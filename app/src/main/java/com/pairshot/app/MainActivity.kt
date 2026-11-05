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
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
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
import com.pairshot.app.ptp.CaptureMode
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
    Scaffold(containerColor = Background) { padding ->
        // Keep camera surfaces composed while scrolling; LazyColumn would release
        // an offscreen preview and interrupt a live Camera2 session.
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).testTag("camera_list")
            .padding(horizontal = 20.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Mint), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(19.dp).border(2.dp, Background, CircleShape), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(7.dp).background(Background, CircleShape))
                    }
                }
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Text("PairShot", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(if (phoneOnly) "PHONE CAPTURE / MULTI FOCAL" else "DUAL CAPTURE / USB", style = MaterialTheme.typography.labelSmall, color = Muted)
                }
                StatePill(if (state.shooting) "拍摄中" else if (phoneOnly && phone.ready) "手机就绪" else if (state.armed) "联动中" else "待机", state.armed || (phoneOnly && phone.ready))
            }
            Column {
                Text(if (phoneOnly) "不同焦距，手机直拍。" else "双端拍摄，一次归档。", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(if (phoneOnly) "手机镜头与变焦 · 无需外接相机" else "手机多摄 × 尼康 USB 相机", color = Muted, modifier = Modifier.padding(top = 6.dp))
            }
            StudioCard {
                Eyebrow("01 / 拍摄模式")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModeCard("仅手机", "不同焦距，直接拍摄", phoneOnly, !locked, Modifier.weight(1f).testTag("mode_phone_only")) { model.setMode(SyncMode.PHONE_ONLY) }
                    ModeCard("相机联动", "USB 双端拍摄", !phoneOnly, !locked, Modifier.weight(1f)) { model.setMode(SyncMode.PHONE_LEADS) }
                }
            }
            if (!phoneOnly) StudioCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Eyebrow("02 / USB 相机")
                        Text(state.info?.model ?: "连接你的相机", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 6.dp))
                        Text(state.info?.let { if (it.supportsNikonEvents) "Nikon PTP · 事件查询" else "PTP · 文件轮询" } ?: "使用支持数据传输的 USB / OTG 线", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { showDevices = !showDevices }) { Text(if (showDevices) "收起" else "设备") }
                }
                if (state.info == null || showDevices) {
                    Text("开启相机并切换到照片模式，插入可写存储卡。手机需要支持 USB Host / OTG。", color = Muted, style = MaterialTheme.typography.bodySmall)
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
                if (state.info?.captureMode == CaptureMode.NIKON_Z6) Text("Z6 适配：使用当前焦点，保存到存储卡；请先在相机端完成对焦。", color = Amber, style = MaterialTheme.typography.bodySmall)
                if (state.info?.supportsCapture == false) Text("该型号暂未适配手机触发快门；可检测相机带动手机模式。", color = Amber, style = MaterialTheme.typography.bodySmall)
            }
            StudioCard {
                Eyebrow(if (phoneOnly) "02 / 手机镜头与焦距" else "03 / 手机镜头与焦距")
                if (!phone.permission) {
                    Box(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(16.dp)).background(Background), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("让手机加入拍摄", style = MaterialTheme.typography.titleMedium)
                            Text("授权后显示系统开放的前后置与物理镜头", color = Muted, style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("授权手机摄像头") }
                        }
                    }
                    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) }) { Text("系统权限设置") }
                } else {
                    val selected = phone.lenses.filter { it.key in phone.selected }
                    if (selected.isEmpty()) Text("请选择至少一枚手机镜头", color = Amber)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        selected.forEach { lens ->
                            Column(Modifier.weight(1f)) {
                                key(lens.key) { CameraPreview(lens, model.phone, Modifier.fillMaxWidth().aspectRatio(if (selected.size == 1) 4f / 3f else 3f / 4f)) }
                                Text(lens.label, color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
                                LensZoomControls(lens, phone.zoomRatios[lens.key] ?: 1f, !locked && phone.ready) { model.setZoom(lens.key, it) }
                            }
                        }
                    }
                    phone.lenses.forEach { lens ->
                        FilterChip(selected = lens.key in phone.selected, enabled = !locked,
                            onClick = { model.toggleLens(lens.key) }, label = { Text(lens.label) }, modifier = Modifier.fillMaxWidth())
                    }
                    Text(phone.message, color = if (phone.ready) Mint else Amber, style = MaterialTheme.typography.bodySmall)
                    Text("最多双镜头；只有系统声明支持且成功建立会话的组合才会启用。部分厂商会限制第三方访问长焦或超广角。", color = Muted, style = MaterialTheme.typography.bodySmall)
                    Text("切换单镜头时先取消已选镜头。mm 为标称物理焦距，非 35mm 等效焦距；变焦可能裁切画面或自动切换镜头。", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            StudioCard {
                Eyebrow(if (phoneOnly) "03 / 手机拍摄" else "04 / 联动方式")
                if (phoneOnly) {
                    Text("选择镜头和变焦倍率后直接拍摄，无需 USB 授权或开启联动。可换焦距继续拍摄，每次拍摄独立归组。", color = Muted, style = MaterialTheme.typography.bodySmall)
                    Text("拍摄仅在应用前台运行；双镜头是否可用取决于手机，双摄不保证同时曝光。", color = Muted, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = model::capture,
                        enabled = !locked && phone.permission && phone.ready && phone.selected.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().height(64.dp).testTag("phone_shutter"), shape = RoundedCornerShape(20.dp)) {
                        if (state.shooting) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Background)
                        else Text("手机拍照", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ModeCard("手机 → 相机", "点击手机快门", state.mode == SyncMode.PHONE_LEADS, !locked, Modifier.weight(1f)) { model.setMode(SyncMode.PHONE_LEADS) }
                        ModeCard("相机 → 手机", "相机拍摄后跟拍", state.mode == SyncMode.CAMERA_LEADS, !locked, Modifier.weight(1f)) { model.setMode(SyncMode.CAMERA_LEADS) }
                    }
                    Text(if (state.mode == SyncMode.CAMERA_LEADS) "延迟联动：收到照片新增事件或发现新文件后，手机才会拍摄。当前不支持快门按下瞬间触发。" else "软件联动：并行发送两端拍摄请求，曝光时间会受 USB、对焦和快门延迟影响。一次仅允许一组待配对拍摄。", color = Amber, style = MaterialTheme.typography.bodySmall)
                    Text("拍摄时请勿在相机端独立连拍；RAW + JPEG 按同名原片归组。拍摄和事件监听仅在应用前台运行。", color = Muted, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { if (state.armed) model.stopSync() else model.arm() },
                        enabled = !locked && (state.armed || (state.info?.supportsFiles == true && phone.ready && (state.mode == SyncMode.CAMERA_LEADS || state.info?.supportsCapture == true))),
                        modifier = Modifier.fillMaxWidth().testTag("sync_arm")) { Text(if (state.armed) "停止联动" else "开启联动") }
                    Button(onClick = model::capture,
                        enabled = state.armed && state.mode == SyncMode.PHONE_LEADS && !locked && phone.ready,
                        modifier = Modifier.fillMaxWidth().height(64.dp).testTag("sync_shutter"), shape = RoundedCornerShape(20.dp)) {
                        if (state.shooting) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Background)
                        else Text(if (state.mode == SyncMode.CAMERA_LEADS && state.armed) "等待相机快门…" else if (state.waitingCamera) "等待相机原片…" else "同步拍照", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                }
                Text(state.status, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("status"))
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.progress.isNotEmpty()) Text(state.progress, color = Mint, style = MaterialTheme.typography.bodySmall)
            }
            StudioCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Eyebrow(if (phoneOnly) "04 / 拍摄与归档" else "05 / 拍摄与归档"); Text("本次拍摄记录", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 6.dp)) }
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
                if (record.shots.isEmpty()) Text(if (phoneOnly) "点击手机拍照，每次拍摄自动保存镜头、倍率和照片。" else "开启联动后，每次拍摄会自动生成一组配对记录。", color = Muted, style = MaterialTheme.typography.bodySmall)
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
                    Text("允许导出不完整记录（附缺失清单）", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                Button(onClick = { model.prepareExport(allowIncomplete) }, enabled = !locked && record.shots.isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag("export_zip")) { Text(if (archiveUsesCamera) "导入原片并打包 ZIP" else "打包手机照片 ZIP") }
                Text(if (archiveUsesCamera) "ZIP 包含 phone/、camera/ 与 manifest.json。保留原始相机文件，逐文件校验 SHA-256；相机中的文件不会被删除。" else "ZIP 包含 phone/ 与 manifest.json，记录镜头标称焦距、请求变焦倍率和 SHA-256，无需相机原片即可完整导出。", color = Muted, style = MaterialTheme.typography.bodySmall)
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
            Text("本地处理 · 无账号 · 无网络权限\n${if (phoneOnly) "镜头与变焦能力需在目标手机上实测。" else "USB 联机与多摄能力仍需目标相机和手机实测。"}", color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 16.dp))
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
private fun CameraPreview(lens: PhoneLens, controller: PhoneCamera, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black)) {
        AndroidView(factory = { context -> TextureView(context).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { controller.attach(lens.key, this@apply) }
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) { controller.attach(lens.key, this@apply) }
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { controller.detach(lens.key, this@apply); return true }
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            }
        } }, onRelease = { controller.detach(lens.key, it) }, modifier = Modifier.fillMaxSize())
        Text(if (lens.physicalId != null) "PHYSICAL ${lens.physicalId}" else "CAM ${lens.cameraId}", color = Color.White,
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.TopStart).padding(10.dp).background(Color.Black.copy(alpha = .5f), RoundedCornerShape(6.dp)).padding(6.dp))
    }
}

