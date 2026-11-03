package com.pairshot.app.ptp

import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

data class PtpContainer(val type: Int, val code: Int, val transaction: Long, val payload: ByteArray)

object PtpCodec {
    const val HEADER_SIZE = 12
    const val MAX_CONTAINER_SIZE = 16 * 1024 * 1024
    const val COMMAND = 1
    const val DATA = 2
    const val RESPONSE = 3
    const val EVENT = 4
    const val OK = 0x2001
    const val SESSION_NOT_OPEN = 0x2003
    const val SESSION_ALREADY_OPEN = 0x201e
    const val GET_DEVICE_INFO = 0x1001
    const val OPEN_SESSION = 0x1002
    const val CLOSE_SESSION = 0x1003
    const val INITIATE_CAPTURE = 0x100e
    const val GET_OBJECT_HANDLES = 0x1007
    const val GET_OBJECT_INFO = 0x1008
    const val GET_OBJECT = 0x1009
    const val NIKON_GET_EVENT = 0x90c7
    const val OBJECT_ADDED = 0x4002
    const val CAPTURE_COMPLETE = 0x400d
    const val NIKON_VENDOR_ID = 0x0000000aL
    const val NIKON_CHANGE_CAMERA_MODE = 0x90c2
    const val NIKON_DEVICE_READY = 0x90c8
    const val NIKON_CAPTURE_TO_MEDIA = 0x9207

    fun operationName(code: Int): String = when (code) {
        GET_DEVICE_INFO -> "GetDeviceInfo"
        OPEN_SESSION -> "OpenSession"
        CLOSE_SESSION -> "CloseSession"
        GET_OBJECT_HANDLES -> "GetObjectHandles"
        GET_OBJECT_INFO -> "GetObjectInfo"
        GET_OBJECT -> "GetObject"
        INITIATE_CAPTURE -> "InitiateCapture"
        NIKON_GET_EVENT -> "Nikon.GetEvent"
        NIKON_CHANGE_CAMERA_MODE -> "Nikon.ChangeCameraMode"
        NIKON_DEVICE_READY -> "Nikon.DeviceReady"
        NIKON_CAPTURE_TO_MEDIA -> "Nikon.CaptureToMedia"
        else -> "PTP"
    }

    fun hex(code: Int): String = "0x%04X".format(Locale.ROOT, code)

    fun command(code: Int, transaction: Long, vararg parameters: Long): ByteArray {
        require(parameters.size <= 5)
        require(transaction in 0..0xffffffffL && parameters.all { it in 0..0xffffffffL })
        return ByteBuffer.allocate(HEADER_SIZE + 4 * parameters.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(capacity())
            putShort(COMMAND.toShort())
            putShort(code.toShort())
            putInt(transaction.toInt())
            parameters.forEach { putInt(it.toInt()) }
        }.array()
    }

    fun length(header: ByteArray): Int {
        require(header.size >= HEADER_SIZE) { "PTP 数据头不完整" }
        val size = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        require(size in HEADER_SIZE..MAX_CONTAINER_SIZE) { "PTP 数据长度无效：$size" }
        return size.toInt()
    }

    fun decode(bytes: ByteArray): PtpContainer {
        require(length(bytes) == bytes.size) { "PTP 数据长度不匹配" }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        b.int
        val type = b.short.toInt() and 0xffff
        require(type in 1..4) { "未知的 PTP 数据类型" }
        val code = b.short.toInt() and 0xffff
        val transaction = b.int.toLong() and 0xffffffffL
        return PtpContainer(type, code, transaction, bytes.copyOfRange(HEADER_SIZE, bytes.size))
    }
}

enum class CaptureMode { STANDARD, NIKON_Z6, UNSUPPORTED }

