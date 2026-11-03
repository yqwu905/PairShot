package com.pairshot.app.phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.core.content.ContextCompat
import com.pairshot.app.data.PhonePhoto
import com.pairshot.app.data.SessionStore
import com.pairshot.app.data.safeName
import com.pairshot.app.data.sha256
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.Closeable
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class PhoneLens(
    val key: String, val cameraId: String, val physicalId: String?, val label: String,
    val focalLength: Float, val front: Boolean,
)
data class PhoneState(
    val permission: Boolean = false, val lenses: List<PhoneLens> = emptyList(), val selected: List<String> = emptyList(),
    val ready: Boolean = false, val capturing: Boolean = false, val message: String = "请授权手机摄像头",
)

/** Prepared Camera2 sessions. Logical physical cameras and declared concurrent sets only. */
class PhoneCameraController(private val context: Context, private val store: SessionStore) : Closeable {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("PairShot-Camera2").apply { start() }
    private val handler = Handler(thread.looper)
    private val scope = CoroutineScope(SupervisorJob() + handler.asCoroutineDispatcher("phone-camera"))
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val executor = Executor { handler.post(it) }
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(PhoneState())
    val state = mutable.asStateFlow()
    private val previews = mutableMapOf<String, TextureView>()
    private var running = false
    private var configureJob: Job? = null
    private val groups = mutableListOf<Group>()
    private val pending = mutableMapOf<String, Pending>()
    private var closed = false

    private data class LensOutput(val lens: PhoneLens, val reader: ImageReader, val preview: Surface)
    private data class Group(val device: CameraDevice, val session: CameraCaptureSession, val outputs: List<LensOutput>)
    private data class Pending(val id: String, val deferred: CompletableDeferred<PhonePhoto>, val requestedNs: Long, var sensorNs: Long = 0)

