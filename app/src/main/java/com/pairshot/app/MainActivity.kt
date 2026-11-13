package com.pairshot.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.provider.Settings
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pairshot.app.phone.PhoneCamera
import com.pairshot.app.phone.PhoneLens
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Mint = Color(0xFF97F3CC)
private val Background = Color(0xFF0B1011)
private val Panel = Color(0xFF161E20)
private val Muted = Color(0xFF9BADA9)
private val Amber = Color(0xFFFFC789)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Mint, onPrimary = Background, secondary = Amber,
                background = Background, surface = Panel, surfaceVariant = Color(0xFF22302E), onSurface = Color(0xFFE8F0EC))) {
                CameraScreen()
            }
        }
    }
}

@Composable
internal fun CameraScreen(model: CameraViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val phone by model.phone.state.collectAsStateWithLifecycle()
    val record by model.store.session.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var showDevices by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var allowIncomplete by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.phone.refresh() }
    var pickerExportPath by rememberSaveable { mutableStateOf<String?>(null) }
    val saveZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        model.savePrepared(uri, pickerExportPath)
        pickerExportPath = null
    }
    LaunchedEffect(state.preparedExport) {
        state.preparedExport?.let { path ->
            if (pickerExportPath != path) { pickerExportPath = path; saveZip.launch(File(path).name) }
        }
    }
    DisposableEffect(context, model, lifecycle) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                when (intent.action) {
                    model.permissionAction -> model.onPermissionResult(device)
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> model.onDetached(device)
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> model.refresh()
                }
            }
        }
        val filter = IntentFilter(model.permissionAction).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED); addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { model.phone.refresh(); model.onForeground(true) }
                Lifecycle.Event.ON_RESUME -> model.recheckUsbPermission()
                Lifecycle.Event.ON_STOP -> model.onForeground(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { model.phone.refresh(); model.onForeground(true) }
        model.refresh()
        onDispose { lifecycle.removeObserver(observer); context.unregisterReceiver(receiver); model.onForeground(false) }
    }
    val phoneOnly = state.mode == SyncMode.PHONE_ONLY
    val archiveUsesCamera = record.shots.any { !it.phoneOnly } || (record.shots.isEmpty() && !phoneOnly)
    val locked = state.busy || state.shooting || phone.capturing || state.waitingCamera || state.permissionDeviceId != null
    Scaffold(containerColor = Background, topBar = {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("PairShot", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
                CompactChoice("仅手机", phoneOnly, !locked, Modifier.testTag("mode_phone_only")) { model.setMode(SyncMode.PHONE_ONLY) }
                CompactChoice("相机联动", !phoneOnly, !locked) { model.setMode(SyncMode.PHONE_LEADS) }
                CompactChoice("配准工具", false, !locked, Modifier.testTag("registration_tool")) {
                    context.startActivity(Intent(context, com.pairshot.app.registration.RegistrationActivity::class.java))
                }
            }
        }
    }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val wide = maxWidth > maxHeight
            val lensCount = phone.lenses.count { it.key in phone.selected }.coerceAtLeast(1)
            val previewAspect = if (lensCount == 1) 4f / 3f else 3f / 4f
            val portraitViewfinderHeight = ((maxWidth - 40.dp - 6.dp * (lensCount - 1)) / lensCount) / previewAspect
            // Measure the capture card against the viewport, not the scrolling content.
            // Extra lenses and archive records must never push the shutter away from the preview.
            val captureHeight = (maxHeight - 16.dp).coerceAtLeast(180.dp)
            // Keep camera surfaces composed while browsing records; lazy disposal would
            // release an offscreen preview and interrupt the Camera2 session.
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("camera_list")
                .padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Card(Modifier.fillMaxWidth().heightIn(max = captureHeight).testTag("capture_panel"),
                    shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
                    Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val lensChoices: @Composable (Modifier) -> Unit = { modifier ->
                            Row(modifier.horizontalScroll(rememberScrollState()).testTag("lens_choices"),
                                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                phone.lenses.forEach { lens ->
                                    CompactChoice(lensChoiceLabel(lens), lens.key in phone.selected, !locked,
                                        Modifier.testTag("lens_${lens.key}").semantics { contentDescription = lens.label }) {
                                        model.toggleLens(lens.key)
                                    }
                                }
                            }
                        }
                        val syncControls: @Composable (Modifier) -> Unit = { modifier ->
                            Row(modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                CompactChoice("手机 → 相机", state.mode == SyncMode.PHONE_LEADS, !locked) { model.setMode(SyncMode.PHONE_LEADS) }
                                CompactChoice("相机 → 手机", state.mode == SyncMode.CAMERA_LEADS, !locked) { model.setMode(SyncMode.CAMERA_LEADS) }
                                OutlinedButton(onClick = { if (state.armed) model.stopSync() else model.arm() },
                                    enabled = !locked && (state.armed || (state.info?.supportsFiles == true && phone.ready &&
                                        (state.mode == SyncMode.CAMERA_LEADS || state.info?.supportsCapture == true))),
                                    contentPadding = PaddingValues(horizontal = 10.dp), modifier = Modifier.testTag("sync_arm")) {
                                    Text(if (state.armed) "停止联动" else "开启联动", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                }
                            }
                        }
                        if (wide && !phoneOnly) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                lensChoices(Modifier.weight(1f))
                                syncControls(Modifier.widthIn(max = 320.dp))
                            }
                        } else {
                            if (phone.permission && phone.lenses.isNotEmpty()) lensChoices(Modifier.fillMaxWidth())
                            if (!phoneOnly) syncControls(Modifier.fillMaxWidth())
                        }
                        val viewfinder: @Composable (Modifier) -> Unit = { modifier ->
                            Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black).testTag("viewfinder"), contentAlignment = Alignment.Center) {
                                if (!phone.permission) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("授权手机摄像头") }
                                        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) }) {
                                            Text("系统权限设置")
                                        }
                                    }
                                } else {
                                    val selected = phone.selected.mapNotNull { key -> phone.lenses.firstOrNull { it.key == key } }
                                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        selected.forEach { lens ->
                                            key(lens.key) {
                                                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                                    val aspect = if (selected.size == 1) 4f / 3f else 3f / 4f
                                                    val previewWidth = minOf(maxWidth, maxHeight * aspect)
                                                    CameraPreview(lens, model.phone, Modifier.size(previewWidth, previewWidth / aspect),
                                                        phone.zoomRatios[lens.key] ?: 1f, !locked && phone.ready) { model.setZoom(lens.key, it) }
                                                }
                                            }
                                        }
                                    }
                                    if (!phone.ready || selected.isEmpty()) Text(
                                        if (selected.isEmpty()) "请选择镜头" else phone.message,
                                        color = Amber, style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.background(Color.Black.copy(alpha = .75f), RoundedCornerShape(8.dp)).padding(10.dp))
                                }
                            }
                        }
                        val captureActions: @Composable (Modifier) -> Unit = { modifier ->
                            Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(onClick = model::capture,
                                    enabled = !locked && phone.permission && phone.ready && phone.selected.isNotEmpty() &&
                                        (phoneOnly || (state.armed && state.mode == SyncMode.PHONE_LEADS)),
                                    modifier = Modifier.fillMaxWidth().height(52.dp).testTag(if (phoneOnly) "phone_shutter" else "sync_shutter"),
                                    shape = RoundedCornerShape(14.dp)) {
                                    if (state.shooting) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Background)
                                    else Text(when {
                                        phoneOnly -> "手机拍照"
                                        state.mode == SyncMode.CAMERA_LEADS && state.armed -> "等待相机快门…"
                                        state.waitingCamera -> "等待相机原片…"
                                        else -> "同步拍照"
                                    }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                                }
                                Text(state.status, color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("status"))
                                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                                if (state.progress.isNotEmpty()) Text(state.progress, color = Mint, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (wide) {
                            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                viewfinder(Modifier.weight(1f).fillMaxHeight())
                                captureActions(Modifier.width(160.dp))
                            }
                        } else {
                            viewfinder(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = portraitViewfinderHeight))
                            captureActions(Modifier.fillMaxWidth())
                        }
                    }
                }
                if (!phoneOnly) StudioCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Eyebrow("USB 相机")
                            Text(state.info?.model ?: "未连接", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 6.dp))
                        }
                        TextButton(onClick = { showDevices = !showDevices }) { Text(if (showDevices) "收起" else "设备") }
                    }
                    if (state.info == null || showDevices) {
                        if (state.devices.isEmpty()) Text("尚未发现相机", color = Amber)
                        state.devices.forEach { device ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(device.productName ?: "USB 相机", style = MaterialTheme.typography.titleSmall)
                                    Text("%04X:%04X".format(device.vendorId, device.productId), color = Muted, style = MaterialTheme.typography.labelSmall)
                                }
                                if (state.connectedDeviceId == device.deviceId) OutlinedButton(onClick = model::disconnect, enabled = !locked) { Text("断开连接") }
                                else Button(onClick = { model.requestConnection(device) }, enabled = !locked && state.connectedDeviceId == null) { Text("连接并授权") }
                            }
                        }
                        OutlinedButton(onClick = model::refresh, enabled = !locked) { Text("刷新设备") }
                    }
                    Text(state.connectionMessage, color = if (state.info != null) Mint else Amber,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("usb_connection_status"))
                    if (state.permissionDeviceId != null || (state.busy && state.info == null)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    if (state.permissionDeviceId != null) TextButton(onClick = model::cancelPermissionRequest) { Text("停止等待授权") }
                    if (state.info?.supportsCapture == false) Text("不支持手机触发快门", color = Amber, style = MaterialTheme.typography.bodySmall)
                }
                StudioCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("本次拍摄记录", style = MaterialTheme.typography.titleMedium) }
                        Text("${record.shots.size} 组", color = Mint, style = MaterialTheme.typography.headlineSmall)
                    }
                    Text("${date(record.createdMs)} · ${record.id.take(8)}", color = Muted, style = MaterialTheme.typography.labelSmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Stat("手机照片", record.shots.sumOf { it.phone.size }.toString())
                        if (archiveUsesCamera) {
                            Stat("相机原片", record.shots.sumOf { it.camera.size }.toString())
                            Stat("已导入", record.shots.sumOf { row -> row.camera.count { it.path.isNotBlank() } }.toString())
                        } else Stat("已完成", record.shots.count { it.complete }.toString())
                    }
                    record.shots.takeLast(8).reversed().forEach { shot ->
                        HorizontalDivider(color = Color(0xFF2A3734))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${time(shot.wallTimeMs)} · ${if (shot.phoneOnly) "仅手机" else if (shot.mode == "PHONE_LEADS") "手机触发" else "相机跟拍"}", style = MaterialTheme.typography.titleSmall)
                                Text(if (shot.phoneOnly) "手机 ${shot.phone.size}/${shot.lenses.size} · ${shot.phone.joinToString { zoomLabel(it.requestedZoomRatio) }}" else "手机 ${shot.phone.size}/${shot.lenses.size} · 原片 ${shot.camera.size} · 已导入 ${shot.camera.count { it.path.isNotBlank() }}", color = Muted, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(when { shot.awaitingCamera -> "配对中"; shot.complete -> "可归档"; shot.errors.isNotEmpty() -> "有缺失"; shot.phoneOnly -> "缺手机照片"; shot.camera.isEmpty() -> "缺相机照片"; else -> "待导入" }, color = if (shot.complete) Mint else Amber, style = MaterialTheme.typography.labelMedium)
                        }
                        (shot.errors + shot.camera.mapNotNull { it.error.takeIf(String::isNotBlank) }).distinct().forEach { Text(it, color = Amber, style = MaterialTheme.typography.bodySmall) }
                    }
                    if (archiveUsesCamera) OutlinedButton(onClick = model::importPhotos, enabled = !locked && record.shots.isNotEmpty() && state.info != null, modifier = Modifier.fillMaxWidth()) { Text("复制本次相机原片到手机") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = allowIncomplete, onCheckedChange = { allowIncomplete = it }, enabled = !locked)
                        Text("允许导出不完整记录", style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                    Button(onClick = { model.prepareExport(allowIncomplete) }, enabled = !locked && record.shots.isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag("export_zip")) { Text(if (archiveUsesCamera) "导入原片并打包 ZIP" else "打包手机照片 ZIP") }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(onClick = model::newSession, enabled = !locked) { Text("新建记录") }
                        TextButton(onClick = { model.loadHistory(); showHistory = true }, enabled = !locked) { Text("历史记录") }
                    }
                }
                TextButton(onClick = { showLogs = !showLogs }) { Text(if (showLogs) "收起连接日志" else "查看连接日志") }
                if (showLogs) {
                    OutlinedButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("PairShot 诊断日志", model.diagnostics()))
                    }) { Text("复制诊断日志") }
                    state.logs.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = Muted) }
                }
            }
        }
    }
    if (showHistory) AlertDialog(onDismissRequest = { showHistory = false }, title = { Text("历史拍摄记录") }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.history.isEmpty()) Text("暂无已保存记录")
            state.history.forEach { history -> TextButton(onClick = { model.selectHistory(history.id); showHistory = false }) { Text("${date(history.createdMs)} · ${history.shots.size} 组 · ${history.id.take(6)}") } }
        }
    }, confirmButton = { TextButton(onClick = { showHistory = false }) { Text("关闭") } })
}