data class PtpDeviceInfo(
    val manufacturer: String,
    val model: String,
    val version: String,
    val operations: Set<Int>,
    val vendorExtensionId: Long,
    val events: Set<Int> = emptySet(),
    val identity: String = "",
    val reliableIdentity: Boolean = false,
) {
    val captureMode: CaptureMode
        get() {
            val normalizedModel = model.uppercase(Locale.ROOT).filter(Char::isLetterOrDigit).removePrefix("NIKON")
            return when {
                vendorExtensionId == PtpCodec.NIKON_VENDOR_ID && normalizedModel == "Z6" &&
                    PtpCodec.NIKON_CAPTURE_TO_MEDIA in operations -> CaptureMode.NIKON_Z6
                PtpCodec.INITIATE_CAPTURE in operations -> CaptureMode.STANDARD
                else -> CaptureMode.UNSUPPORTED
            }
        }
    val supportsCapture: Boolean get() = captureMode != CaptureMode.UNSUPPORTED
    val supportsFiles: Boolean get() = setOf(PtpCodec.GET_OBJECT_HANDLES, PtpCodec.GET_OBJECT_INFO, PtpCodec.GET_OBJECT).all { it in operations }
    val supportsNikonEvents: Boolean get() = vendorExtensionId == PtpCodec.NIKON_VENDOR_ID && PtpCodec.NIKON_GET_EVENT in operations

    companion object {
        fun decode(bytes: ByteArray): PtpDeviceInfo {
            val r = DatasetReader(bytes)
            r.u16() // Standard version
            val vendor = r.u32()
            r.u16() // Vendor extension version
            r.string()
            r.u16() // Functional mode
            val operations = r.u16Array().toSet()
            val events = r.u16Array().toSet()
            repeat(3) { r.u16Array() } // Properties, capture formats, image formats
            val manufacturer = r.string()
            val model = r.string()
            val version = r.string()
            val serial = r.string()
            // Retain only a digest for safe reconnect/import identity checks.
            val identity = java.security.MessageDigest.getInstance("SHA-256")
                .digest("$vendor|$manufacturer|$model|$serial".toByteArray()).joinToString("") { "%02x".format(it) }
            return PtpDeviceInfo(manufacturer, model, version, operations, vendor, events, identity, serial.isNotBlank())
        }
    }
}

internal class DatasetReader(bytes: ByteArray) {
    private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private fun need(size: Int) { require(size >= 0 && buffer.remaining() >= size) { "相机信息数据不完整" } }
    fun u16(): Int { need(2); return buffer.short.toInt() and 0xffff }
    fun u32(): Long { need(4); return buffer.int.toLong() and 0xffffffffL }
    fun skip(size: Int) { need(size); buffer.position(buffer.position() + size) }
    fun u32Array(): List<Long> {
        val count = u32()
        require(count <= buffer.remaining() / 4) { "对象列表长度无效" }
        return List(count.toInt()) { u32() }
    }
    fun u16Array(): List<Int> {
        val count = u32()
        require(count <= buffer.remaining() / 2) { "相机信息数组长度无效" }
        return List(count.toInt()) { u16() }
    }
    fun string(): String {
        need(1)
        val count = buffer.get().toInt() and 0xff
        if (count == 0) return ""
        need(count * 2)
        val text = ByteArray((count - 1) * 2)
        buffer.get(text)
        require(u16() == 0) { "PTP 字符串没有结束标记" }
        return text.toString(Charsets.UTF_16LE)
    }
}

interface PtpTransport : Closeable {
    fun send(command: ByteArray)
    fun receive(): PtpContainer
    /** USB implementations stream this data phase without buffering a whole RAW file. */
    fun receiveData(code: Int, transaction: Long, output: OutputStream): Long {
        val container = receive()
        require(container.transaction == transaction) { "PTP 数据事务不匹配" }
        if (container.type == PtpCodec.RESPONSE) {
            if (container.code != PtpCodec.OK) throw PtpResponseException(container.code, code, transaction)
            throw IOException("相机未返回照片数据")
        }
        require(container.type == PtpCodec.DATA && container.code == code) { "PTP 数据类型不匹配" }
        output.write(container.payload)
        return container.payload.size.toLong()
    }
}

data class PtpEvent(val code: Int, val parameter: Long = 0, val transaction: Long = 0) {
    companion object {
        fun decodeNikon(bytes: ByteArray): List<PtpEvent> {
            val reader = DatasetReader(bytes)
            val count = reader.u16()
            require(bytes.size >= 2 + count * 6) { "尼康事件数据长度无效" }
            return List(count) { PtpEvent(reader.u16(), reader.u32()) }
        }
    }
}

data class PtpObjectInfo(
    val handle: Long, val storage: Long, val format: Int, val size: Long,
    val parent: Long, val filename: String, val captureDate: String,
) {
    val isPhoto: Boolean get() = format != 0x3001 && filename.substringAfterLast('.', "").lowercase(Locale.ROOT) in
        setOf("jpg", "jpeg", "nef", "nrw", "dng", "tif", "tiff", "heic", "hif", "cr2", "cr3", "arw", "raf", "orf", "rw2", "pef")
    val groupKey: String get() = "$storage/$parent/${filename.substringBeforeLast('.').uppercase(Locale.ROOT)}"
    companion object {
        fun decode(handle: Long, bytes: ByteArray): PtpObjectInfo {
            val r = DatasetReader(bytes)
            val storage = r.u32()
            val format = r.u16()
            r.u16()
            val size = r.u32()
            r.skip(26) // thumbnail descriptor, full image dimensions and bit depth
            val parent = r.u32()
            r.skip(10) // association type/description and sequence number
            val filename = r.string()
            val captureDate = r.string()
            r.string(); r.string() // modification time, keywords
            return PtpObjectInfo(handle, storage, format, size, parent, filename, captureDate)
        }
    }
}

