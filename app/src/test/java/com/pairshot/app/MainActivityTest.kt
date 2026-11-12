package com.pairshot.app

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun noCameraCannotArmShootOrExportAndCanSwitchSyncDirection() {
        compose.onNodeWithText("PairShot").assertIsDisplayed()
        compose.onNodeWithText("尚未发现相机").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("刷新设备").performScrollTo().performClick()
        compose.onNodeWithTag("sync_arm").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("sync_shutter").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("capture_panel").performScrollTo()
        compose.onNodeWithText("相机 → 手机").assertIsDisplayed().performClick()
        compose.onNodeWithText("相机 → 手机").assertIsSelected()
        compose.onNodeWithTag("sync_arm").assertIsNotEnabled()
        compose.onNodeWithTag("export_zip").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("capture_panel").performScrollTo()
        compose.onNodeWithText("手机 → 相机").assertIsDisplayed().performClick()
        compose.onNodeWithTag("sync_shutter").performScrollTo().assertIsNotEnabled()
    }

    @Test fun phoneOnlyModeHidesUsbAndRequiresPhonePermission() {
        compose.onNodeWithTag("mode_phone_only").performClick().assertIsSelected()
        compose.onNodeWithTag("usb_connection_status").assertDoesNotExist()
        compose.onNodeWithTag("sync_arm").assertDoesNotExist()
        compose.onNodeWithTag("sync_shutter").assertDoesNotExist()
        compose.onNodeWithTag("phone_shutter").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("打包手机照片 ZIP").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("相机联动").performClick()
        compose.onNodeWithTag("usb_connection_status").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("sync_arm").performScrollTo().assertIsNotEnabled()
    }

    @Test fun readyPhoneCanZoomShootAndArchiveWithNoUsbCamera() {
        lateinit var phone: FakePhoneCamera
        val model = CameraViewModel(compose.activity.application,
            phoneFactory = { _, store -> FakePhoneCamera(store).also { phone = it } })
        val holder = ViewModelStore().apply { put("ui-phone-only", model) }
        try {
            compose.runOnUiThread { compose.activity.setContent { MaterialTheme { CameraScreen(model) } } }
            compose.onNodeWithTag("mode_phone_only").performClick()
            compose.onNodeWithTag("usb_connection_status").assertDoesNotExist()
            compose.onNodeWithTag("zoom_presets_0").performScrollTo()
            compose.onNodeWithText("2.0×").performScrollTo().performClick()
            compose.onNodeWithTag("zoom_value_0").assertTextEquals("2.0×").performClick()
            compose.onNodeWithTag("zoom_slider_0").assertIsDisplayed().performSemanticsAction(SemanticsActions.SetProgress) { it(2.5f) }
            compose.onNodeWithText("完成").performClick()
            compose.onNodeWithTag("zoom_value_0").assertTextEquals("2.5×")
            compose.onNodeWithTag("phone_shutter").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(5_000) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                model.store.current.shots.any { it.complete && it.phoneOnly } && !model.state.value.shooting
            }
            compose.onNodeWithText("打包手机照片 ZIP").performScrollTo().assertIsEnabled()
            compose.onNodeWithText("缺相机照片").assertDoesNotExist()
            org.junit.Assert.assertEquals(2.5f, model.store.current.shots.last().phone.single().requestedZoomRatio)
            org.junit.Assert.assertEquals(1, phone.captureIds.size)
        } finally {
            holder.clear()
            java.io.File(compose.activity.filesDir, "sessions").deleteRecursively()
        }
    }

    @Test @Config(qualifiers = "w320dp-h568dp-port-mdpi")
    fun smallPortraitKeepsPreviewAndShutterVisibleWithManyLenses() {
        checkCaptureLayout(dual = false, landscape = false)
    }

    @Test @Config(qualifiers = "w640dp-h360dp-land-mdpi")
    fun landscapeKeepsBothPreviewsBesideShutterInBothModes() {
        checkCaptureLayout(dual = true, landscape = true)
    }

    private fun checkCaptureLayout(dual: Boolean, landscape: Boolean) {
        val model = CameraViewModel(compose.activity.application,
            phoneFactory = { _, store -> FakePhoneCamera(store).apply {
                val lens = state.value.lenses.single()
                state.value = state.value.copy(
                    lenses = List(12) { lens.copy(key = "$it", cameraId = "$it", label = "后置镜头 $it") },
                    selected = if (dual) listOf("0", "1") else listOf("0"))
            } })
        val holder = ViewModelStore().apply { put("compact-layout", model) }
        try {
            compose.runOnUiThread { compose.activity.setContent { MaterialTheme { CameraScreen(model) } } }
            listOf(false, true).forEach { phoneOnly ->
                if (phoneOnly) compose.onNodeWithTag("mode_phone_only").performClick()
                val shutter = compose.onNodeWithTag(if (phoneOnly) "phone_shutter" else "sync_shutter").assertIsDisplayed()
                compose.onNodeWithTag("preview_0").assertIsDisplayed()
                if (dual) compose.onNodeWithTag("preview_1").assertIsDisplayed()
                val viewport = compose.onNodeWithTag("camera_list").getUnclippedBoundsInRoot()
                val picture = compose.onNodeWithTag("viewfinder").getUnclippedBoundsInRoot()
                val button = shutter.getUnclippedBoundsInRoot()
                assertTrue("The full viewfinder must fit without scrolling", picture.top >= viewport.top && picture.bottom <= viewport.bottom)
                assertTrue("The full shutter must fit without scrolling", button.top >= viewport.top && button.bottom <= viewport.bottom)
                assertTrue("The viewfinder must remain usable", picture.right - picture.left >= 120.dp && picture.bottom - picture.top >= 100.dp)
                if (landscape) {
                    assertTrue("The shutter must stay beside the viewfinder", button.left >= picture.right && button.left - picture.right <= 8.dp)
                } else {
                    assertTrue("The shutter must stay directly below the viewfinder", button.top >= picture.bottom && button.top - picture.bottom <= 8.dp)
                }
                // Browsing a long lens list must not scroll the preview or shutter out of view.
                compose.onNodeWithTag("lens_11").performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("preview_0").assertIsDisplayed()
                shutter.assertIsDisplayed()
            }
        } finally {
            holder.clear()
            java.io.File(compose.activity.filesDir, "sessions").deleteRecursively()
        }
    }
}
