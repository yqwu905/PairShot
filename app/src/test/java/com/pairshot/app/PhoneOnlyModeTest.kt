package com.pairshot.app

import android.app.Application
import android.os.Looper
import android.view.TextureView
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.pairshot.app.data.PHONE_ONLY_MODE
import com.pairshot.app.data.PhonePhoto
import com.pairshot.app.data.SessionStore
import com.pairshot.app.data.safeName
import com.pairshot.app.data.sha256
import com.pairshot.app.phone.PhoneCamera
import com.pairshot.app.phone.PhoneLens
import com.pairshot.app.phone.PhoneState
import com.pairshot.app.ptp.PtpCodec
import com.pairshot.app.ptp.PtpEvent
import com.pairshot.app.ptp.PtpObjectInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneOnlyModeTest {
    private lateinit var model: CameraViewModel
    private lateinit var phone: FakePhoneCamera
    private lateinit var holder: ViewModelStore
    private var usbRequests = 0

    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        File(app.filesDir, "sessions").deleteRecursively()
        model = CameraViewModel(app,
            sessionFactory = { _, _, _ -> usbRequests++; error("仅手机模式不应打开 PTP") },
            permissionRequester = { _, _, _ -> usbRequests++; error("仅手机模式不应请求 USB 授权") },
            phoneFactory = { _, store -> FakePhoneCamera(store).also { phone = it } })
        holder = ViewModelStore().apply { put("phone-only", model) }
        model.onForeground(true)
        model.setMode(SyncMode.PHONE_ONLY)
    }
    @After fun cleanup() {
        holder.clear()
        File(model.getApplication<Application>().filesDir, "sessions").deleteRecursively()
    }

    @Test fun disconnectedPhoneCapturesDifferentZoomsAndExportsWithoutArming() {
        assertNull(model.state.value.info)
        assertFalse(model.state.value.armed)
        model.capture()
        waitUntil { !model.state.value.shooting }
        model.setZoom("0", 2f)
        model.capture()
        waitUntil { !model.state.value.shooting }
        val shots = model.store.current.shots
        assertEquals(2, shots.size)
        assertTrue(shots.all { it.mode == PHONE_ONLY_MODE && it.complete && it.camera.isEmpty() && !it.awaitingCamera })
        assertEquals(listOf(1f, 2f), shots.map { it.phone.single().requestedZoomRatio })
        assertNotEquals(shots[0].phone.single().path, shots[1].phone.single().path)
        model.prepareExport(false)
        waitUntil { !model.state.value.busy }
        val path = checkNotNull(model.state.value.preparedExport) { model.state.value.status }
        ZipFile(path).use { zip ->
            assertFalse(zip.entries().asSequence().any { it.name.startsWith("camera/") })
            val manifest = JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText())
            assertEquals(2, manifest.getJSONArray("shots").length())
        }
        assertEquals(0, usbRequests)
    }
    @Test fun repeatedShutterAndModeChangesCannotOverlapACapture() {
        val gate = CompletableDeferred<Unit>()
        phone.beforeCapture = { gate.await() }
        model.capture()
        waitUntil { phone.captureIds.size == 1 }
        model.capture()
        model.setZoom("0", 3f)
        model.setMode(SyncMode.PHONE_LEADS)
        assertEquals(SyncMode.PHONE_ONLY, model.state.value.mode)
        assertEquals(1f, phone.state.value.zoomRatios["0"])
        assertEquals(1, phone.captureIds.size)
        gate.complete(Unit)
        waitUntil { !model.state.value.shooting }
        assertTrue(model.store.current.shots.single().complete)
    }
    @Test fun captureFailureKeepsAnIncompleteRecordAndCanBeRetried() {
        phone.beforeCapture = { error("测试镜头错误") }
        model.capture()
        waitUntil { !model.state.value.shooting }
        val failed = model.store.current.shots.single()
        assertFalse(failed.complete)
        assertFalse(failed.awaitingCamera)
        assertTrue(failed.errors.single().contains("测试镜头错误"))
        assertFalse(model.state.value.waitingCamera)
        phone.beforeCapture = {}
        model.capture()
        waitUntil { !model.state.value.shooting }
        assertTrue(model.store.current.shots.last().complete)
        assertFalse(model.store.current.shots.first().complete)
        assertEquals(0, usbRequests)
    }
    @Test fun backgroundMissingPermissionAndUnreadyLensCannotCreateShots() {
        model.onForeground(false)
        model.capture()
        model.onForeground(true)
        phone.state.update { it.copy(permission = false) }
        model.capture()
        phone.state.update { it.copy(permission = true, ready = false) }
        model.capture()
        assertTrue(model.store.current.shots.isEmpty())
        assertTrue(phone.captureIds.isEmpty())
    }
    @Test fun phoneOnlyIgnoresRemoteEventsAndNeverArms() = runBlocking {
        model.arm()
        model.onCameraPhoto(PtpObjectInfo(1, 1, 0x3801, 10, 1, "DSC_0001.JPG", "20261105T080000"),
            PtpEvent(PtpCodec.OBJECT_ADDED, 1))
        assertFalse(model.state.value.armed)
        assertTrue(model.store.current.shots.isEmpty())
        assertEquals(0, usbRequests)
    }
    @Test fun historyAndProcessRestartRestorePhoneOnlyMode() {
        model.capture()
        waitUntil { !model.state.value.shooting }
        val saved = model.store.current
        model.newSession()
        waitUntil { !model.state.value.busy }
        model.setMode(SyncMode.PHONE_LEADS)
        model.selectHistory(saved.id)
        waitUntil { !model.state.value.busy }
        assertEquals(SyncMode.PHONE_ONLY, model.state.value.mode)
        assertTrue(model.store.current.shots.single().complete)
        holder.clear()
        val app = ApplicationProvider.getApplicationContext<Application>()
        model = CameraViewModel(app, phoneFactory = { _, store -> FakePhoneCamera(store).also { phone = it } })
        holder = ViewModelStore().apply { put("restored", model) }
        assertEquals(SyncMode.PHONE_ONLY, model.state.value.mode)
        assertTrue(model.store.current.shots.single().complete)
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        fail("等待操作完成超时：${model.state.value.status}")
    }
}

