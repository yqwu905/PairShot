package com.pairshot.app.phone

import android.Manifest
import android.app.Application
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.test.core.app.ApplicationProvider
import com.pairshot.app.data.SessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowCameraCharacteristics

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [PhoneCaptureModeTest.CharacteristicsShadow::class])
class PhoneCaptureModeTest {
    @get:Rule val temp = TemporaryFolder()

    @Implements(CameraCharacteristics::class)
    class CharacteristicsShadow : ShadowCameraCharacteristics() {
        @Implementation(minSdk = 28)
        protected fun getPhysicalCameraIds(): Set<String> = emptySet()
    }

    private fun lens(cameraId: String, physicalId: String? = null) = PhoneLens(
        key = if (physicalId == null) cameraId else "$cameraId:$physicalId",
        cameraId = cameraId, physicalId = physicalId, label = cameraId,
        focalLength = 4f, front = false,
    )

    @Test fun zeroOrOneLensDoesNotNeedConcurrentCameraSupport() {
        for (sdk in listOf(29, 30, 35)) {
            assertTrue(supportsConcurrentCapture(emptyList(), sdk, emptySet()))
            assertTrue(supportsConcurrentCapture(listOf(lens("0")), sdk, emptySet()))
            assertTrue(supportsConcurrentCapture(listOf(lens("0", "wide")), sdk, emptySet()))
        }
    }

    @Test fun twoPhysicalOutputsOfOneLogicalCameraCanBeTriedOnAndroid10() {
        val pair = listOf(lens("0", "wide"), lens("0", "tele"))
        assertTrue(supportsConcurrentCapture(pair, 29, emptySet()))
        assertTrue(supportsConcurrentCapture(pair.reversed(), 35, emptySet()))
    }

    @Test fun logicalAndPhysicalOutputOfSameCameraUseSequentialCapture() {
        val pair = listOf(lens("0"), lens("0", "wide"))
        assertFalse(supportsConcurrentCapture(pair, 29, emptySet()))
        assertFalse(supportsConcurrentCapture(pair.reversed(), 35, setOf(setOf("0", "1"))))
    }

    @Test fun android10UsesSequentialCaptureForDifferentCameraDevices() {
        val pair = listOf(lens("0"), lens("1"))
        assertFalse(supportsConcurrentCapture(pair, 29, emptySet()))
        assertFalse(supportsConcurrentCapture(pair, 29, setOf(setOf("0", "1"))))
    }

    @Test fun differentCameraDevicesNeedOneDeclarationContainingTheWholePair() {
        val pair = listOf(lens("0"), lens("1"))
        assertFalse(supportsConcurrentCapture(pair, 30, emptySet()))
        assertFalse(supportsConcurrentCapture(pair, 35, setOf(setOf("0", "2"), setOf("1", "2"))))
        assertTrue(supportsConcurrentCapture(pair, 30, setOf(setOf("0", "1"))))
        assertTrue(supportsConcurrentCapture(pair.reversed(), 35, setOf(setOf("2", "1", "0"))))
    }

    @Test fun physicalOutputsAcrossLogicalDevicesStillRequireAConcurrentDeclaration() {
        val pair = listOf(lens("0", "wide"), lens("1", "tele"))
        assertFalse(supportsConcurrentCapture(pair, 29, setOf(setOf("0", "1"))))
        assertFalse(supportsConcurrentCapture(pair, 35, setOf(setOf("wide", "tele"))))
        assertTrue(supportsConcurrentCapture(pair, 35, setOf(setOf("0", "1"))))
    }

    @Test @Config(sdk = [29, 35])
    fun selectingTwoIndependentCamerasWithoutConcurrentSupportPreservesSelectionOrder() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val manager = app.getSystemService(CameraManager::class.java)
        for (id in listOf("0", "1")) {
            val characteristics = ShadowCameraCharacteristics.newCameraCharacteristics()
            shadowOf(characteristics).apply {
                set(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES, intArrayOf())
                set(CameraCharacteristics.LENS_FACING, CameraCharacteristics.LENS_FACING_BACK)
                set(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS, floatArrayOf(4f))
            }
            shadowOf(manager).addCamera(id, characteristics)
        }
        val camera = PhoneCameraController(app, SessionStore(temp.newFolder()))
        try {
            camera.refresh()
            assertEquals(listOf("0"), camera.state.value.selected)
            camera.toggle("1")
            assertEquals(listOf("0", "1"), camera.state.value.selected)
            camera.toggle("0")
            assertEquals(listOf("1"), camera.state.value.selected)
            camera.toggle("0")
            assertEquals(listOf("1", "0"), camera.state.value.selected)
        } finally {
            camera.close()
        }
    }
}
