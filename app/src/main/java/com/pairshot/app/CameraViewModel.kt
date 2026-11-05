package com.pairshot.app

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pairshot.app.data.CaptureSession
import com.pairshot.app.data.PHONE_ONLY_MODE
import com.pairshot.app.data.RemotePhoto
import com.pairshot.app.data.SessionStore
import com.pairshot.app.data.ShotRecord
import com.pairshot.app.data.safeName
import com.pairshot.app.data.sha256
import com.pairshot.app.phone.PhoneCameraController
import com.pairshot.app.phone.PhoneCamera
import com.pairshot.app.ptp.PtpCodec
import com.pairshot.app.ptp.PtpDeviceInfo
import com.pairshot.app.ptp.PtpEvent
import com.pairshot.app.ptp.PtpObjectInfo
import com.pairshot.app.ptp.PtpResponseException
import com.pairshot.app.ptp.PtpSession
import com.pairshot.app.usb.UsbPtpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.io.File
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

private val ptpCleanup = Executors.newSingleThreadExecutor { action ->
    Thread(action, "PairShot-PTP-close").apply { isDaemon = true }
}

internal fun releasePtpSession(active: PtpSession) = ptpCleanup.submit {
    runCatching { active.close() }.onFailure { active.abort() }
}

enum class SyncMode { PHONE_LEADS, CAMERA_LEADS, PHONE_ONLY }

private fun CaptureSession.captureMode(): SyncMode = when (shots.lastOrNull()?.mode) {
    PHONE_ONLY_MODE -> SyncMode.PHONE_ONLY
    "CAMERA_LEADS_POST_CAPTURE" -> SyncMode.CAMERA_LEADS
    else -> SyncMode.PHONE_LEADS
}

data class CameraState(
    val devices: List<UsbDevice> = emptyList(), val connectedDeviceId: Int? = null,
    val info: PtpDeviceInfo? = null, val status: String = "连接相机，开始双端拍摄",
    val connectionMessage: String = "尚未连接相机会话",
    val permissionDeviceId: Int? = null,
    val busy: Boolean = false, val armed: Boolean = false, val shooting: Boolean = false,
    val mode: SyncMode = SyncMode.PHONE_LEADS, val waitingCamera: Boolean = false,
    val progress: String = "", val preparedExport: String? = null,
    val history: List<CaptureSession> = emptyList(), val logs: List<String> = emptyList(),
)