/** Test capture backend: exercises the real journal/export/view model without camera hardware. */
internal class FakePhoneCamera(private val store: SessionStore) : PhoneCamera {
    override val state = MutableStateFlow(PhoneState(permission = true,
        lenses = listOf(PhoneLens("0", "0", null, "后置 · 4.2 mm", 4.2f, false, .5f, 4f, true)),
        selected = listOf("0"), ready = true, zoomRatios = mapOf("0" to 1f)))
    val captureIds = mutableListOf<String>()
    var beforeCapture: suspend () -> Unit = {}
    override fun refresh() = Unit
    override fun toggle(key: String) = Unit
    override fun setZoom(key: String, ratio: Float) { state.update { it.copy(zoomRatios = it.zoomRatios + (key to ratio)) } }
    override fun attach(key: String, view: TextureView) = Unit
    override fun detach(key: String, view: TextureView) = Unit
    override fun setRunning(value: Boolean) = Unit
    override fun close() = Unit
    override suspend fun capture(shotId: String): List<PhonePhoto> {
        captureIds += shotId
        beforeCapture()
        val photos = state.value.selected.map { key ->
            val path = "phone/$shotId/${safeName(key)}.jpg"
            val file = store.file(path).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
            PhonePhoto(key, "后置", path, file.length(), sha256(file), 100, "UNKNOWN", 120, 160,
                nominalFocalLengthMm = 4.2f, requestedZoomRatio = state.value.zoomRatios[key] ?: 1f)
        }
        store.shot(shotId) { it.copy(phone = photos) }
        return photos
    }
}