class PtpResponseException(
    val responseCode: Int,
    val operationCode: Int? = null,
    val transactionId: Long? = null,
) : IOException(
    when (responseCode) {
        0x2003 -> "相机会话已失效"
        0x2004 -> "相机事务序号异常"
        0x2005 -> "相机不支持此操作"
        0x200f -> "相机拒绝访问，请检查相机的 USB 连接模式"
        0x2019 -> "相机忙，请稍后再试"
        0x201d -> "相机参数不支持，请检查拍摄模式"
        0x201e -> if (operationCode == PtpCodec.OPEN_SESSION || operationCode == null) "相机存在尚未结束的会话" else "相机返回会话状态异常"
        0xa002 -> "相机未合焦，请先在相机端完成对焦"
        0xa004 -> "相机当前状态无法拍摄，请检查拍摄模式及相机屏幕"
        0xa008 -> "当前为 B 门模式，首版暂不支持长按快门"
        0xa021 -> "相机存储错误，请检查存储卡"
        0xa022 -> "相机存储卡未格式化，请在相机端检查"
        else -> "相机返回错误：0x${responseCode.toString(16)}"
    } + "（返回 ${PtpCodec.hex(responseCode)}" +
        (operationCode?.let { "，${PtpCodec.operationName(it)} ${PtpCodec.hex(it)}" } ?: "") +
        (transactionId?.let { "，事务 $it" } ?: "") + "）",
)