    fun refresh() {
        val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!permission) { mutable.update { it.copy(permission = false, ready = false) }; return }
        try {
            val list = mutableListOf<PhoneLens>()
            for (id in manager.cameraIdList) {
                val chars = manager.getCameraCharacteristics(id)
                val front = chars[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_FRONT
                val focal = chars[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.firstOrNull() ?: 0f
                val side = if (front) "前置" else "后置"
                list += PhoneLens(id, id, null, "$side · 相机 $id · ${"%.1f".format(focal)} mm", focal, front)
                for (physical in chars.physicalCameraIds.sorted()) {
                    val pc = manager.getCameraCharacteristics(physical)
                    val pf = pc[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.firstOrNull() ?: 0f
                    list += PhoneLens("$id:$physical", id, physical, "$side · 物理镜头 $physical · ${"%.1f".format(pf)} mm", pf, front)
                }
            }
            val selected = mutable.value.selected.filter { key -> list.any { it.key == key } }
                .ifEmpty { list.firstOrNull { !it.front && it.physicalId == null }?.let { listOf(it.key) } ?: list.take(1).map { it.key } }
            mutable.update { it.copy(permission = true, lenses = list, selected = selected, message = "选择一枚镜头；可在硬件支持时同时选择两枚") }
            reconfigure()
        } catch (error: Exception) {
            mutable.update { it.copy(permission = true, message = error.message ?: "无法读取手机镜头") }
        }
    }

    fun toggle(key: String) {
        if (mutable.value.capturing) return
        val selected = mutable.value.selected.toMutableList()
        if (key in selected) selected.remove(key) else {
            if (selected.size >= 2) { mutable.update { it.copy(message = "当前版本最多同时使用两枚手机镜头") }; return }
            selected += key
        }
        val options = mutable.value.lenses.filter { it.key in selected }
        val problem = combinationProblem(options)
        if (problem != null) { mutable.update { it.copy(message = problem) }; return }
        mutable.update { it.copy(selected = selected, ready = false) }
        reconfigure()
    }

    private fun combinationProblem(lenses: List<PhoneLens>): String? {
        if (lenses.size < 2) return null
        if (lenses.map { it.cameraId }.distinct().size == 1) {
            return if (lenses.all { it.physicalId != null }) null else "同一逻辑相机的自动镜头与物理镜头不能同时选择"
        }
        if (Build.VERSION.SDK_INT < 30) return "Android 10 仅支持单镜头或同一逻辑相机下的双物理镜头"
        val ids = lenses.map { it.cameraId }.toSet()
        return if (manager.concurrentCameraIds.any { it.containsAll(ids) }) null else "系统未声明这组镜头可以同时工作，请逐个选择拍摄"
    }

    fun attach(key: String, view: TextureView) {
        synchronized(previews) { previews[key] = view }
        reconfigure()
    }
    fun detach(key: String, view: TextureView) {
        synchronized(previews) { if (previews[key] === view) previews.remove(key) }
        reconfigure()
    }
    fun setRunning(value: Boolean) {
        running = value
        if (value && !mutable.value.permission) refresh()
        reconfigure()
    }

    private fun reconfigure() {
        if (closed) return
        mutable.update { it.copy(ready = false) }
        configureJob?.cancel()
        configureJob = scope.launch {
            mutex.withLock {
                releaseGroups()
                val state = mutable.value
                if (!running || !state.permission || state.selected.isEmpty()) return@withLock
                val selected = state.selected.mapNotNull { key -> state.lenses.firstOrNull { it.key == key } }
                val views = synchronized(previews) { previews.toMap() }
                if (selected.any { views[it.key]?.isAvailable != true }) return@withLock
                try {
                    for ((id, lenses) in selected.groupBy { it.cameraId }) {
                        val outputs = mutableListOf<LensOutput>()
                        var device: CameraDevice? = null
                        try {
                            for (lens in lenses) outputs += makeOutput(lens, checkNotNull(views[lens.key]), selected.size > 1)
                            device = open(id)
                            val configs = outputs.flatMap { output ->
                                listOf(OutputConfiguration(output.reader.surface), OutputConfiguration(output.preview)).onEach {
                                    if (output.lens.physicalId != null) it.setPhysicalCameraId(output.lens.physicalId)
                                }
                            }
                            val session = createSession(device, configs)
                            groups += Group(device, session, outputs)
                        } catch (error: Exception) {
                            outputs.forEach { it.reader.close(); it.preview.release() }
                            device?.close()
                            throw error
                        }
                    }
                    for (group in groups) {
                        val request = group.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            group.outputs.forEach { addTarget(it.preview) }
                            autoControls(this, group.device.id)
                        }
                        group.session.setRepeatingRequest(request.build(), null, handler)
                    }
                    mutable.update { it.copy(ready = true, message = if (selected.size > 1) "双镜头已就绪 · 实际曝光差异受手机硬件影响" else "手机镜头已就绪") }
                } catch (error: Exception) {
                    releaseGroups()
                    mutable.update { it.copy(ready = false, message = "镜头初始化失败：${error.message ?: "该组合不受硬件支持"}") }
                    if (error is kotlinx.coroutines.CancellationException) throw error
                }
            }
        }
    }

    private fun makeOutput(lens: PhoneLens, view: TextureView, multiple: Boolean): LensOutput {
        var reader: ImageReader? = null
        var surface: Surface? = null
        try {
            val chars = manager.getCameraCharacteristics(lens.physicalId ?: lens.cameraId)
            val streams = checkNotNull(chars[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP])
            val maxPixels = if (multiple) 1920L * 1080 else 12_500_000L
            val sizes = streams.getOutputSizes(ImageFormat.JPEG).orEmpty()
            val size = sizes.filter { it.width.toLong() * it.height <= maxPixels }.maxByOrNull { it.width.toLong() * it.height }
                ?: sizes.minByOrNull { it.width.toLong() * it.height } ?: error("镜头不支持 JPEG 输出")
            reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 3)
            reader.setOnImageAvailableListener({ source -> onImage(lens, source) }, handler)
            val previewSize = streams.getOutputSizes(SurfaceTexture::class.java).orEmpty()
                .filter { it.width <= 1920 && it.height <= 1080 }
                .minWithOrNull(compareBy<Size> { kotlin.math.abs(it.width.toFloat() / it.height - size.width.toFloat() / size.height) }
                    .thenByDescending { it.width * it.height }) ?: Size(640, 480)
            val texture = checkNotNull(view.surfaceTexture) { "预览已关闭" }
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            view.post { transform(view, previewSize) }
            surface = Surface(texture)
            return LensOutput(lens, reader, surface)
        } catch (error: Exception) {
            reader?.close(); surface?.release()
            throw error
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun open(id: String): CameraDevice = suspendCancellableCoroutine { continuation ->
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (continuation.isActive) continuation.resume(camera) { _, value, _ -> value.close() } else camera.close()
            }
            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException("手机摄像头已断开"))
                else mutable.update { it.copy(ready = false, message = "手机摄像头被断开，请重新选择镜头") }
            }
            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException("手机摄像头错误 $error"))
                else mutable.update { it.copy(ready = false, message = "手机摄像头错误 $error，请重新选择镜头") }
            }
        }, handler)
    }

    private suspend fun createSession(device: CameraDevice, outputs: List<OutputConfiguration>): CameraCaptureSession = suspendCancellableCoroutine { continuation ->
        device.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (continuation.isActive) continuation.resume(session) { _, value, _ -> value.close() } else session.close()
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("硬件拒绝此镜头/分辨率组合，请选择单镜头"))
                }
            }))
    }

    private fun autoControls(request: CaptureRequest.Builder, id: String) {
        request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        val modes = manager.getCameraCharacteristics(id)[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES] ?: intArrayOf()
        if (CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in modes) request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
    }

    suspend fun capture(shotId: String): List<PhonePhoto> = withContext(handler.asCoroutineDispatcher()) {
        mutex.withLock {
            check(mutable.value.ready && groups.isNotEmpty()) { "手机镜头尚未就绪" }
            mutable.update { it.copy(capturing = true) }
            val requested = SystemClock.elapsedRealtimeNanos()
            try {
                val waiters = groups.flatMap { it.outputs }.associate { output ->
                    while (true) { val old = output.reader.acquireLatestImage() ?: break; old.close() }
                    val result = CompletableDeferred<PhonePhoto>()
                    pending[output.lens.key] = Pending(shotId, result, requested)
                    output.lens.key to result
                }
                for (group in groups) {
                    val view = synchronized(previews) { previews[group.outputs.first().lens.key] }
                    val degrees = when (view?.display?.rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
                    val chars = manager.getCameraCharacteristics(group.device.id)
                    val sensor = chars[CameraCharacteristics.SENSOR_ORIENTATION] ?: 90
                    val front = group.outputs.first().lens.front
                    val request = group.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        group.outputs.forEach { addTarget(it.reader.surface) }
                        autoControls(this, group.device.id)
                        set(CaptureRequest.JPEG_ORIENTATION, (sensor + (if (front) degrees else -degrees) + 360) % 360)
                        set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                    }
                    group.session.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                            // Logical physical outputs can have independent timestamps. Keep each
                            // Image timestamp as the authoritative value rather than the logical one.
                            if (group.outputs.size == 1 && group.outputs.first().lens.physicalId == null)
                                pending[group.outputs.first().lens.key]?.takeIf { it.id == shotId }?.sensorNs = timestamp
                        }
                        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                            group.outputs.forEach { output ->
                                if (pending[output.lens.key]?.id == shotId) pending.remove(output.lens.key)?.deferred
                                    ?.completeExceptionally(IllegalStateException("手机拍摄失败 ${failure.reason}"))
                            }
                        }
                    }, handler)
                }
                withTimeout(12_000) { waiters.values.map { async { it.await() } }.awaitAll() }
            } catch (error: Exception) {
                // Recreate readers before accepting another request, so a late JPEG
                // from a timed-out request cannot be assigned to a new shot.
                groups.forEach { runCatching { it.session.abortCaptures() } }
                reconfigure()
                throw error
            } finally {
                pending.values.forEach { it.deferred.cancel() }
                pending.clear()
                mutable.update { it.copy(capturing = false) }
            }
        }
    }

    private fun onImage(lens: PhoneLens, source: ImageReader) {
        val image = try { source.acquireNextImage() } catch (_: IllegalStateException) { null } ?: return
        val request = pending[lens.key]
        if (request == null || groups.none { group -> group.outputs.any { it.reader === source } } ||
            (request.sensorNs != 0L && request.sensorNs != image.timestamp)) { image.close(); return }
        val timestamp = image.timestamp
        val callback = SystemClock.elapsedRealtimeNanos()
        val bytes: ByteArray
        try {
            bytes = ByteArray(image.planes[0].buffer.remaining())
            image.planes[0].buffer.get(bytes)
        } catch (error: Exception) {
            pending.remove(lens.key)
            request.deferred.completeExceptionally(error)
            return
        } finally { image.close() }
        pending.remove(lens.key)
        ioScope.launch {
            var partial: java.io.File? = null
            try {
                val sourceType = manager.getCameraCharacteristics(lens.physicalId ?: lens.cameraId)[CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE]
                val path = "phone/${request.id}/${safeName(lens.key)}.jpg"
                val file = store.file(path)
                file.parentFile!!.mkdirs()
                partial = java.io.File(file.path + ".part")
                partial.outputStream().use { it.write(bytes); it.flush() }
                check(partial.renameTo(file)) { "无法保存手机照片" }
                val photo = PhonePhoto(lens.key, lens.label, path, file.length(), sha256(file), timestamp,
                    if (sourceType == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) "REALTIME" else "UNKNOWN", request.requestedNs, callback)
                store.shot(request.id) { it.copy(phone = it.phone.filterNot { existing -> existing.lens == lens.key } + photo) }
                request.deferred.complete(photo)
            } catch (error: Exception) { partial?.delete(); request.deferred.completeExceptionally(error) }
        }
    }

    private fun transform(view: TextureView, preview: Size) {
        val rotation = view.display?.rotation ?: Surface.ROTATION_0
        val width = view.width.toFloat(); val height = view.height.toFloat()
        val matrix = Matrix()
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            val viewRect = RectF(0f, 0f, width, height)
            val bufferRect = RectF(0f, 0f, preview.height.toFloat(), preview.width.toFloat())
            bufferRect.offset(viewRect.centerX() - bufferRect.centerX(), viewRect.centerY() - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = maxOf(height / preview.height, width / preview.width)
            matrix.postScale(scale, scale, viewRect.centerX(), viewRect.centerY())
            matrix.postRotate(90f * (rotation - 2), viewRect.centerX(), viewRect.centerY())
        } else if (rotation == Surface.ROTATION_180) matrix.postRotate(180f, width / 2, height / 2)
        view.setTransform(matrix)
    }

    private fun releaseGroups() {
        pending.values.forEach { it.deferred.completeExceptionally(IllegalStateException("镜头会话已关闭")) }
        pending.clear()
        groups.forEach { group ->
            runCatching { group.session.stopRepeating() }
            group.session.close(); group.device.close()
            group.outputs.forEach { it.reader.close(); it.preview.release() }
        }
        groups.clear()
        mutable.update { it.copy(ready = false) }
    }

    override fun close() {
        closed = true
        configureJob?.cancel()
        scope.launch {
            mutex.withLock { releaseGroups() }
            ioScope.cancel(); scope.cancel(); thread.quitSafely()
        }
    }
}
