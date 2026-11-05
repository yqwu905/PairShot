package com.pairshot.app.phone

import android.Manifest
import android.app.Application
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Range
import androidx.test.core.app.ApplicationProvider
import com.pairshot.app.data.SessionStore
import org.junit.Assert.*
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
@Config(sdk = [35], shadows = [PhoneZoomTest.CharacteristicsShadow::class])
class PhoneZoomTest {
    @get:Rule val temp = TemporaryFolder()

    // Robolectric's characteristics factory has no native metadata backing on
    // API 35. These fixtures describe a single camera with no physical outputs.
    @Implements(CameraCharacteristics::class)
    class CharacteristicsShadow : ShadowCameraCharacteristics() {
        @Implementation(minSdk = 28)
        protected fun getPhysicalCameraIds(): Set<String> = emptySet()
    }

    @Test fun cropZoomPreservesSensorOffsetAndNativeView() {
        val active = Rect(100, 200, 4100, 3200)
        assertEquals(active, zoomCropRegion(active, 1f))
        assertEquals(Rect(1100, 950, 3100, 2450), zoomCropRegion(active, 2f))
        val max = zoomCropRegion(active, 100_000f)
        assertEquals(1, max.width())
        assertEquals(1, max.height())
        assertTrue(active.contains(max))
        assertThrows(IllegalArgumentException::class.java) { zoomCropRegion(active, .5f) }
        assertThrows(IllegalArgumentException::class.java) { zoomCropRegion(active, Float.NaN) }
    }
    @Test fun android30ZoomRangeClampsUnsupportedRatiosAndRejectsNonFiniteValues() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val manager = app.getSystemService(CameraManager::class.java)
        val chars = ShadowCameraCharacteristics.newCameraCharacteristics()
        shadowOf(chars).apply {
            set(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES, intArrayOf())
            set(CameraCharacteristics.LENS_FACING, CameraCharacteristics.LENS_FACING_BACK)
            set(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS, floatArrayOf(4.2f))
            set(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE, Range(.5f, 4f))
        }
        shadowOf(manager).addCamera("0", chars)
        val camera = PhoneCameraController(app, SessionStore(temp.newFolder()))
        try {
            camera.refresh()
            assertFalse(camera.state.value.message, camera.state.value.lenses.isEmpty())
            val lens = camera.state.value.lenses.single()
            assertEquals(.5f, lens.minZoom)
            assertEquals(4f, lens.maxZoom)
            assertTrue(lens.zoomRatioControl)
            camera.setZoom("0", 20f)
            assertEquals(4f, camera.state.value.zoomRatios["0"])
            camera.setZoom("0", .2f)
            assertEquals(.5f, camera.state.value.zoomRatios["0"])
            camera.setZoom("0", Float.NaN)
            camera.setZoom("0", Float.POSITIVE_INFINITY)
            assertEquals(.5f, camera.state.value.zoomRatios["0"])
            camera.setZoom("unknown", 2f)
            assertFalse(camera.state.value.zoomRatios.containsKey("unknown"))
        } finally { camera.close() }
    }
    @Test @Config(sdk = [29]) fun android10UsesDeclaredDigitalZoomWithoutInventingUltraWide() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val manager = app.getSystemService(CameraManager::class.java)
        val chars = ShadowCameraCharacteristics.newCameraCharacteristics()
        shadowOf(chars).apply {
            set(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES, intArrayOf())
            set(CameraCharacteristics.LENS_FACING, CameraCharacteristics.LENS_FACING_BACK)
            set(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM, 3f)
        }
        shadowOf(manager).addCamera("0", chars)
        val camera = PhoneCameraController(app, SessionStore(temp.newFolder()))
        try {
            camera.refresh()
            val lens = camera.state.value.lenses.single()
            assertEquals(1f, lens.minZoom)
            assertEquals(3f, lens.maxZoom)
            assertFalse(lens.zoomRatioControl)
            camera.setZoom("0", .5f)
            assertEquals(1f, camera.state.value.zoomRatios["0"])
        } finally { camera.close() }
    }
}