@Composable
private fun CameraPreview(lens: PhoneLens, controller: PhoneCamera, modifier: Modifier, ratio: Float, zoomEnabled: Boolean, onZoom: (Float) -> Unit) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black).testTag("preview_${lens.key}")) {
        AndroidView(factory = { context -> TextureView(context).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { controller.attach(lens.key, this@apply) }
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) { controller.attach(lens.key, this@apply) }
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { controller.detach(lens.key, this@apply); return true }
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            }
        } }, onRelease = { controller.detach(lens.key, it) }, modifier = Modifier.fillMaxSize())
        Text(if (lens.physicalId != null) "P ${lens.physicalId}" else "CAM ${lens.cameraId}", color = Color.White,
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(Color.Black.copy(alpha = .5f), RoundedCornerShape(6.dp)).padding(4.dp))
        LensZoomControls(lens, ratio, zoomEnabled, onZoom)
    }
}

@Composable
private fun BoxScope.LensZoomControls(lens: PhoneLens, ratio: Float, enabled: Boolean, onZoom: (Float) -> Unit) {
    if (lens.maxZoom <= lens.minZoom) return
    var showSlider by remember(lens.key) { mutableStateOf(false) }
    TextButton(onClick = { showSlider = true }, enabled = enabled,
        contentPadding = PaddingValues(horizontal = 8.dp),
        modifier = Modifier.align(Alignment.TopEnd).testTag("zoom_value_${lens.key}")
            .semantics { contentDescription = "调节${lens.label}变焦" }) {
        Text(zoomLabel(ratio), color = Mint, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.background(Color.Black.copy(alpha = .65f), RoundedCornerShape(6.dp)).padding(4.dp))
    }
    Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = .6f))
        .horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp).testTag("zoom_presets_${lens.key}"),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        listOf(.5f, 1f, 2f, 3f, 5f).filter { it in lens.minZoom..lens.maxZoom }.forEach { preset ->
            CompactChoice(zoomLabel(preset), kotlin.math.abs(ratio - preset) < .01f, enabled) { onZoom(preset) }
        }
    }
    if (showSlider) AlertDialog(onDismissRequest = { showSlider = false },
        title = { Text("变焦 ${zoomLabel(ratio)}") },
        text = {
            Column {
                Slider(value = ratio.coerceIn(lens.minZoom, lens.maxZoom), onValueChange = onZoom, enabled = enabled,
                    valueRange = lens.minZoom..lens.maxZoom, modifier = Modifier.testTag("zoom_slider_${lens.key}"))
                Text("${zoomLabel(lens.minZoom)} – ${zoomLabel(lens.maxZoom)}", color = Muted, style = MaterialTheme.typography.labelSmall)
            }
        }, confirmButton = { TextButton(onClick = { showSlider = false }) { Text("完成") } })
}

@Composable private fun StudioCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
@Composable private fun Eyebrow(text: String) { Text(text, color = Mint, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold) }
@Composable private fun CompactChoice(title: String, selected: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // The chip is visually small while Material keeps a 48 dp touch target.
    FilterChip(selected = selected, enabled = enabled, onClick = onClick,
        modifier = modifier, shape = RoundedCornerShape(10.dp),
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFF1C342B), selectedLabelColor = Mint),
        label = { Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) })
}
@Composable private fun Stat(title: String, value: String) { Column { Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium); Text(title, color = Muted, style = MaterialTheme.typography.labelSmall) } }
private fun lensChoiceLabel(lens: PhoneLens): String =
    "${if (lens.front) "前置" else "后置"} ${String.format(Locale.ROOT, "%.1f", lens.focalLength)}mm · ${lens.physicalId?.let { "P$it" } ?: lens.cameraId}"
private fun date(ms: Long) = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
private fun time(ms: Long) = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))
private fun zoomLabel(ratio: Float) = String.format(Locale.ROOT, "%.1f×", ratio)
