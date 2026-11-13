package com.pairshot.app.phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.Closeable
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class PhoneLens(
    val key: String, val cameraId: String, val physicalId: String?, val label: String,
    val focalLength: Float, val front: Boolean,
    val minZoom: Float = 1f, val maxZoom: Float = 1f, val zoomRatioControl: Boolean = false,
)
data class PhoneState(
    val permission: Boolean = false, val lenses: List<PhoneLens> = emptyList(), val selected: List<String> = emptyList(),
    val ready: Boolean = false, val capturing: Boolean = false, val message: String = "请授权手机摄像头",
    val zoomRatios: Map<String, Float> = emptyMap(),
    val sequential: Boolean = false,
)

interface PhoneCamera : Closeable {
    val state: StateFlow<PhoneState>
    fun refresh()
    fun toggle(key: String)
    fun setZoom(key: String, ratio: Float)
    fun attach(key: String, view: TextureView)
    fun detach(key: String, view: TextureView)
    fun setRunning(value: Boolean)
    suspend fun capture(shotId: String): List<PhonePhoto>
}

internal fun zoomCropRegion(active: Rect, ratio: Float): Rect {
    require(ratio.isFinite() && ratio >= 1f)
    val width = (active.width() / ratio).toInt().coerceAtLeast(1)
    val height = (active.height() / ratio).toInt().coerceAtLeast(1)
    val left = active.left + (active.width() - width) / 2
    val top = active.top + (active.height() - height) / 2
    return Rect(left, top, left + width, top + height)
}

internal fun supportsConcurrentCapture(lenses: List<PhoneLens>, sdk: Int, concurrentCameraIds: Set<Set<String>>): Boolean {
    if (lenses.size < 2) return true
    val ids = lenses.map { it.cameraId }.toSet()
    if (ids.size == 1) return lenses.all { it.physicalId != null }
    return sdk >= 30 && concurrentCameraIds.any { it.containsAll(ids) }
}

/** Prefer prepared concurrent sessions, with one-camera-at-a-time capture as a fallback. */
class PhoneCameraController(private val context: Context, private val store: SessionStore) : PhoneCamera {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("PairShot-Camera2").apply { start() }
    private val handler = Handler(thread.looper)
    private val scope = CoroutineScope(SupervisorJob() + handler.asCoroutineDispatcher("phone-camera"))
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val executor = Executor { handler.post(it) }
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(PhoneState())
    override val state = mutable.asStateFlow()
    private val previews = mutableMapOf<String, TextureView>()
    private var running = false
    private var configureJob: Job? = null
    private val groups = mutableListOf<Group>()
    private val pending = mutableMapOf<String, Pending>()
    private val devices = mutableSetOf<DeviceLease>()
    private val sequentialCombinations = mutableSetOf<Set<String>>()
    private var closed = false

    private data class LensOutput(val lens: PhoneLens, val reader: ImageReader, val preview: Surface)
    private class DeviceLease {
        var device: CameraDevice? = null
        var closing = false
        var failure: String? = null
        val closed = CompletableDeferred<Unit>()
    }
    private data class Group(val lease: DeviceLease, val session: CameraCaptureSession, val outputs: List<LensOutput>,
        val firstFrame: CompletableDeferred<Unit> = CompletableDeferred()) {
        val device: CameraDevice get() = checkNotNull(lease.device)
    }
    private class CameraSetupException(message: String) : IllegalStateException(message)
    private class CameraCaptureException(message: String, val saved: List<PhonePhoto> = emptyList()) : IllegalStateException(message)
    private data class Pending(val id: String, val deferred: CompletableDeferred<PhonePhoto>, val requestedNs: Long,
        val zoomRatio: Float, var sensorNs: Long = 0)

