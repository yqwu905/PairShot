package com.pairshot.app

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.pairshot.app.ptp.PtpCodec
import com.pairshot.app.ptp.PtpEvent
import com.pairshot.app.ptp.PtpObjectInfo
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CameraPairingTest {
    private lateinit var model: CameraViewModel
    private lateinit var holder: ViewModelStore
    @Before fun setup() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        File(application.filesDir, "sessions").deleteRecursively()
        model = CameraViewModel(application)
        holder = ViewModelStore().apply { put("pairing", model) }
    }
    @After fun cleanup() { holder.clear() }

    @Test fun rawJpegAndRepeatedEventsCreateOneShotWhileBurstRemainsSeparate() = runBlocking {
        model.setMode(SyncMode.CAMERA_LEADS)
        val raw = photo(10, "DSC_0021.NEF")
        val jpeg = photo(11, "DSC_0021.JPG")
        model.onCameraPhoto(raw, PtpEvent(PtpCodec.OBJECT_ADDED, 10))
        model.onCameraPhoto(jpeg, PtpEvent(PtpCodec.OBJECT_ADDED, 11))
        model.onCameraPhoto(raw, PtpEvent(PtpCodec.OBJECT_ADDED, 10))
        assertEquals(1, model.store.current.shots.size)
        assertEquals(2, model.store.current.shots.single().camera.size)
        assertTrue(model.store.current.shots.single().phone.isEmpty())
        assertFalse(model.store.current.shots.single().complete)
        assertTrue(model.store.current.shots.single().errors.isNotEmpty())
        model.onCameraPhoto(photo(12, "DSC_0022.NEF"), PtpEvent(PtpCodec.OBJECT_ADDED, 12))
        assertEquals(2, model.store.current.shots.size)
    }
    @Test fun phoneLedModeCannotCreateFeedbackShotsFromUnsolicitedCameraFiles() = runBlocking {
        model.setMode(SyncMode.PHONE_LEADS)
        model.onCameraPhoto(photo(10, "DSC_0021.NEF"), PtpEvent(PtpCodec.OBJECT_ADDED, 10))
        assertTrue(model.store.current.shots.isEmpty())
    }
    @Test fun identicalFilenamesInDifferentFoldersOrCaptureTimesAreDifferentShots() = runBlocking {
        model.setMode(SyncMode.CAMERA_LEADS)
        val raw = photo(10, "DSC_0021.NEF")
        model.onCameraPhoto(raw, PtpEvent(PtpCodec.OBJECT_ADDED, 10))
        model.onCameraPhoto(raw.copy(handle = 11, parent = 9), PtpEvent(PtpCodec.OBJECT_ADDED, 11))
        model.onCameraPhoto(raw.copy(handle = 12, captureDate = "20261103T120105"), PtpEvent(PtpCodec.OBJECT_ADDED, 12))
        assertEquals(3, model.store.current.shots.size)
    }
    private fun photo(handle: Long, name: String) = PtpObjectInfo(handle, 1, 0x3801, 100, 2, name, "20261103T120102")
}
