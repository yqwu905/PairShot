package com.pairshot.app.phone

import android.Manifest
import android.app.Application
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.TextureView
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import com.pairshot.app.data.SessionStore
import com.pairshot.app.data.PHONE_ONLY_MODE
import com.pairshot.app.data.PhonePhoto
import com.pairshot.app.data.ShotRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowCameraCaptureSessionImpl
import org.robolectric.shadows.ShadowCameraCharacteristics
import org.robolectric.shadows.ShadowCameraDeviceImpl
import org.robolectric.shadows.ShadowCameraManager
import org.robolectric.shadows.ShadowImageReader
import org.robolectric.shadows.StreamConfigurationMapBuilder
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.Executor
import java.nio.ByteBuffer

/** Exercise the controller's real configuration and device-close continuations. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [
    PhoneSequentialCaptureTest.CharacteristicsShadow::class,
    PhoneSequentialCaptureTest.ManagerShadow::class,
    PhoneSequentialCaptureTest.DeviceShadow::class,
    PhoneSequentialCaptureTest.SessionShadow::class,
    PhoneSequentialCaptureTest.JpegImageShadow::class,
])
class PhoneSequentialCaptureTest {
    @get:Rule val temp = TemporaryFolder()

    companion object {
        private val events = mutableListOf<String>()
        private val closeCallbacks = mutableListOf<() -> Unit>()
        private var declaresConcurrent = false
        private var rejectSecondSession = false
        private var deferDeviceClosed = false
        private var failSecondCapture = false
        private var failSecondCaptureOnce = false
        private var imageTimestamp = 100L
    }

    @Before fun resetHardware() {
        events.clear()
        closeCallbacks.clear()
        declaresConcurrent = false
        rejectSecondSession = false
        deferDeviceClosed = false
        failSecondCapture = false
        failSecondCaptureOnce = false
        imageTimestamp = 100L
    }

    @Implements(CameraCharacteristics::class)
    class CharacteristicsShadow : ShadowCameraCharacteristics() {
        @Implementation(minSdk = 28)
        protected fun getPhysicalCameraIds(): Set<String> = emptySet()
    }

    @Implements(CameraManager::class)
    class ManagerShadow : ShadowCameraManager() {
        @Implementation(minSdk = 30)
        protected fun getConcurrentCameraIds(): Set<Set<String>> =
            if (declaresConcurrent) setOf(setOf("0", "1")) else emptySet()

        @Implementation
        protected fun openCamera(id: String, callback: CameraDevice.StateCallback, handler: Handler) {
            events += "open:$id"
            val forwarding = object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) = callback.onOpened(camera)
                override fun onDisconnected(camera: CameraDevice) = callback.onDisconnected(camera)
                override fun onError(camera: CameraDevice, error: Int) = callback.onError(camera, error)
                override fun onClosed(camera: CameraDevice) {
                    val closed = {
                        events += "closed:$id"
                        callback.onClosed(camera)
                    }
                    if (deferDeviceClosed) closeCallbacks += closed else closed()
                }
            }
            super.openCameraDeviceUserAsync(id, forwarding, Executor { handler.post(it) }, 0, 0)
        }
    }

    @Implements(className = "android.hardware.camera2.impl.CameraDeviceImpl", isInAndroidSdk = false)
    class DeviceShadow : ShadowCameraDeviceImpl() {
        @RealObject private lateinit var camera: CameraDevice

        @Implementation
        override fun createCaptureSession(configuration: SessionConfiguration) {
            events += "session:${camera.id}"
            val original = configuration.stateCallback
            val forwarding = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (rejectSecondSession && camera.id == "1") original.onConfigureFailed(session)
                    else original.onConfigured(session)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) = original.onConfigureFailed(session)
                override fun onClosed(session: CameraCaptureSession) = original.onClosed(session)
            }
            super.createCaptureSession(SessionConfiguration(configuration.sessionType,
                configuration.outputConfigurations, configuration.executor, forwarding))
        }

        @Implementation
        override fun close() {
            events += "close:${camera.id}"
            super.close()
        }
    }

    @Implements(className = "android.hardware.camera2.impl.CameraCaptureSessionImpl", isInAndroidSdk = false)
    class SessionShadow : ShadowCameraCaptureSessionImpl() {
        @RealObject private lateinit var session: CameraCaptureSession

        @Implementation
        override fun setRepeatingRequest(request: CaptureRequest, callback: CameraCaptureSession.CaptureCallback?, handler: Handler?): Int {
            if (callback != null) checkNotNull(handler).post {
                callback.onCaptureCompleted(session, request, Shadow.newInstanceOf(TotalCaptureResult::class.java))
            }
            return 1
        }

        @Implementation
        protected fun stopRepeating() = Unit

        @Implementation
        override fun capture(request: CaptureRequest, callback: CameraCaptureSession.CaptureCallback?, handler: Handler?): Int {
            events += "capture:${session.device.id}"
            if (failSecondCapture && session.device.id == "1") error("Injected second camera failure")
            if (failSecondCaptureOnce && session.device.id == "1") {
                failSecondCaptureOnce = false
                checkNotNull(handler).post {
                    checkNotNull(callback).onCaptureFailed(session, request, Shadow.newInstanceOf(CaptureFailure::class.java))
                }
                return 1
            }
            // Robolectric's ImageReader surface posts an image-available event when
            // a producer submits a frame, just as the Camera2 output would.
            ReflectionHelpers.callInstanceMethod<Collection<Surface>>(request, "getTargets")
                .forEach { surface -> surface.unlockCanvasAndPost(surface.lockCanvas(null)) }
            return 1
        }

        @Implementation
        protected fun abortCaptures() = Unit
    }

    @Implements(className = "android.media.ImageReader\$SurfaceImage", isInAndroidSdk = false)
    class JpegImageShadow : ShadowImageReader.ShadowSurfaceImage() {
        @RealObject private lateinit var image: Image
        private val timestamp = ++imageTimestamp

        @Implementation
        protected fun getTimestamp(): Long = timestamp

        @Implementation
        protected fun getPlanes(): Array<Image.Plane> {
            val plane = Shadow.newInstanceOf(Class.forName("android.media.ImageReader\$SurfaceImage\$SurfacePlane")) as Image.Plane
            ReflectionHelpers.setField(plane, "this\$1", image)
            ReflectionHelpers.setField(plane, "mBuffer", ByteBuffer.wrap(byteArrayOf(0xff.toByte(), 0xd8.toByte(),
                timestamp.toByte(), 0xff.toByte(), 0xd9.toByte())))
            return arrayOf(plane)
        }
    }

    private class AvailablePreview(app: Application) : TextureView(app) {
        private val texture = SurfaceTexture(0)
        override fun isAvailable() = true
        override fun getSurfaceTexture() = texture
        fun release() = texture.release()
    }

    private fun withController(block: (PhoneCameraController, () -> Unit) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val manager = app.getSystemService(CameraManager::class.java)
        for (id in listOf("0", "1")) {
            val characteristics = ShadowCameraCharacteristics.newCameraCharacteristics()
            shadowOf(characteristics).apply {
                set(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES, intArrayOf())
                set(CameraCharacteristics.LENS_FACING, CameraCharacteristics.LENS_FACING_BACK)
                set(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS, floatArrayOf(4f))
                set(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
                    StreamConfigurationMapBuilder.newBuilder()
                        // StreamConfiguration stores JPEG as HAL_PIXEL_FORMAT_BLOB.
                        .addOutputSize(0x21, Size(640, 480))
                        .addOutputSize(Size(640, 480)).build())
            }
            shadowOf(manager).addCamera(id, characteristics)
        }
        val camera = PhoneCameraController(app, SessionStore(temp.newFolder()))
        val handler = ReflectionHelpers.getField<Handler>(camera, "handler")
        val looper = shadowOf(handler.looper)
        looper.pause()
        val previews = listOf(AvailablePreview(app), AvailablePreview(app))
        val drain = { repeat(10) { looper.idle() } }
        try {
            camera.refresh()
            camera.toggle("1")
            camera.attach("0", previews[0])
            camera.attach("1", previews[1])
            camera.setRunning(true)
            drain()
            block(camera, drain)
        } finally {
            deferDeviceClosed = false
            closeCallbacks.toList().forEach { handler.post(it) }
            closeCallbacks.clear()
            camera.close()
            looper.unPause()
            ReflectionHelpers.getField<HandlerThread>(camera, "thread").join(2_000)
            previews.forEach { it.release() }
        }
    }

    @Test fun unsupportedConcurrentPairPreviewsOnlyTheFirstSelectedCamera() {
        withController { camera, _ ->
            assertTrue(camera.state.value.message, camera.state.value.ready)
            assertTrue(camera.state.value.sequential)
            assertEquals(listOf("0", "1"), camera.state.value.selected)
            assertEquals(listOf("open:0"), events.filter { it.startsWith("open:") })
        }
    }

    @Test fun rejectedConcurrentSessionAutomaticallyReturnsToFirstCameraPreview() {
        declaresConcurrent = true
        rejectSecondSession = true
        withController { camera, _ ->
            assertTrue(camera.state.value.message, camera.state.value.ready)
            assertTrue(camera.state.value.sequential)
            assertEquals(listOf("0", "1"), camera.state.value.selected)
            assertEquals(listOf("open:0", "open:1", "open:0"), events.filter { it.startsWith("open:") })
            val reopened = events.lastIndexOf("open:0")
            assertTrue(events.indexOf("closed:0") in 0 until reopened)
            assertTrue(events.indexOf("closed:1") in 0 until reopened)
        }
    }

    @Test fun fallbackDoesNotReopenUntilBothDevicesAcknowledgeClosure() {
        declaresConcurrent = true
        rejectSecondSession = true
        deferDeviceClosed = true
        withController { camera, drain ->
            assertFalse(camera.state.value.ready)
            assertEquals(listOf("open:0", "open:1"), events.filter { it.startsWith("open:") })
            assertEquals(2, closeCallbacks.size)
            closeCallbacks.removeAt(0).invoke()
            drain()
            assertEquals(listOf("open:0", "open:1"), events.filter { it.startsWith("open:") })
            closeCallbacks.removeAt(0).invoke()
            drain()
            assertTrue(camera.state.value.message, camera.state.value.ready)
            assertTrue(camera.state.value.sequential)
            assertEquals(listOf("open:0", "open:1", "open:0"), events.filter { it.startsWith("open:") })
        }
    }

    private fun capture(camera: PhoneCameraController, shot: ShotRecord, drain: () -> Unit): Result<List<PhonePhoto>> = runBlocking {
        val result = async(Dispatchers.Default) { runCatching { camera.capture(shot.id) } }
        withTimeout(5_000) {
            while (!result.isCompleted) {
                drain()
                delay(5)
            }
            drain()
            result.await()
        }
    }

    private fun newShot(camera: PhoneCameraController): Pair<SessionStore, ShotRecord> {
        val store = ReflectionHelpers.getField<SessionStore>(camera, "store")
        val shot = ShotRecord(mode = PHONE_ONLY_MODE, triggerElapsedNs = 1,
            lenses = camera.state.value.selected)
        store.add(shot)
        return store to shot
    }

    @Test fun sequentialCaptureSavesBothLensesTogetherAndRestoresFirstPreview() {
        withController { camera, drain ->
            val (store, shot) = newShot(camera)
            val photos = capture(camera, shot, drain).getOrThrow()
            assertEquals(listOf("0", "1"), photos.map { it.lens })
            assertTrue(store.current.shots.single().complete)
            assertEquals(2, photos.map { it.sensorTimestampNs }.toSet().size)
            photos.forEach { photo ->
                assertEquals("phone/${shot.id}/${photo.lens}.jpg", photo.path)
                assertEquals(5L, store.file(photo.path).length())
            }
            assertEquals(listOf("capture:0", "capture:1"), events.filter { it.startsWith("capture:") })
            assertTrue(events.indexOf("closed:0") < events.indexOf("open:1"))
            assertTrue(camera.state.value.message, camera.state.value.ready)
            assertFalse(camera.state.value.capturing)
            assertEquals("open:0", events.last { it.startsWith("open:") })
        }
    }

    @Test fun secondCameraFailureKeepsFirstPhotoAndAllowsNextPair() {
        failSecondCapture = true
        withController { camera, drain ->
            val (store, firstShot) = newShot(camera)
            val failed = capture(camera, firstShot, drain)
            assertTrue(failed.isFailure)
            assertEquals(listOf("0"), store.current.shots.single().phone.map { it.lens })
            assertFalse(store.current.shots.single().complete)
            assertTrue(store.file(store.current.shots.single().phone.single().path).isFile)
            assertTrue(camera.state.value.message, camera.state.value.ready)
            assertFalse(camera.state.value.capturing)

            failSecondCapture = false
            val (_, nextShot) = newShot(camera)
            assertEquals(listOf("0", "1"), capture(camera, nextShot, drain).getOrThrow().map { it.lens })
            assertTrue(store.current.shots.first { it.id == nextShot.id }.complete)
            assertFalse(store.current.shots.first { it.id == firstShot.id }.complete)
        }
    }

    @Test fun concurrentCaptureFailureRetriesOnlyTheMissingLensSequentially() {
        declaresConcurrent = true
        failSecondCaptureOnce = true
        withController { camera, drain ->
            assertFalse(camera.state.value.sequential)
            val (store, shot) = newShot(camera)
            val photos = capture(camera, shot, drain).getOrThrow()
            assertEquals(listOf("0", "1"), photos.map { it.lens })
            assertTrue(store.current.shots.single().complete)
            assertEquals(listOf("capture:0", "capture:1", "capture:1"), events.filter { it.startsWith("capture:") })
            val reopened = events.lastIndexOf("open:1")
            assertTrue(events.indexOf("closed:0") in 0 until reopened)
            assertTrue(events.indexOf("closed:1") in 0 until reopened)
            assertTrue(camera.state.value.message, camera.state.value.ready)
            assertTrue(camera.state.value.sequential)
            assertFalse(camera.state.value.capturing)
        }
    }
}