    override fun refresh() {
        val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!permission) { mutable.update { it.copy(permission = false, ready = false) }; return }
        try {
            val list = mutableListOf<PhoneLens>()
            for (id in manager.cameraIdList) {
                val chars = manager.getCameraCharacteristics(id)
                val front = chars[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_FRONT
                val focal = chars[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.firstOrNull() ?: 0f
                val side = if (front) "前置" else "后置"
                val zoomRange = if (Build.VERSION.SDK_INT >= 30) chars[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE] else null
                val ratioControl = zoomRange != null && zoomRange.lower.isFinite() && zoomRange.lower > 0f &&
                    zoomRange.upper.isFinite() && zoomRange.upper >= zoomRange.lower
                val minZoom = if (ratioControl) checkNotNull(zoomRange).lower else 1f
                val maxZoom = if (ratioControl) checkNotNull(zoomRange).upper else
                    (chars[CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM] ?: 1f).takeIf { it.isFinite() && it >= 1f } ?: 1f
                list += PhoneLens(id, id, null, "$side · 相机 $id · ${"%.1f".format(focal)} mm", focal, front,
                    minZoom, maxZoom, ratioControl)
                for (physical in chars.physicalCameraIds.sorted()) {
                    val pc = manager.getCameraCharacteristics(physical)
                    val pf = pc[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.firstOrNull() ?: 0f
                    list += PhoneLens("$id:$physical", id, physical, "$side · 物理镜头 $physical · ${"%.1f".format(pf)} mm", pf, front)
                }
            }
            val selected = mutable.value.selected.filter { key -> list.any { it.key == key } }
                .ifEmpty { list.firstOrNull { !it.front && it.physicalId == null }?.let { listOf(it.key) } ?: list.take(1).map { it.key } }
            mutable.update { old -> old.copy(permission = true, lenses = list, selected = selected,
                zoomRatios = list.associate { it.key to (old.zoomRatios[it.key] ?: 1f).coerceIn(it.minZoom, it.maxZoom) },
                message = "选择镜头与变焦倍率；双镜头不支持同时拍摄时会自动依次拍摄") }
            reconfigure()
        } catch (error: Exception) {
            mutable.update { it.copy(permission = true, message = error.message ?: "无法读取手机镜头") }
        }
    }

    override fun toggle(key: String) {
        if (mutable.value.capturing || mutable.value.lenses.none { it.key == key }) return
        val selected = mutable.value.selected.toMutableList()
        if (key in selected) selected.remove(key) else {
            if (selected.size >= 2) { mutable.update { it.copy(message = "当前版本最多选择两枚手机镜头") }; return }
            selected += key
        }
        mutable.update { it.copy(selected = selected, ready = false) }
        reconfigure()
    }

    override fun setZoom(key: String, ratio: Float) {
        if (closed || mutable.value.capturing || !ratio.isFinite() || key !in mutable.value.selected) return
        val lens = mutable.value.lenses.firstOrNull { it.key == key && it.physicalId == null } ?: return
        val value = ratio.coerceIn(lens.minZoom, lens.maxZoom)
        if (value == mutable.value.zoomRatios[key]) return
        mutable.update { it.copy(zoomRatios = it.zoomRatios + (key to value)) }
        scope.launch {
            mutex.withLock {
                try { groups.forEach(::updatePreview) }
                catch (error: Exception) {
                    runCatching { releaseGroups() }
                    if (error is CancellationException) throw error
                    mutable.update { it.copy(message = "变焦设置失败：${error.message}，请重新选择镜头") }
                }
            }
        }
    }

    private fun needsSequential(lenses: List<PhoneLens>): Boolean {
        if (lenses.size < 2) return false
        if (lenses.map { it.key }.toSet() in sequentialCombinations) return true
        val concurrentIds = if (Build.VERSION.SDK_INT >= 30 && lenses.map { it.cameraId }.distinct().size > 1)
            manager.concurrentCameraIds else emptySet()
        return !supportsConcurrentCapture(lenses, Build.VERSION.SDK_INT, concurrentIds)
    }

    override fun attach(key: String, view: TextureView) {
        synchronized(previews) { previews[key] = view }
        reconfigure()
    }
    override fun detach(key: String, view: TextureView) {
        synchronized(previews) { if (previews[key] === view) previews.remove(key) }
        reconfigure()
    }
    override fun setRunning(value: Boolean) {
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
                try {
                    releaseGroups()
                    val state = mutable.value
                    if (!running || closed || !state.permission || state.selected.isEmpty()) return@withLock
                    val selected = state.selected.mapNotNull { key -> state.lenses.firstOrNull { it.key == key } }
                    val views = synchronized(previews) { previews.toMap() }
                    if (selected.any { views[it.key]?.isAvailable != true }) return@withLock
                    var sequential = needsSequential(selected)
                    mutable.update { it.copy(sequential = sequential) }
                    try {
                        prepareGroups(if (sequential) selected.take(1) else selected, views)
                    } catch (error: Exception) {
                        if (error is CancellationException || error is SecurityException || selected.size < 2 || sequential) throw error
                        // A declared pair can still exceed the device's actual stream limits.
                        releaseGroups()
                        sequentialCombinations += selected.map { it.key }.toSet()
                        sequential = true
                        mutable.update { it.copy(sequential = true, message = "已切换为双镜头依次拍摄…") }
                        prepareGroups(selected.take(1), views)
                    }
                    mutable.update { it.copy(ready = true, sequential = sequential, message = when {
                        sequential -> "双镜头已就绪 · 将按选择顺序连续拍摄两张，当前仅预览第一枚镜头"
                        selected.size > 1 -> "双镜头已就绪 · 实际曝光差异受手机硬件影响"
                        else -> "手机镜头已就绪"
                    }) }
                } catch (error: Exception) {
                    // Keep leases whose onClosed has not arrived; never reopen over
                    // them, but do not let a cleanup timeout crash the camera worker.
                    withContext(NonCancellable) { runCatching { releaseGroups() } }
                    if (error is CancellationException) throw error
                    mutable.update { it.copy(ready = false, message = "镜头初始化失败：${error.message ?: "该组合不受硬件支持"}") }
                }
            }
        }
    }

