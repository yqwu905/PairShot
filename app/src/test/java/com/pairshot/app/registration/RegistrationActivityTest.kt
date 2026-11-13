package com.pairshot.app.registration

import android.app.Application
import android.net.Uri
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RegistrationActivityTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun privateHttpsOriginRejectsExternalUrlsAndEncodedTraversal() {
        assertTrue(isRegistrationResource(Uri.parse("$REGISTRATION_ORIGIN/registration/compute-worker.js")))
        assertTrue(isRegistrationResource(Uri.parse("$REGISTRATION_ORIGIN/registration/runtime/pyodide.asm.wasm")))
        for (url in listOf(
            "https://example.com/registration/index.html", "http://$REGISTRATION_HOST/registration/index.html",
            "file:///data/data/com.pairshot.app/files/sessions/session.json", "content://media/external/images/1",
            "https://$REGISTRATION_HOST@evil.test/registration/index.html", "$REGISTRATION_ORIGIN:444/registration/index.html",
            "$REGISTRATION_ORIGIN/registration/%2e%2e/secrets", "$REGISTRATION_ORIGIN/registration/runtime%2f..%2fsecrets",
            "$REGISTRATION_ORIGIN/registration/%5c..%5csecrets",
        )) assertFalse(url, isRegistrationResource(Uri.parse(url)))
    }

    @Test fun routerServesBundledWorkerAndWasmButNeverFallsBackToNetworkOrPrivatePaths() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        RegistrationInputs(app, temp.newFolder()).use { inputs ->
            val router = RegistrationResources(app, inputs, RegistrationStore(temp.newFolder()))
            val worker = router.open(Uri.parse("$REGISTRATION_ORIGIN/registration/compute-worker.js"))
            assertEquals(200, worker.statusCode)
            assertEquals("text/javascript", worker.mimeType)
            assertTrue(worker.data.bufferedReader().use { it.readText() }.contains("loadPyodide"))
            val wasm = router.open(Uri.parse("$REGISTRATION_ORIGIN/registration/runtime/pyodide.asm.wasm"))
            assertEquals("application/wasm", wasm.mimeType)
            val signature = ByteArray(4)
            wasm.data.use { assertEquals(4, it.read(signature)) }
            assertArrayEquals(byteArrayOf(0, 0x61, 0x73, 0x6d), signature)
            for (path in listOf("https://example.com/runtime/pyodide.mjs", "$REGISTRATION_ORIGIN/sessions/session.json",
                "$REGISTRATION_ORIGIN/inputs/unknown.png", "$REGISTRATION_ORIGIN/results/../metadata.json")) {
                val response = router.open(Uri.parse(path))
                assertEquals(path, 404, response.statusCode)
                response.data.close()
            }
        }
    }

    @Test fun standalonePageLoadsOfflineAndDoesNotExposeFileOrContentAccess() {
        val controller = Robolectric.buildActivity(RegistrationActivity::class.java).setup()
        try {
            val activity = controller.get()
            val browser = ReflectionHelpers.getField<WebView>(activity, "browser")
            assertEquals("$REGISTRATION_ORIGIN/registration/index.html", shadowOf(browser).lastLoadedUrl)
            assertTrue(browser.settings.javaScriptEnabled)
            assertFalse(browser.settings.allowFileAccess)
            assertFalse(browser.settings.allowContentAccess)
            assertFalse(browser.settings.domStorageEnabled)
            val bridge = activity.NativeBridge()
            assertTrue(JSONObject(bridge.listResults()).getBoolean("ok"))
            assertFalse(JSONObject(bridge.beginResult("{}")).getBoolean("ok"))
            assertFalse(JSONObject(bridge.getResult("../../sessions")).getBoolean("ok"))
        } finally { controller.pause().stop().destroy() }
    }
}
