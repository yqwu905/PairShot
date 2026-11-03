package com.pairshot.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
}