    private suspend fun prepareGroups(selected: List<PhoneLens>, views: Map<String, TextureView>) {
        check(running && !closed) { "手机拍摄已停止" }
        val grouped = selected.groupBy { it.cameraId }
        // Camera2 concurrent sets require opening every device before configuring
        // any of their sessions, otherwise the first session can reserve resources.
        val leases = grouped.keys.associateWith { id -> setupTimeout { open(id) } }
        for ((id, lenses) in grouped) {
            val outputs = mutableListOf<LensOutput>()
            try {
                for (lens in lenses) outputs += makeOutput(lens, checkNotNull(views[lens.key]), selected.size > 1)
                val lease = leases.getValue(id)
                val configs = outputs.flatMap { output ->
                    listOf(OutputConfiguration(output.reader.surface), OutputConfiguration(output.preview)).onEach {
                        if (output.lens.physicalId != null) it.setPhysicalCameraId(output.lens.physicalId)
                    }
                }
                val session = setupTimeout { createSession(checkNotNull(lease.device), configs) }
                groups += Group(lease, session, outputs)
            } catch (error: Exception) {
                outputs.forEach { it.reader.close(); it.preview.release() }
                throw error
            }
        }
        groups.forEach(::updatePreview)
        // A configured session alone does not mean the new sensor is producing frames.
        setupTimeout { groups.forEach { it.firstFrame.await() } }
        groups.firstNotNullOfOrNull { it.lease.failure }?.let { throw CameraSetupException(it) }
    }

