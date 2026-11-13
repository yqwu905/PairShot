package com.pairshot.app.registration

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Keep
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.pairshot.app.data.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

internal const val REGISTRATION_HOST = "appassets.androidplatform.net"
internal const val REGISTRATION_ORIGIN = "https://$REGISTRATION_HOST"
private const val START_PAGE = "$REGISTRATION_ORIGIN/registration/index.html"
private val RESULT_IMAGES = setOf("homography.png", "geometry.png", "global.png", "aligned.png", "reference.png", "mask.png", "training_mask.png")

/** Every request, including worker/WASM/package loads, stays inside app-owned storage. */
internal fun isRegistrationResource(uri: Uri): Boolean =
    uri.scheme == "https" && uri.host == REGISTRATION_HOST && uri.userInfo == null &&
        (uri.port == -1 || uri.port == 443) && uri.pathSegments.all { segment ->
            segment.isNotBlank() && segment != "." && segment != ".." &&
                segment.matches(Regex("[A-Za-z0-9_.-]+"))
        }

internal class RegistrationResources(
    private val activity: android.content.Context,
    private val inputs: RegistrationInputs,
    private val results: RegistrationStore,
) {
    fun open(uri: Uri): WebResourceResponse {
        if (!isRegistrationResource(uri)) return denied()
        return try {
            val parts = uri.pathSegments
            val (mime, stream) = when {
                parts.firstOrNull() == "registration" && parts.size >= 2 -> {
                    val path = parts.joinToString("/")
                    val mime = when (path.substringAfterLast('.')) {
                        "html" -> "text/html"
                        "js", "mjs" -> "text/javascript"
                        "css" -> "text/css"
                        "wasm" -> "application/wasm"
                        "json" -> "application/json"
                        "svg" -> "image/svg+xml"
                        "txt", "py", "md" -> "text/plain"
                        else -> "application/octet-stream"
                    }
                    mime to activity.assets.open(path)
                }
                parts.size == 2 && parts[0] == "inputs" && parts[1].endsWith(".png") ->
                    "image/png" to (inputs.file(parts[1].removeSuffix(".png")) ?: return denied()).inputStream()
                parts.size == 3 && parts[0] == "results" && parts[2] in RESULT_IMAGES ->
                    "image/png" to results.file(parts[1], parts[2]).inputStream()
                else -> return denied()
            }
            WebResourceResponse(mime, "UTF-8", 200, "OK", mapOf(
                "Cache-Control" to "no-store",
                "X-Content-Type-Options" to "nosniff",
                "Cross-Origin-Resource-Policy" to "same-origin",
            ), stream)
        } catch (_: Exception) { denied() }
    }

    private fun denied() = WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
        mapOf("Cache-Control" to "no-store"), ByteArrayInputStream("Resource unavailable".toByteArray()))
}

/** A separate Activity releases the capture screen's Camera2 sessions through ON_STOP. */
class RegistrationActivity : ComponentActivity() {
    private lateinit var results: RegistrationStore
    private lateinit var inputs: RegistrationInputs
    private lateinit var content: LinearLayout
    private var browser: WebView? = null
    @Volatile private var busy = false
    @Volatile private var hostClosed = false
    @Volatile private var browserGeneration = 0
    private var pageReady = false
    private val pendingScripts = mutableListOf<String>()
    private var importing = false
    private var pickingSlot: String? = null
    private var externalPicker = false
    private var pendingExport: Pair<String, String>? = null
    private val slotTokens = mutableMapOf<String, String>()