class CameraViewModel @JvmOverloads constructor(
    application: Application,
    private val sessionFactory: (UsbManager, UsbDevice, (String) -> Unit) -> PtpSession = { manager, device, diagnostic ->
        PtpSession(UsbPtpTransport.open(manager, device), diagnostic)
    },
    private val permissionRequester: (UsbManager, UsbDevice, PendingIntent) -> Unit = { manager, device, pending ->
        manager.requestPermission(device, pending)
    },
    phoneFactory: (Application, SessionStore) -> PhoneCamera = { app, store -> PhoneCameraController(app, store) },
) : AndroidViewModel(application) {
    private val manager = application.getSystemService(UsbManager::class.java)
    val store = SessionStore(File(application.filesDir, "sessions"))
    val phone = phoneFactory(application, store)
    private val mutable = MutableStateFlow(CameraState(mode = store.current.captureMode(),
        status = if (store.current.captureMode() == SyncMode.PHONE_ONLY) "仅手机模式 · 选择镜头和倍率即可拍摄" else "连接相机，开始双端拍摄"))
    val state = mutable.asStateFlow()
    private var session: PtpSession? = null
    private var sessionDeviceId: Int? = null
    private var generation = 0
    private var connection = ""
    private var permissionDevice: UsbDevice? = null
    private var syncJob: Job? = null
    private var syncGeneration = 0
    private var foreground = false
    private val seen = mutableSetOf<Long>()
    private val cameraGroups = mutableMapOf<String, String>()
    private data class PendingShot(val id: String, val started: Long, var transaction: Long = 0, var group: String? = null, var lastAdded: Long = 0)
    private var pending: PendingShot? = null
    val permissionAction = "${application.packageName}.USB_PERMISSION"

    init { refresh() }

    private fun status(message: String) {
        mutable.update { it.copy(status = message) }
        protocolLog(message)
    }
    private fun connectionStatus(message: String) {
        mutable.update { it.copy(connectionMessage = message) }
        status(message)
    }
    private fun protocolLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        mutable.update { it.copy(logs = (listOf("$time  $message") + it.logs).take(120)) }
    }
    fun diagnostics(): String {
        val app = getApplication<Application>()
        val packageInfo = app.packageManager.getPackageInfo(app.packageName, 0)
        val info = mutable.value.info
        return buildString {
            appendLine("PairShot ${packageInfo.versionName} (${packageInfo.longVersionCode})")
            appendLine("Android ${android.os.Build.VERSION.RELEASE} / ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            info?.let { appendLine("相机 ${it.manufacturer} ${it.model}，固件 ${it.version}") }
            appendLine("联动模式 ${mutable.value.mode}，状态 ${mutable.value.status}")
            appendLine("不包含照片或相机序列号。日志按时间顺序：")
            mutable.value.logs.asReversed().forEach { appendLine(it) }
        }
    }
    fun refresh() {
        mutable.update { it.copy(devices = manager.deviceList.values.filter(UsbPtpTransport::isCamera).sortedBy(UsbDevice::getDeviceName)) }
        val waiting = permissionDevice
        if (waiting != null && manager.deviceList[waiting.deviceName]?.let(manager::hasPermission) == true) {
            onPermissionResult(null)
        }
    }
    fun requestConnection(device: UsbDevice) {
        if (mutable.value.mode == SyncMode.PHONE_ONLY || mutable.value.busy || session != null || permissionDevice != null) return
        if (manager.hasPermission(device)) connect(device) else {
            val context = getApplication<Application>()
            // An immutable PendingIntent cannot rely on system fill-in extras.
            // Seed the device ourselves and retain it across an empty callback.
            val intent = Intent(permissionAction).setPackage(context.packageName)
                .putExtra(UsbManager.EXTRA_DEVICE, device)
            val permission = PendingIntent.getBroadcast(context, device.deviceId, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            permissionDevice = device
            mutable.update { it.copy(permissionDeviceId = device.deviceId) }
            connectionStatus("等待系统 USB 授权，允许后会自动连接相机…")
            runCatching { permissionRequester(manager, device, permission) }.onFailure {
                clearPermissionRequest()
                connectionStatus(it.message ?: "无法请求 USB 授权")
            }
        }
    }
    fun onPermissionResult(device: UsbDevice?) {
        val requested = permissionDevice ?: return
        if (device != null && device.deviceName != requested.deviceName) return
        val attached = manager.deviceList[requested.deviceName]?.takeIf {
            it.vendorId == requested.vendorId && it.productId == requested.productId
        }
        clearPermissionRequest()
        if (attached == null) connectionStatus("相机已拔出，请连接后再授权")
        else if (manager.hasPermission(attached)) connect(attached)
        else connectionStatus("USB 授权未通过，可点击设备重新授权")
    }
    fun recheckUsbPermission() {
        // Some Android builds omit extras or the callback; check system permission
        // again when the authorization window returns focus to the activity.
        if (permissionDevice != null) onPermissionResult(null)
    }
    fun cancelPermissionRequest() {
        if (permissionDevice == null) return
        clearPermissionRequest()
        connectionStatus("已停止等待 USB 授权，可重新点击连接")
    }
    private fun clearPermissionRequest() {
        permissionDevice = null
        mutable.update { it.copy(permissionDeviceId = null) }
    }
    private fun connect(device: UsbDevice) {
        if (mutable.value.busy || session != null) return
        val current = ++generation
        sessionDeviceId = device.deviceId
        mutable.update { it.copy(busy = true) }; connectionStatus("USB 已授权，正在打开相机会话…")
        viewModelScope.launch {
            var candidate: PtpSession? = null
            try {
                withContext(Dispatchers.IO) { candidate = sessionFactory(manager, device, ::protocolLog) }
                val active = checkNotNull(candidate)
                if (generation != current) { active.abort(); return@launch }
                session = active
                val info = withContext(Dispatchers.IO) { active.open() }
                if (generation == current) {
                    connection = UUID.randomUUID().toString()
                    mutable.update { it.copy(connectedDeviceId = device.deviceId, info = info) }
                    connectionStatus("${info.model} 已连接 · ${if (info.supportsNikonEvents) "尼康事件查询可用" else "文件轮询模式"}")
                }
            } catch (error: Exception) {
                candidate?.abort()
                if (generation == current) {
                    session = null; sessionDeviceId = null
                    connectionStatus("相机会话连接失败：${error.message ?: "请查看诊断日志"}")
                }
                if (error is CancellationException) throw error
            } finally { if (generation == current) mutable.update { it.copy(busy = false) } }
        }
    }

    fun setMode(mode: SyncMode) {
        if (mutable.value.busy || mutable.value.shooting || mutable.value.waitingCamera) return
        stopSync()
        mutable.update { it.copy(mode = mode) }
        status(if (mode == SyncMode.PHONE_ONLY) "仅手机模式 · 选择镜头和倍率即可拍摄" else "选择镜头并连接相机后开启联动")
    }
    fun toggleLens(key: String) {
        if (mutable.value.busy || mutable.value.shooting || mutable.value.waitingCamera) return
        stopSync(); phone.toggle(key)
    }
    fun setZoom(key: String, ratio: Float) {
        if (mutable.value.busy || mutable.value.shooting || mutable.value.waitingCamera) return
        stopSync(); phone.setZoom(key, ratio)
    }
    fun onForeground(value: Boolean) {
        foreground = value
        if (!value) stopSync()
        else refresh()
        phone.setRunning(value)
    }

    fun arm() {
        if (mutable.value.mode == SyncMode.PHONE_ONLY) return
        val active = session ?: return
        val info = mutable.value.info ?: return
        if (mutable.value.busy || mutable.value.armed || mutable.value.shooting) return
        if (!phone.state.value.ready) { status("请先授权并等待手机镜头就绪"); return }
        if (!info.supportsFiles) { status("相机未声明完整的文件读取能力，暂不能建立配对记录"); return }
        if (mutable.value.mode == SyncMode.PHONE_LEADS && !info.supportsCapture) { status("此型号尚无可用快门适配，可尝试相机带动手机模式"); return }
        val current = generation
        val armToken = ++syncGeneration
        mutable.update { it.copy(busy = true) }; status("正在建立存储卡基线，旧照片不会加入本次拍摄…")
        viewModelScope.launch {
            try {
                val handles = withContext(Dispatchers.IO) {
                    store.bind(info)
                    active.prepareSync()
                }
                if (generation != current || syncGeneration != armToken || !foreground) return@launch
                seen.clear(); seen.addAll(handles); cameraGroups.clear(); pending = null
                mutable.update { it.copy(armed = true) }
                status(if (mutable.value.mode == SyncMode.CAMERA_LEADS) "监听已开启 · 在相机按快门，照片生成后手机跟拍" else "联动已就绪 · 点击快门触发手机与相机")
                startMonitor(active, info)
            } catch (error: Exception) {
                status(error.message ?: "无法开启联动")
                if (!active.isOpen || (error !is PtpResponseException && error is java.io.IOException)) failConnection(error)
                if (error is CancellationException) throw error
            } finally { if (generation == current) mutable.update { it.copy(busy = false) } }
        }
    }

    fun stopSync() {
        syncGeneration++
        syncJob?.cancel(); syncJob = null
        val outstanding = pending
        pending = null
        mutable.update { it.copy(armed = false, waitingCamera = false) }
        if (outstanding != null) viewModelScope.launch(Dispatchers.IO) {
            runCatching { store.shot(outstanding.id) { it.copy(awaitingCamera = false, errors = if (it.camera.isEmpty()) it.errors + "联动停止前未收到相机照片" else it.errors) } }
                .onFailure { status("记录保存失败：${it.message}") }
        }
    }

    private fun startMonitor(active: PtpSession, info: PtpDeviceInfo) {
        val token = ++syncGeneration
        syncJob = viewModelScope.launch {
            var lastScan = 0L
            var nikonEvents = info.supportsNikonEvents
            val unresolved = mutableMapOf<Long, PtpEvent>()
            while (isActive && token == syncGeneration) {
                try {
                    val now = SystemClock.elapsedRealtime()
                    val baseline = seen.toSet()
                    val events = withContext(Dispatchers.IO) {
                        val found = mutableListOf<PtpEvent>()
                        if (nikonEvents) try { found += active.nikonEvents() } catch (error: PtpResponseException) {
                            if (error.responseCode == 0x2005) nikonEvents = false else if (error.responseCode != 0x2019) throw error
                        }
                        if (now - lastScan >= 1500) {
                            found += active.objectHandles().filter { it !in baseline }.map { PtpEvent(PtpCodec.OBJECT_ADDED, it) }
                            lastScan = now
                        }
                        found
                    }
                    if (token != syncGeneration) break
                    for (event in events) if (event.code == PtpCodec.OBJECT_ADDED && event.parameter != 0L && event.parameter !in seen) {
                        // Keep a real transaction id when a later scan reports the same handle.
                        val previous = unresolved[event.parameter]
                        if (previous == null || event.transaction != 0L) unresolved[event.parameter] = event
                    }
                    for ((handle, event) in unresolved.toMap()) {
                        val objectInfo = try { withContext(Dispatchers.IO) { active.objectInfo(handle) } } catch (error: PtpResponseException) {
                            if (error.responseCode in setOf(0x2019, 0x2009)) continue else throw error
                        }
                        if (token != syncGeneration) break
                        unresolved.remove(handle); seen += handle
                        if (objectInfo.isPhoto) onCameraPhoto(objectInfo, event)
                    }
                    finishPendingIfSettled()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    status("监听暂停：${error.message ?: "相机连接错误"}")
                    stopSync()
                    if (error !is PtpResponseException) failConnection(error)
                    break
                }
                delay(if (nikonEvents) 180 else 450)
            }
        }
    }

    internal suspend fun onCameraPhoto(info: PtpObjectInfo, event: PtpEvent) {
        if (mutable.value.mode == SyncMode.PHONE_ONLY) return
        val groupKey = "${info.groupKey}/${info.captureDate}"
        var shotId = cameraGroups[groupKey]
        if (mutable.value.mode == SyncMode.PHONE_LEADS && shotId == null) {
            val waiting = pending ?: return
            if (event.transaction != 0L && event.transaction != 0xffffffffL && waiting.transaction != 0L && event.transaction != waiting.transaction) return
            if (waiting.group != null && waiting.group != groupKey) { status("发现另一组相机照片，未自动混入当前配对"); return }
            waiting.group = groupKey; waiting.lastAdded = SystemClock.elapsedRealtime()
            shotId = waiting.id; cameraGroups[groupKey] = waiting.id
        } else if (mutable.value.mode == SyncMode.CAMERA_LEADS && shotId == null) {
            val shot = ShotRecord(mode = "CAMERA_LEADS_POST_CAPTURE", triggerElapsedNs = SystemClock.elapsedRealtimeNanos(), lenses = phone.state.value.selected)
            withContext(Dispatchers.IO) { store.add(shot) }
            shotId = shot.id; cameraGroups[groupKey] = shot.id
            capturePhone(shot.id)
        }
        val target = shotId ?: return
        val origin = connection
        withContext(Dispatchers.IO) { store.shot(target) { row ->
            if (row.camera.any { it.info.handle == info.handle }) row else row.copy(camera = row.camera + RemotePhoto(info, origin))
        } }
    }

    private suspend fun capturePhone(id: String) {
        if (mutable.value.shooting || !phone.state.value.ready || !foreground || !mutable.value.armed) {
            withContext(Dispatchers.IO) { store.shot(id) { it.copy(awaitingCamera = false, errors = it.errors + "手机镜头忙或未就绪，缺少手机照片") } }
            status("相机照片已记录，但手机未能跟拍；请降低拍摄频率")
            return
        }
        mutable.update { it.copy(shooting = true) }
        viewModelScope.launch {
            try { phone.capture(id); status("已完成一组联动拍摄") }
            catch (error: Exception) {
                withContext(Dispatchers.IO) { runCatching { store.shot(id) { it.copy(errors = it.errors + "手机拍摄：${error.message}") } } }
                status("手机拍摄失败：${error.message}")
                if (error is CancellationException) throw error
            } finally {
                try { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { store.shot(id) { it.copy(awaitingCamera = false) } } }
                catch (error: Exception) { status("记录保存失败：${error.message}") }
                finally { mutable.update { it.copy(shooting = false) } }
            }
        }
    }

    fun capture() {
        if (mutable.value.mode == SyncMode.PHONE_ONLY) { capturePhoneOnly(); return }
        val active = session ?: return
        if (!mutable.value.armed || mutable.value.mode != SyncMode.PHONE_LEADS || mutable.value.busy || mutable.value.shooting || pending != null || !phone.state.value.ready) return
        val captureGeneration = generation
        val shot = ShotRecord(mode = "PHONE_LEADS", triggerElapsedNs = SystemClock.elapsedRealtimeNanos(), lenses = phone.state.value.selected)
        pending = PendingShot(shot.id, SystemClock.elapsedRealtime())
        mutable.update { it.copy(shooting = true, waitingCamera = true) }
        status("正在并行发送双端拍摄请求…")
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.add(shot) }
                if (!foreground || !mutable.value.armed || generation != captureGeneration) {
                    withContext(Dispatchers.IO) { store.shot(shot.id) { it.copy(awaitingCamera = false, errors = it.errors + "拍摄请求在发送前已取消") } }
                    return@launch
                }
                supervisorScope {
                    val remote = async {
                        try {
                            val transaction = withContext(Dispatchers.IO) { active.capture() }
                            pending?.takeIf { it.id == shot.id }?.transaction = transaction
                            withContext(Dispatchers.IO) { store.shot(shot.id) { it.copy(cameraTransaction = transaction) } }
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            withContext(Dispatchers.IO) { store.shot(shot.id) { it.copy(awaitingCamera = false, errors = it.errors + "相机快门：${error.message}") } }
                            if (pending?.id == shot.id) { pending = null; mutable.update { it.copy(waitingCamera = false) } }
                            status("相机快门失败：${error.message}；不会自动重发")
                            if (error !is PtpResponseException) failConnection(error)
                        }
                    }
                    val local = async {
                        try { phone.capture(shot.id) } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            withContext(Dispatchers.IO) { store.shot(shot.id) { it.copy(errors = it.errors + "手机拍摄：${error.message}") } }
                            status("手机拍摄失败：${error.message}")
                        }
                    }
                    remote.await(); local.await()
                }
                if (pending?.id == shot.id) status("手机拍摄流程结束，正在等待相机文件…")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (pending?.id == shot.id) { pending = null; mutable.update { it.copy(waitingCamera = false) } }
                withContext(Dispatchers.IO) { runCatching { store.shot(shot.id) { it.copy(awaitingCamera = false, errors = it.errors + "拍摄流程中断：${error.message}") } } }
                status("拍摄流程中断：${error.message}")
            } finally { mutable.update { it.copy(shooting = false) } }
        }
    }

    private fun capturePhoneOnly() {
        val state = mutable.value
        val camera = phone.state.value
        if (!foreground || state.busy || state.shooting || state.waitingCamera ||
            !camera.permission || !camera.ready || camera.capturing || camera.selected.isEmpty()) return
        val shot = ShotRecord(mode = PHONE_ONLY_MODE, triggerElapsedNs = SystemClock.elapsedRealtimeNanos(),
            lenses = camera.selected.toList())
        mutable.update { it.copy(shooting = true) }
        status("正在拍摄手机照片…")
        viewModelScope.launch {
            var recorded = false
            try {
                withContext(Dispatchers.IO) { store.add(shot) }
                recorded = true
                check(foreground && mutable.value.mode == SyncMode.PHONE_ONLY) { "拍摄请求在发送前已取消" }
                phone.capture(shot.id)
                check(store.current.shots.first { it.id == shot.id }.complete) { "未保存所有选中镜头的照片" }
                status("已保存 ${camera.selected.size} 张手机照片，可继续拍摄或打包 ZIP")
            } catch (error: Exception) {
                if (recorded) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    runCatching { store.shot(shot.id) { it.copy(errors = it.errors + "手机拍摄：${error.message ?: "拍摄中断"}") } }
                        .onFailure { status("记录保存失败：${it.message}") }
                }
                status("手机拍摄失败：${error.message ?: "拍摄中断"}")
                if (error is CancellationException) throw error
            } finally { mutable.update { it.copy(shooting = false) } }
        }
    }

    private suspend fun finishPendingIfSettled() {
        val waiting = pending ?: return
        val now = SystemClock.elapsedRealtime()
        if (!mutable.value.shooting && waiting.lastAdded > 0 && now - waiting.lastAdded >= 3000) {
            withContext(Dispatchers.IO) { store.shot(waiting.id) { it.copy(awaitingCamera = false) } }
            pending = null; mutable.update { it.copy(waitingCamera = false) }; status("照片已配对，可继续拍摄或导入原片")
        } else if (now - waiting.started > 45_000) {
            withContext(Dispatchers.IO) { store.shot(waiting.id) { it.copy(awaitingCamera = false, errors = it.errors + "等待相机照片超时；本次记录不完整") } }
            pending = null; mutable.update { it.copy(waitingCamera = false) }; status("等待相机照片超时，请检查存储卡与拍摄结果")
        }
    }

    private fun failConnection(error: Exception) {
        generation++
        stopSync(); session?.abort(); session = null; sessionDeviceId = null
        mutable.update { it.copy(connectedDeviceId = null, info = null, busy = false) }
        connectionStatus("连接已释放：${error.message ?: "请重新连接相机"}")
    }

    fun disconnect() {
        if (mutable.value.busy || mutable.value.shooting || mutable.value.waitingCamera) return
        stopSync()
        val active = session ?: return
        val current = ++generation
        session = null; sessionDeviceId = null
        mutable.update { it.copy(busy = true, connectedDeviceId = null, info = null) }
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { active.close() }; connectionStatus("相机已断开") }
            catch (error: Exception) { connectionStatus("连接已释放：${error.message ?: "请重新连接相机"}") }
            finally { if (generation == current) mutable.update { it.copy(busy = false) } }
        }
    }
    fun onDetached(device: UsbDevice?) {
        if (device != null && device.deviceName == permissionDevice?.deviceName) {
            clearPermissionRequest()
            connectionStatus("授权期间相机已拔出")
        }
        if (device?.deviceId == sessionDeviceId) failConnection(IllegalStateException("相机已拔出"))
        refresh()
    }

    fun newSession() {
        if (mutable.value.busy || mutable.value.shooting || mutable.value.waitingCamera) return
        stopSync()
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.newSession(if (mutable.value.mode == SyncMode.PHONE_ONLY) null else mutable.value.info) }
                status("已新建拍摄记录；以前的记录保存在历史记录中")
            } catch (error: Exception) { status("新建记录失败：${error.message}") }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun loadHistory() { viewModelScope.launch { val history = withContext(Dispatchers.IO) { store.history() }; mutable.update { it.copy(history = history) } } }
    fun selectHistory(id: String) {
        if (mutable.value.busy || mutable.value.shooting || mutable.value.waitingCamera) return
        stopSync()
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.select(id) }
                val mode = store.current.captureMode()
                mutable.update { it.copy(mode = mode) }
                status(if (mode == SyncMode.PHONE_ONLY) "已打开仅手机拍摄记录，可直接继续拍摄或导出" else "已打开联动拍摄记录")
            }
            catch (error: Exception) { status("读取历史记录失败：${error.message}") }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun importPhotos() = runExport(importOnly = true, allowIncomplete = false)
    fun prepareExport(allowIncomplete: Boolean) = runExport(importOnly = false, allowIncomplete = allowIncomplete)
    private fun runExport(importOnly: Boolean, allowIncomplete: Boolean) {
        if (mutable.value.busy || mutable.value.shooting || mutable.value.waitingCamera || store.current.shots.isEmpty()) return
        val monitor = syncJob
        stopSync()
        mutable.update { it.copy(busy = true, progress = "检查本次照片…") }
        viewModelScope.launch {
            try {
                monitor?.cancelAndJoin()
                val active = session
                val info = mutable.value.info
                withContext(Dispatchers.IO) { importAll(active, info) }
                if (importOnly) {
                    val missing = store.current.shots.sumOf { row -> row.camera.count { it.path.isBlank() } }
                    status(if (missing == 0) "相机原片已复制到手机" else "仍有 $missing 张原片未导入，请检查错误后重试")
                } else {
                    val context = getApplication<Application>()
                    val name = "PairShot_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())}_${store.current.id.take(6)}.zip"
                    val destination = File(context.cacheDir, "exports/$name").apply { parentFile!!.mkdirs() }
                    withContext(Dispatchers.IO) { store.archive(destination, allowIncomplete) }
                    mutable.update { it.copy(preparedExport = destination.path) }
                    status("ZIP 已生成，请选择保存位置")
                }
            } catch (error: Exception) {
                status(error.message ?: "导入或打包失败")
                if (error is CameraTransferException) failConnection(error)
                if (error is CancellationException) throw error
            } finally { mutable.update { it.copy(busy = false, progress = "") } }
        }
    }

    private fun importAll(active: PtpSession?, info: PtpDeviceInfo?) {
        val snapshot = store.current
        val photos = snapshot.shots.flatMap { shot -> shot.camera.map { shot.id to it } }
        photos.forEachIndexed { index, (shotId, remote) ->
            mutable.update { it.copy(progress = "导入 ${index + 1}/${photos.size} · ${remote.info.filename}") }
            if (remote.path.isNotBlank()) {
                val existing = store.file(snapshot, remote.path)
                if (existing.isFile && existing.length() == remote.info.size && sha256(existing) == remote.sha256) return@forEachIndexed
            }
            var partial: File? = null
            try {
                check(active != null && info != null) { "请连接原相机以复制未导入的照片" }
                check(info.identity == snapshot.cameraIdentity && (snapshot.reliableIdentity || remote.connection == connection)) { "相机身份无法匹配，禁止使用旧句柄读取另一台相机；请连接原相机" }
                val latest = active.objectInfo(remote.info.handle)
                check(latest == remote.info) { "相机文件已改变或句柄已失效，未复制：${remote.info.filename}" }
                val path = "camera/$shotId/${remote.info.handle}_${safeName(remote.info.filename)}"
                val target = store.file(snapshot, path)
                target.parentFile!!.mkdirs()
                check(target.parentFile!!.usableSpace > remote.info.size + 16L * 1024 * 1024) { "手机存储空间不足" }
                partial = File(target.path + ".part")
                val digest = MessageDigest.getInstance("SHA-256")
                val count = DigestOutputStream(partial.outputStream().buffered(64 * 1024), digest).use { active.download(remote.info.handle, it) }
                check(count == remote.info.size && partial.length() == remote.info.size) { "照片大小校验失败" }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                check(partial.renameTo(target)) { "保存相机原片失败" }
                store.shot(shotId) { row -> row.copy(camera = row.camera.map { if (it.info.handle == remote.info.handle) it.copy(path = path, sha256 = hash, error = "") else it }) }
            } catch (error: Exception) {
                partial?.delete()
                store.shot(shotId) { row -> row.copy(camera = row.camera.map { if (it.info.handle == remote.info.handle) it.copy(path = "", sha256 = "", error = error.message ?: "导入失败") else it }) }
                if (error is java.io.IOException && error !is PtpResponseException) {
                    active?.abort()
                    // Further transfers cannot use a desynchronised bulk stream.
                    throw CameraTransferException(error)
                }
            }
        }
    }

    private class CameraTransferException(cause: Exception) : java.io.IOException("原片传输中断，请重新连接后重试：${cause.message}", cause)

    fun savePrepared(uri: Uri?, restoredPath: String? = null) {
        val path = mutable.value.preparedExport ?: restoredPath ?: return
        val exportRoot = File(getApplication<Application>().cacheDir, "exports").canonicalPath + File.separator
        if (!File(path).canonicalPath.startsWith(exportRoot)) { status("导出临时路径无效，请重新打包"); return }
        mutable.update { it.copy(preparedExport = null) }
        if (uri == null) { File(path).delete(); status("已取消保存 ZIP"); return }
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    checkNotNull(resolver.openOutputStream(uri, "w")) { "无法打开保存位置" }.use { output -> File(path).inputStream().use { it.copyTo(output, 64 * 1024) } }
                }
                status("ZIP 已保存，包含本次照片与拍摄清单")
            } catch (error: Exception) { status("ZIP 保存失败：${error.message}，请重新打包保存") }
            finally { File(path).delete(); mutable.update { it.copy(busy = false) } }
        }
    }

    override fun onCleared() {
        generation++; syncJob?.cancel()
        val active = session
        session = null
        sessionDeviceId = null
        // viewModelScope is being cancelled; close the camera-side session on an
        // independent I/O worker before releasing USB. Detach/error paths still abort.
        if (active != null) releasePtpSession(active)
        phone.close()
        super.onCleared()
    }
}