    private suspend fun <T> setupTimeout(block: suspend () -> T): T = try {
        withTimeout(5_000) { block() }
    } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw CameraSetupException("等待手机镜头就绪超时")
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
    private suspend fun open(id: String): DeviceLease = suspendCancellableCoroutine { continuation ->
        val lease = DeviceLease()
        devices += lease
        continuation.invokeOnCancellation {
            handler.post { lease.closing = true; lease.device?.close() }
        }
        try { manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                lease.device = camera
                if (continuation.isActive && !lease.closing) continuation.resume(lease) { _, value, _ ->
                    handler.post { value.closing = true; value.device?.close() }
                } else { lease.closing = true; camera.close() }
            }
            override fun onDisconnected(camera: CameraDevice) {
                lease.device = camera
                camera.close()
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException("手机摄像头已断开"))
                else onDeviceFailure(lease, "手机摄像头被断开")
            }
            override fun onError(camera: CameraDevice, error: Int) {
                lease.device = camera
                camera.close()
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException("手机摄像头错误 $error"))
                else onDeviceFailure(lease, "手机摄像头错误 $error")
            }
            override fun onClosed(camera: CameraDevice) { lease.closed.complete(Unit) }
        }, handler) } catch (error: Exception) {
            devices.remove(lease)
            lease.closed.complete(Unit)
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }

    private fun onDeviceFailure(lease: DeviceLease, message: String) {
        if (lease.closing || groups.none { it.lease === lease }) return
        lease.failure = message
        groups.filter { it.lease === lease }.forEach { it.firstFrame.completeExceptionally(CameraSetupException(message)) }
        groups.filter { it.lease === lease }.flatMap { it.outputs }.forEach {
            pending.remove(it.lens.key)?.deferred?.completeExceptionally(CameraCaptureException(message))
        }
        mutable.update { it.copy(ready = false, message = "$message，请重新选择镜头") }
        if (!mutable.value.capturing && mutable.value.selected.size > 1 && !mutable.value.sequential && configureJob?.isActive != true) {
            sequentialCombinations += mutable.value.selected.toSet()
            reconfigure()
        }
    }

    private suspend fun createSession(device: CameraDevice, outputs: List<OutputConfiguration>): CameraCaptureSession = suspendCancellableCoroutine { continuation ->
        device.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (continuation.isActive) continuation.resume(session) { _, value, _ -> value.close() } else session.close()
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                    if (continuation.isActive) continuation.resumeWithException(CameraSetupException("硬件拒绝此镜头/分辨率组合"))
                }
            }))
    }

    private fun autoControls(request: CaptureRequest.Builder, id: String) {
        request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        val modes = manager.getCameraCharacteristics(id)[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES] ?: intArrayOf()
        if (CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in modes) request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
    }

    private fun zoomControls(request: CaptureRequest.Builder, group: Group) {
        val lens = group.outputs.singleOrNull()?.lens ?: return
        // Physical outputs retain their native view. A logical zoom ratio must
        // never be applied to a pair of separately routed physical outputs.
        if (lens.physicalId != null) return
        val ratio = (mutable.value.zoomRatios[lens.key] ?: 1f).coerceIn(lens.minZoom, lens.maxZoom)
        if (Build.VERSION.SDK_INT >= 30 && lens.zoomRatioControl) {
            request.set(CaptureRequest.CONTROL_ZOOM_RATIO, ratio)
        } else if (lens.maxZoom > 1f) {
            val active = checkNotNull(manager.getCameraCharacteristics(lens.cameraId)[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE])
            request.set(CaptureRequest.SCALER_CROP_REGION, zoomCropRegion(active, ratio))
        }
    }

    private fun updatePreview(group: Group) {
        val request = group.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            group.outputs.forEach { addTarget(it.preview) }
            autoControls(this, group.device.id)
            zoomControls(this, group)
        }
        group.session.setRepeatingRequest(request.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                group.firstFrame.complete(Unit)
            }
            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                group.firstFrame.completeExceptionally(CameraSetupException("镜头预览失败 ${failure.reason}"))
            }
        }, handler)
    }

    override suspend fun capture(shotId: String): List<PhonePhoto> = withContext(handler.asCoroutineDispatcher()) {
        mutex.withLock {
            check(running && !closed && mutable.value.ready && groups.isNotEmpty()) { "手机镜头尚未就绪" }
            val state = mutable.value
            val selected = state.selected.map { key -> state.lenses.first { it.key == key } }
            var restorePreview = state.sequential
            mutable.update { it.copy(capturing = true) }
            val requested = SystemClock.elapsedRealtimeNanos()
            try {
                if (state.sequential) captureSequential(selected, shotId, requested)
                else try {
                    captureGroups(shotId, requested).sortedBy { state.selected.indexOf(it.lens) }
                } catch (error: CameraCaptureException) {
                    if (selected.size < 2) throw error
                    restorePreview = true
                    sequentialCombinations += state.selected.toSet()
                    mutable.update { it.copy(sequential = true, message = "已切换为双镜头依次拍摄…") }
                    // captureGroups settles image writes before returning failures.
                    // Preserve successful images and only retry missing lenses once.
                    releaseGroups()
                    val saved = error.saved.associateBy { it.lens }
                    val photos = saved.values + captureSequential(selected.filterNot { it.key in saved }, shotId, requested)
                    photos.sortedBy { state.selected.indexOf(it.lens) }
                }
            } catch (error: Exception) {
                restorePreview = true
                groups.forEach { runCatching { it.session.abortCaptures() } }
                throw error
            } finally {
                if (groups.any { it.lease.failure != null }) {
                    restorePreview = true
                    if (selected.size > 1 && !state.sequential) {
                        sequentialCombinations += state.selected.toSet()
                        mutable.update { it.copy(sequential = true) }
                    }
                }
                mutable.update { it.copy(capturing = false) }
                // Preview restoration is separate from saving the photos: failure to
                // reopen the preview must not turn an already saved pair into a failed shot.
                if (restorePreview) reconfigure()
            }
        }
    }

    private suspend fun captureSequential(selected: List<PhoneLens>, shotId: String, requested: Long): List<PhonePhoto> {
        val photos = mutableListOf<PhonePhoto>()
        for ((index, lens) in selected.withIndex()) {
            currentCoroutineContext().ensureActive()
            check(running && !closed) { "手机拍摄已停止" }
            mutable.update { it.copy(message = "正在依次拍摄 ${index + 1}/${selected.size}…") }
            if (groups.singleOrNull()?.outputs?.singleOrNull()?.lens?.key != lens.key) {
                releaseGroups()
                val views = synchronized(previews) { previews.toMap() }
                prepareGroups(listOf(lens), views)
            }
            photos += captureGroups(shotId, requested)
        }
        return photos
    }

    private suspend fun captureGroups(shotId: String, requested: Long): List<PhonePhoto> {
        currentCoroutineContext().ensureActive()
        check(running && !closed) { "手机拍摄已停止" }
        val waiters = mutableMapOf<String, Pending>()
        try {
            for (output in groups.flatMap { it.outputs }) {
                while (true) { val old = output.reader.acquireLatestImage() ?: break; old.close() }
                val result = Pending(shotId, CompletableDeferred(), requested, mutable.value.zoomRatios[output.lens.key] ?: 1f)
                waiters[output.lens.key] = result
                pending[output.lens.key] = result
            }
            try {
                for (group in groups) {
                    val view = synchronized(previews) { previews[group.outputs.first().lens.key] }
                    val degrees = when (view?.display?.rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
                    val chars = manager.getCameraCharacteristics(group.device.id)
                    val sensor = chars[CameraCharacteristics.SENSOR_ORIENTATION] ?: 90
                    val front = group.outputs.first().lens.front
                    val request = group.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        group.outputs.forEach { addTarget(it.reader.surface) }
                        autoControls(this, group.device.id)
                        zoomControls(this, group)
                        set(CaptureRequest.JPEG_ORIENTATION, (sensor + (if (front) degrees else -degrees) + 360) % 360)
                        set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                    }
                    group.session.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                            // Logical physical outputs can have independent timestamps. Keep each
                            // Image timestamp as the authoritative value rather than the logical one.
                            val lens = group.outputs.singleOrNull()?.lens
                            if (lens != null && lens.physicalId == null)
                                pending[lens.key]?.takeIf { it === waiters[lens.key] }?.sensorNs = timestamp
                        }
                        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                            group.outputs.forEach { output ->
                                if (pending[output.lens.key] === waiters[output.lens.key]) pending.remove(output.lens.key)?.deferred
                                    ?.completeExceptionally(CameraCaptureException("手机拍摄失败 ${failure.reason}"))
                            }
                        }
                    }, handler)
                }
                // Do not fail fast: a peer image may already be writing to disk.
                // Settling every result prevents a fallback from overwriting it.
                withTimeout(12_000) {
                    waiters.values.forEach {
                        runCatching { it.deferred.await() }
                        currentCoroutineContext().ensureActive()
                    }
                }
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                val failure = when (error) {
                    is kotlinx.coroutines.TimeoutCancellationException -> CameraCaptureException("等待手机照片超时")
                    is CameraAccessException, is IllegalStateException, is IllegalArgumentException ->
                        CameraCaptureException(error.message ?: "手机拍摄请求失败")
                    else -> error
                }
                groups.forEach { runCatching { it.session.abortCaptures() } }
                waiters.forEach { (key, value) ->
                    if (pending[key] === value) {
                        pending.remove(key)
                        value.deferred.completeExceptionally(failure)
                    }
                }
                if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
            }
            val outcomes = waiters.values.map {
                runCatching { it.deferred.await() }.also { currentCoroutineContext().ensureActive() }
            }
            val failures = outcomes.mapNotNull { it.exceptionOrNull() }
            // Storage/permission errors are real errors, not concurrency limitations.
            failures.firstOrNull { it !is CameraCaptureException }?.let { throw it }
            val photos = outcomes.mapNotNull { it.getOrNull() }
            failures.firstOrNull()?.let { throw CameraCaptureException(it.message ?: "手机拍摄失败", photos) }
            return photos
        } finally {
            waiters.forEach { (key, value) ->
                if (pending[key] === value) { pending.remove(key); value.deferred.cancel() }
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
                    if (sourceType == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) "REALTIME" else "UNKNOWN", request.requestedNs, callback,
                    lens.focalLength.takeIf { it.isFinite() && it > 0f }, request.zoomRatio)
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

    private suspend fun releaseGroups() = withContext(NonCancellable) {
        pending.values.forEach { it.deferred.completeExceptionally(IllegalStateException("镜头会话已关闭")) }
        pending.clear()
        val closing = devices.toList()
        closing.forEach { it.closing = true }
        groups.forEach { group ->
            runCatching { group.session.stopRepeating() }
            group.session.close()
            group.outputs.forEach { it.reader.close(); it.preview.release() }
        }
        groups.clear()
        mutable.update { it.copy(ready = false) }
        closing.forEach { it.device?.close() }
        // close() is asynchronous. In particular, a phone that allows only one
        // camera at a time must acknowledge closure before the next openCamera.
        try { withTimeout(5_000) { closing.forEach { it.closed.await() } } }
        catch (error: kotlinx.coroutines.TimeoutCancellationException) { throw CameraSetupException("等待手机镜头关闭超时") }
        devices.removeAll(closing.toSet())
    }

    override fun close() {
        closed = true
        configureJob?.cancel()
        scope.launch {
            try { mutex.withLock { runCatching { releaseGroups() } } }
            finally { ioScope.cancel(); scope.cancel(); thread.quitSafely() }
        }
    }
}