    private val selectDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val slot = pickingSlot
        pickingSlot = null
        externalPicker = false
        if (uri != null && slot != null) importImage(slot) {
            val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: "手机图片"
            inputs.importUri(uri, name)
        }
    }
    private val saveDocument = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val target = pendingExport
        pendingExport = null
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null && target != null) exportTo(uri, target.first, target.second)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        results = RegistrationStore(File(filesDir, "registration"))
        inputs = RegistrationInputs(this, File(cacheDir, "registration-inputs/${UUID.randomUUID()}"))
        pickingSlot = savedInstanceState?.getString("pickingSlot")?.takeIf { it in setOf("main", "tele") }
        externalPicker = pickingSlot != null
        val exportId = savedInstanceState?.getString("exportId")
        val exportAsset = savedInstanceState?.getString("exportAsset")
        if (exportId != null && exportAsset != null) pendingExport = exportId to exportAsset
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(11, 16, 17))
        }
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(16), 0)
        }
        toolbar.addView(Button(this).apply {
            text = "‹ 返回"
            contentDescription = "返回拍摄"
            setTextColor(Color.rgb(151, 243, 204))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }, LinearLayout.LayoutParams(dp(86), dp(52)))
        toolbar.addView(TextView(this).apply {
            text = "配准工具"
            textSize = 18f
            setTextColor(Color.rgb(232, 240, 236))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(toolbar)
        setContentView(content)
        onBackPressedDispatcher.addCallback(this) {
            if (busy) AlertDialog.Builder(this@RegistrationActivity)
                .setTitle("取消计算并返回？")
                .setMessage("已保存的结果会保留，本次未完成的计算将取消。")
                .setNegativeButton("继续计算", null)
                .setPositiveButton("返回") { _, _ -> finish() }.show()
            else finish()
        }
        openBrowser()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openBrowser() {
        browserGeneration++
        pageReady = false
        val resources = RegistrationResources(this, inputs, results)
        val view = WebView(this)
        browser = view
        view.setBackgroundColor(Color.rgb(11, 16, 17))
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
        }
        view.addJavascriptInterface(NativeBridge(), "AndroidRegistration")
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = resources.open(request.url)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !isRegistrationResource(request.url) || request.url.path != "/registration/index.html"

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (browser !== view) return true
                browserGeneration++
                pageReady = false
                pendingScripts.clear()
                setComputing(false)
                content.removeView(view)
                view.removeJavascriptInterface("AndroidRegistration")
                view.destroy()
                browser = null
                cleanupResults(results)
                results = RegistrationStore(File(filesDir, "registration"))
                val retry = Button(this@RegistrationActivity).apply {
                    text = "计算页面已停止，点击重新打开\n建议选择 1024 px；也可更新 Android System WebView"
                    setOnClickListener { content.removeView(this); openBrowser() }
                }
                content.addView(retry)
                return true
            }
        }
        content.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        view.loadUrl(START_PAGE)
    }

    private fun importImage(slot: String, load: () -> RegistrationInput) {
        importing = true
        evaluate("window.PairShotNative?.onImporting(${JSONObject.quote(slot)})")
        lifecycleScope.launch {
            try {
                val input = withContext(Dispatchers.IO) { load() }
                if (isFinishing || isDestroyed) return@launch
                slotTokens.put(slot, input.token)?.let(inputs::discard)
                val payload = JSONObject().put("url", "$REGISTRATION_ORIGIN/inputs/${input.token}.png")
                    .put("name", input.name).put("mime", "image/png")
                    .put("width", input.width).put("height", input.height)
                    .put("originalWidth", input.originalWidth).put("originalHeight", input.originalHeight)
                evaluate("window.PairShotNative?.onInput(${JSONObject.quote(slot)},$payload)")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                showError(error.message ?: "无法读取图片，请选择 JPEG、PNG 或手机可解码的图片。")
            } finally { importing = false }
        }
    }

    private fun pickImage(slot: String, source: String) {
        if (busy || importing || pickingSlot != null || slot !in setOf("main", "tele") || source !in setOf("library", "device")) return
        pickingSlot = slot
        if (source == "device") {
            launchDocumentPicker()
            return
        }
        lifecycleScope.launch {
            try {
                val photos = withContext(Dispatchers.IO) {
                    listRegistrationPhotos(SessionStore(File(filesDir, "sessions")))
                }
                if (isFinishing || isDestroyed) return@launch
                if (busy) { pickingSlot = null; return@launch }
                if (photos.isEmpty()) {
                    AlertDialog.Builder(this@RegistrationActivity).setTitle("还没有可用的拍摄照片")
                        .setMessage("先拍摄手机照片或导入相机 JPEG，也可以从手机文件中选择。")
                        .setNegativeButton("取消") { _, _ -> pickingSlot = null }
                        .setPositiveButton("手机文件") { _, _ -> launchDocumentPicker() }
                        .setOnCancelListener { pickingSlot = null }.show()
                } else AlertDialog.Builder(this@RegistrationActivity)
                    .setTitle(if (slot == "main") "选择待配准图" else "选择参考图")
                    .setItems(photos.map { it.label }.toTypedArray()) { _, index ->
                        pickingSlot = null
                        val photo = photos[index]
                        importImage(slot) { inputs.importFile(photo.file, photo.label) }
                    }.setNegativeButton("取消") { _, _ -> pickingSlot = null }
                    .setOnCancelListener { pickingSlot = null }.show()
            } catch (error: Exception) {
                pickingSlot = null
                if (error is CancellationException) throw error
                showError("无法读取拍摄记录：${error.message ?: "请重试"}")
            }
        }
    }

    private fun launchDocumentPicker() {
        externalPicker = true
        try { selectDocument.launch(arrayOf("image/*")) }
        catch (_: Exception) {
            externalPicker = false
            pickingSlot = null
            showError("无法打开系统文件选择器，请检查手机的文件应用。")
        }
    }

    private fun requestExport(id: String, asset: String) {
        if (busy || importing || pendingExport != null || (asset !in RESULT_IMAGES && asset != "zip")) return
        pendingExport = id to asset
        val name = if (asset == "zip") "PairShot-alignment-${id.take(8)}.zip" else "PairShot-${id.take(8)}-$asset"
        try {
            saveDocument.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = if (asset == "zip") "application/zip" else "image/png"
                putExtra(Intent.EXTRA_TITLE, name)
            })
        } catch (_: Exception) { pendingExport = null; showError("无法打开系统保存位置，请检查手机的文件应用。") }
    }

    private fun exportTo(uri: Uri, id: String, asset: String) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val zip = if (asset == "zip") File(cacheDir, "registration-export-${UUID.randomUUID()}.zip") else null
                    try {
                        val file = if (zip != null) { results.archive(id, zip); zip } else results.file(id, asset)
                        checkNotNull(contentResolver.openOutputStream(uri, "w")) { "无法打开保存位置" }.use { output ->
                            file.inputStream().use { it.copyTo(output, 64 * 1024) }
                        }
                    } finally { zip?.delete() }
                }
                android.widget.Toast.makeText(this@RegistrationActivity, "配准结果已导出", android.widget.Toast.LENGTH_SHORT).show()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                showError("导出失败：${error.message ?: "请重新选择保存位置"}")
            }
        }
    }

    private fun evaluate(script: String) {
        if (hostClosed) return
        if (pageReady) browser?.evaluateJavascript(script, null)
        else {
            if (pendingScripts.size >= 16) pendingScripts.removeAt(0)
            pendingScripts += script
        }
    }
    private fun showError(message: String) { evaluate("window.PairShotNative?.onError(${JSONObject.quote(message)})") }
    private fun setComputing(value: Boolean) {
        busy = value
        if (value) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun cleanupResults(storage: RegistrationStore) {
        // Do not wait on an in-flight PNG verification or disk write on the UI thread.
        Thread({ runCatching { storage.abandonAll() } }, "PairShot-Alignment-Cleanup").start()
    }

    @Keep
    inner class NativeBridge(private val generation: Int = browserGeneration) {
        private val storage = results
        private fun alive() = !hostClosed && generation == browserGeneration
        private fun reply(block: (JSONObject) -> Unit): String = try {
            check(alive()) { "配准工具已关闭，请重新打开" }
            JSONObject().put("ok", true).also(block).toString()
        } catch (error: Exception) { JSONObject().put("ok", false).put("error", error.message ?: "本机文件操作失败").toString() }

        @JavascriptInterface fun pageReady() = runOnUiThread {
            if (alive()) {
                pageReady = true
                val queued = pendingScripts.toList()
                pendingScripts.clear()
                queued.forEach(::evaluate)
            }
        }
        @JavascriptInterface fun pickImage(slot: String, source: String) = runOnUiThread { if (alive()) this@RegistrationActivity.pickImage(slot, source) }
        @JavascriptInterface fun setBusy(value: Boolean) = runOnUiThread { if (alive()) setComputing(value) }
        @JavascriptInterface fun beginResult(metadataJson: String): String = reply {
            require(metadataJson.length <= 2 * 1024 * 1024) { "结果信息过大" }
            it.put("id", storage.begin(JSONObject(metadataJson)))
        }
        @JavascriptInterface fun appendResult(id: String, name: String, chunk: String): String = reply { storage.append(id, name, chunk) }
        @JavascriptInterface fun commitResult(id: String): String = reply { it.put("id", storage.commit(id).id) }
        @JavascriptInterface fun abandonResult(id: String) { runCatching { storage.abandon(id) } }
        @JavascriptInterface fun listResults(): String = reply { out ->
            out.put("results", JSONArray(storage.history().map { row ->
                JSONObject().put("id", row.id).put("createdAt", row.createdAt).put("label", row.label)
                    .put("width", row.width).put("height", row.height)
            }))
        }
        @JavascriptInterface fun getResult(id: String): String = reply { out ->
            val row = storage.load(id)
            out.put("id", row.id).put("metadata", row.metadata)
            out.put("images", JSONObject(RESULT_IMAGES.associate { name -> name.removeSuffix(".png") to "$REGISTRATION_ORIGIN/results/${row.id}/$name" }))
        }
        @JavascriptInterface fun exportResult(id: String, asset: String) = runOnUiThread { if (alive()) requestExport(id, asset) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pickingSlot", pickingSlot.takeIf { externalPicker })
        pendingExport?.let { (id, asset) -> outState.putString("exportId", id); outState.putString("exportAsset", asset) }
        super.onSaveInstanceState(outState)
    }
    override fun onPause() { browser?.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); browser?.onResume() }
    override fun onDestroy() {
        hostClosed = true
        browserGeneration++
        pendingScripts.clear()
        browser?.let { view ->
            content.removeView(view)
            view.stopLoading()
            view.removeJavascriptInterface("AndroidRegistration")
            view.destroy()
        }
        browser = null
        inputs.close()
        cleanupResults(results)
        super.onDestroy()
    }
}
