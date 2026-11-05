package com.pairshot.app

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun noCameraCannotArmShootOrExportAndModeExplainsDelayedTrigger() {
        compose.onNodeWithText("PairShot").assertIsDisplayed()
        compose.onNodeWithText("尚未发现相机").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("刷新设备").performScrollTo().performClick()
        compose.onNodeWithTag("sync_arm").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("sync_shutter").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("相机 → 手机").performScrollTo().performClick()
        compose.onNodeWithText("延迟联动：收到照片新增事件或发现新文件后，手机才会拍摄。当前不支持快门按下瞬间触发。").assertIsDisplayed()
        compose.onNodeWithTag("export_zip").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("手机 → 相机").performScrollTo().performClick()
        compose.onNodeWithTag("sync_shutter").performScrollTo().assertIsNotEnabled()
    }

    @Test fun phoneOnlyModeHidesUsbAndRequiresPhonePermission() {
        compose.onNodeWithTag("mode_phone_only").performScrollTo().performClick()
        compose.onNodeWithText("不同焦距，手机直拍。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("usb_connection_status").assertDoesNotExist()
        compose.onNodeWithTag("sync_arm").assertDoesNotExist()
        compose.onNodeWithTag("sync_shutter").assertDoesNotExist()
        compose.onNodeWithTag("phone_shutter").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("打包手机照片 ZIP").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("相机联动").performScrollTo().performClick()
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
            compose.onNodeWithTag("mode_phone_only").performScrollTo().performClick()
            compose.onNodeWithTag("usb_connection_status").assertDoesNotExist()
            compose.onNodeWithTag("zoom_presets_0").performScrollTo()
            compose.onNodeWithText("2.0×").performScrollTo().performClick()
            compose.onNodeWithTag("zoom_value_0").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("变焦 2.0×").assertIsDisplayed()
            compose.onNodeWithTag("phone_shutter").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(5_000) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                model.store.current.shots.any { it.complete && it.phoneOnly } && !model.state.value.shooting
            }
            compose.onNodeWithText("打包手机照片 ZIP").performScrollTo().assertIsEnabled()
            compose.onNodeWithText("缺相机照片").assertDoesNotExist()
            org.junit.Assert.assertEquals(2f, model.store.current.shots.last().phone.single().requestedZoomRatio)
            org.junit.Assert.assertEquals(1, phone.captureIds.size)
        } finally {
            holder.clear()
            java.io.File(compose.activity.filesDir, "sessions").deleteRecursively()
        }
    }
}