@Composable
private fun LensZoomControls(lens: PhoneLens, ratio: Float, enabled: Boolean, onZoom: (Float) -> Unit) {
    if (lens.maxZoom <= lens.minZoom) return
    Column(Modifier.fillMaxWidth()) {
        Text("变焦 ${zoomLabel(ratio)}", color = Mint, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 8.dp).testTag("zoom_value_${lens.key}"))
        Slider(value = ratio.coerceIn(lens.minZoom, lens.maxZoom), onValueChange = onZoom, enabled = enabled,
            valueRange = lens.minZoom..lens.maxZoom, modifier = Modifier.testTag("zoom_slider_${lens.key}"))
        Row(Modifier.horizontalScroll(rememberScrollState()).testTag("zoom_presets_${lens.key}"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(.5f, 1f, 2f, 3f, 5f).filter { it in lens.minZoom..lens.maxZoom }.forEach { preset ->
                FilterChip(selected = kotlin.math.abs(ratio - preset) < .01f, enabled = enabled,
                    onClick = { onZoom(preset) }, label = { Text(zoomLabel(preset)) })
            }
        }
        Text("${zoomLabel(lens.minZoom)} – ${zoomLabel(lens.maxZoom)}", color = Muted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable private fun StudioCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
@Composable private fun Eyebrow(text: String) { Text(text, color = Mint, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold) }
@Composable private fun StatePill(text: String, active: Boolean) {
    Row(Modifier.background(if (active) Color(0xFF1B3C30) else Panel, CircleShape).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(if (active) Mint else Muted, CircleShape)); Text(text, color = if (active) Mint else Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 6.dp))
    }
}
@Composable private fun ModeCard(title: String, subtitle: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, enabled = enabled, modifier = modifier,
        border = androidx.compose.foundation.BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) Mint else Color(0xFF36413D)),
        colors = CardDefaults.outlinedCardColors(containerColor = if (selected) Color(0xFF1C342B) else Panel)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = if (selected) Mint else Color.White, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
@Composable private fun Stat(title: String, value: String) { Column { Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium); Text(title, color = Muted, style = MaterialTheme.typography.labelSmall) } }
private fun date(ms: Long) = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
private fun time(ms: Long) = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))
private fun zoomLabel(ratio: Float) = String.format(Locale.ROOT, "%.1f×", ratio)