/** A single USB session. Call serially on an I/O thread, never the main thread. */
class PtpSession(
    private val transport: PtpTransport,
    private val diagnostic: (String) -> Unit = {},
) : Closeable {
    private var nextTransaction = 1L
    @Volatile private var opened = false
    val isOpen: Boolean get() = opened
    private var info: PtpDeviceInfo? = null
    private var nikonControlMode = false
    private var nikonModeAttempted = false

    @Synchronized fun open(): PtpDeviceInfo {
        check(!opened) { "PTP 会话已经打开" }
        try {
            val data = transact(PtpCodec.GET_DEVICE_INFO, 0)
            val deviceInfo = PtpDeviceInfo.decode(requireNotNull(data) { "相机未返回设备信息" })
            try {
                transact(PtpCodec.OPEN_SESSION, 0, 1)
                opened = true
            } catch (error: PtpResponseException) {
                if (error.responseCode != PtpCodec.SESSION_ALREADY_OPEN) throw error
                trace("检测到遗留 PTP 会话，关闭后重新建立一次")
                restartSession()
            }
            info = deviceInfo
            return deviceInfo
        } catch (error: Exception) {
            abort()
            throw error
        }
    }

    /** Called only while arming, before any shutter is sent. Never retries a capture. */
    @Synchronized fun prepareSync(): Set<Long> {
        check(opened) { "相机尚未连接" }
        try {
            return readBaseline()
        } catch (error: PtpResponseException) {
            if (!error.isSessionError) throw error
            trace("开启联动时会话状态异常，重建会话并重新读取基线一次")
            try {
                restartSession()
                return readBaseline()
            } catch (retryError: Exception) {
                // Do not leave the UI claiming a usable connection after recovery fails.
                abort()
                throw retryError
            }
        }
    }

    private val PtpResponseException.isSessionError: Boolean
        get() = responseCode == PtpCodec.SESSION_ALREADY_OPEN || responseCode == PtpCodec.SESSION_NOT_OPEN

    private fun readBaseline(): Set<Long> {
        useLocalShutter()
        if (info?.supportsNikonEvents == true) {
            try { nikonEvents() } catch (error: PtpResponseException) {
                if (error.responseCode != 0x2005) throw error
                trace("尼康事件查询不受支持，使用新文件扫描")
            }
        }
        return objectHandles().toSet()
    }

    private fun restartSession() {
        opened = false
        try {
            transact(PtpCodec.CLOSE_SESSION, nextTransaction++)
        } catch (error: PtpResponseException) {
            if (error.responseCode != PtpCodec.SESSION_NOT_OPEN) throw error
        }
        nikonControlMode = false
        nikonModeAttempted = false
        nextTransaction = 1L
        transact(PtpCodec.OPEN_SESSION, 0, 1)
        opened = true
        trace("PTP 会话已重建，事务号从 1 重新开始")
    }

    private fun trace(message: String) { runCatching { diagnostic(message) } }

    @Synchronized fun capture(): Long {
        check(opened) { "相机尚未连接" }
        val deviceInfo = checkNotNull(info)
        val captureTransaction: Long
        when (deviceInfo.captureMode) {
            CaptureMode.NIKON_Z6 -> {
                if (!nikonModeAttempted && PtpCodec.NIKON_CHANGE_CAMERA_MODE in deviceInfo.operations) {
                    try {
                        transact(PtpCodec.NIKON_CHANGE_CAMERA_MODE, nextTransaction++, 1)
                        nikonControlMode = true
                    } catch (error: PtpResponseException) {
                        // libgphoto2 treats Nikon's ChangeCameraModeFailed as nonfatal.
                        // Do not suppress other errors or restore a mode we never entered.
                        if (error.responseCode != 0xa003) throw error
                    }
                    nikonModeAttempted = true
                }
                if (PtpCodec.NIKON_DEVICE_READY in deviceInfo.operations) {
                    // A busy response is surfaced to the user; never retry a shutter automatically.
                    transact(PtpCodec.NIKON_DEVICE_READY, nextTransaction++)
                }
                // Protocol facts checked against libgphoto2 ptp_nikon_capture2:
                // 0xffffffff = use current focus; target 0 = save to camera memory card.
                captureTransaction = nextTransaction++
                transact(PtpCodec.NIKON_CAPTURE_TO_MEDIA, captureTransaction, 0xffffffffL, 0)
            }
            CaptureMode.STANDARD -> {
                captureTransaction = nextTransaction++
                transact(PtpCodec.INITIATE_CAPTURE, captureTransaction, 0, 0)
            }
            CaptureMode.UNSUPPORTED -> error("该相机需要适配厂商拍摄协议")
        }
        return captureTransaction
    }

    @Synchronized fun useLocalShutter() {
        if (nikonControlMode) {
            transact(PtpCodec.NIKON_CHANGE_CAMERA_MODE, nextTransaction++, 0)
            nikonControlMode = false
            nikonModeAttempted = false
        }
    }

    @Synchronized fun objectHandles(): List<Long> {
        check(opened)
        val bytes = transact(PtpCodec.GET_OBJECT_HANDLES, nextTransaction++, 0xffffffffL, 0, 0)
        return DatasetReader(requireNotNull(bytes)).u32Array()
    }

    @Synchronized fun objectInfo(handle: Long): PtpObjectInfo {
        check(opened)
        return PtpObjectInfo.decode(handle, requireNotNull(transact(PtpCodec.GET_OBJECT_INFO, nextTransaction++, handle)))
    }

    @Synchronized fun nikonEvents(): List<PtpEvent> {
        check(opened)
        if (info?.supportsNikonEvents != true) return emptyList()
        return PtpEvent.decodeNikon(requireNotNull(transact(PtpCodec.NIKON_GET_EVENT, nextTransaction++)))
    }

    @Synchronized fun download(handle: Long, output: OutputStream): Long {
        check(opened)
        val transaction = nextTransaction++
        transport.send(PtpCodec.command(PtpCodec.GET_OBJECT, transaction, handle))
        val count = transport.receiveData(PtpCodec.GET_OBJECT, transaction, output)
        val response = transport.receive()
        require(response.type == PtpCodec.RESPONSE && response.transaction == transaction) { "PTP 下载响应事务不匹配" }
        if (response.code != PtpCodec.OK) throw PtpResponseException(response.code, PtpCodec.GET_OBJECT, transaction)
        return count
    }

    private fun transact(code: Int, transaction: Long, vararg parameters: Long): ByteArray? {
        trace("→ ${PtpCodec.operationName(code)} ${PtpCodec.hex(code)} tx=$transaction")
        transport.send(PtpCodec.command(code, transaction, *parameters))
        var result: ByteArray? = null
        var container = transport.receive()
        if (container.type == PtpCodec.DATA) {
            require(container.transaction == transaction && container.code == code) { "PTP 数据事务不匹配" }
            result = container.payload
            container = transport.receive()
        }
        require(container.type == PtpCodec.RESPONSE && container.transaction == transaction) {
            "${PtpCodec.operationName(code)} 的 PTP 响应事务不匹配：预期 $transaction，收到 ${container.transaction}"
        }
        trace("← ${PtpCodec.operationName(code)} tx=$transaction RC=${PtpCodec.hex(container.code)}")
        if (container.code != PtpCodec.OK) throw PtpResponseException(container.code, code, transaction)
        return result
    }

    @Synchronized override fun close() {
        try {
            if (opened) {
                try {
                    if (nikonControlMode) transact(PtpCodec.NIKON_CHANGE_CAMERA_MODE, nextTransaction++, 0)
                } finally {
                    transact(PtpCodec.CLOSE_SESSION, nextTransaction++)
                }
            }
        } finally {
            opened = false
            nikonControlMode = false
            nikonModeAttempted = false
            transport.close()
        }
    }

    /** Release immediately after USB detachment or when the process is shutting down. */
    fun abort() {
        opened = false
        nikonControlMode = false
        nikonModeAttempted = false
        transport.close()
    }
}
