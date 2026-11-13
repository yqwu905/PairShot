package com.pairshot.app.registration

import android.util.AtomicFile
import com.pairshot.app.data.SessionStore
import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class RegistrationImage(val size: Long, val sha256: String)

data class RegistrationRecord(
    val id: String,
    val createdAt: Long,
    val label: String,
    val width: Int,
    val height: Int,
    /** Original algorithm metrics, source names and requested processing options. */
    val metadata: JSONObject,
    /** Keys are the seven fixed PNG filenames, including their extension. */
    val images: Map<String, RegistrationImage>,
)

data class RegistrationPhoto(val id: String, val label: String, val file: File)

/**
 * Independent journal for derived images. No original capture journal or photograph
 * is modified. A result becomes visible only after every image and its manifest are
 * complete; abandoned and interrupted transfers never appear in history.
 */
class RegistrationStore(root: File) {
    companion object {
        val IMAGE_NAMES = listOf("homography.png", "geometry.png", "global.png", "aligned.png",
            "reference.png", "mask.png", "training_mask.png")
        const val MAX_METADATA_BYTES = 2 * 1024 * 1024
        const val MAX_CHUNK_CHARS = 64 * 1024
        const val MAX_IMAGE_BYTES = 24L * 1024 * 1024
        const val MAX_RESULT_BYTES = 128L * 1024 * 1024
        private val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val HASH = Regex("[0-9a-f]{64}")
        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10)
    }

    private val directory = root.canonicalFile
    private val pendingDirectory = child(directory, "pending")
    private val resultsDirectory = child(directory, "results")
    private data class Transfer(val directory: File, val metadata: JSONObject, val createdAt: Long,
        val sizes: MutableMap<String, Long> = mutableMapOf(), var total: Long = 0)
    private val transfers = mutableMapOf<String, Transfer>()

    init {
        check(directory.mkdirs() || directory.isDirectory) { "无法创建配准结果目录" }
        check(pendingDirectory.mkdirs() || pendingDirectory.isDirectory) { "无法创建配准临时目录" }
        check(resultsDirectory.mkdirs() || resultsDirectory.isDirectory) { "无法创建配准结果目录" }
        // Other instances may still own transfers in pending/. Never delete them
        // merely because another Activity or read-only store has been constructed.
    }

    @Synchronized fun begin(metadata: JSONObject): String {
        require(transfers.isEmpty()) { "已有配准结果正在保存" }
        val encoded = metadata.toString().toByteArray(Charsets.UTF_8)
        require(encoded.size <= MAX_METADATA_BYTES) { "配准参数过大" }
        val snapshot = JSONObject(encoded.toString(Charsets.UTF_8))
        outputSize(snapshot)
        val id = UUID.randomUUID().toString()
        val pending = child(pendingDirectory, id)
        check(pending.mkdir()) { "无法创建配准结果" }
        transfers[id] = Transfer(pending, snapshot, System.currentTimeMillis())
        return id
    }

    @Synchronized fun append(id: String, name: String, base64: String) {
        validateId(id)
        require(name in IMAGE_NAMES) { "不支持的配准结果文件" }
        require(base64.isNotEmpty() && base64.length <= MAX_CHUNK_CHARS) { "结果数据块大小无效" }
        val transfer = transfers[id] ?: error("配准结果保存会话不存在")
        val bytes = Base64.getDecoder().decode(base64)
        require(bytes.isNotEmpty()) { "结果数据块为空" }
        val newSize = (transfer.sizes[name] ?: 0L) + bytes.size
        require(newSize <= MAX_IMAGE_BYTES && transfer.total + bytes.size <= MAX_RESULT_BYTES) { "配准结果超过大小限制" }
        val target = child(transfer.directory, name)
        check(target.length() == (transfer.sizes[name] ?: 0L)) { "配准结果在保存过程中发生变化" }
        FileOutputStream(target, true).use { it.write(bytes) }
        transfer.sizes[name] = newSize
        transfer.total += bytes.size
    }

    @Synchronized fun commit(id: String): RegistrationRecord {
        validateId(id)
        val transfer = transfers[id] ?: error("配准结果保存会话不存在")
        check(transfer.sizes.keys == IMAGE_NAMES.toSet()) { "配准结果图片不完整" }
        val (width, height) = outputSize(transfer.metadata)
        val images = IMAGE_NAMES.associateWith { name ->
            val target = child(transfer.directory, name)
            check(target.length() == transfer.sizes[name]) { "配准结果文件长度不匹配" }
            val image = inspectPng(target)
            check(image.width == width && image.height == height) { "配准结果图片尺寸不一致" }
            RegistrationImage(target.length(), image.sha256)
        }
        val label = resultLabel(transfer.metadata)
        val record = RegistrationRecord(id, transfer.createdAt, label, width, height, transfer.metadata, images)
        val manifestBytes = manifest(record).toString(2).toByteArray(Charsets.UTF_8)
        require(manifestBytes.size <= MAX_METADATA_BYTES + 16_384) { "配准记录元数据过大" }
        val atomic = AtomicFile(child(transfer.directory, "metadata.json"))
        val stream = atomic.startWrite()
        try {
            stream.write(manifestBytes)
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
        val destination = child(resultsDirectory, id)
        check(!destination.exists() && transfer.directory.renameTo(destination)) { "无法完成配准结果保存" }
        transfers.remove(id)
        return record
    }

    /** Unknown or committed IDs cannot delete someone else's data. */
    @Synchronized fun abandon(id: String) {
        transfers.remove(id)?.directory?.deleteRecursively()
    }

    @Synchronized fun abandonAll() {
        transfers.keys.toList().forEach(::abandon)
    }

    @Synchronized fun history(): List<RegistrationRecord> = resultsDirectory.listFiles().orEmpty()
        .filter { it.isDirectory && ID.matches(it.name) }
        .mapNotNull { runCatching { readRecord(it.name, verifyContents = false) }.getOrNull() }
        .sortedByDescending { it.createdAt }

    @Synchronized fun load(id: String): RegistrationRecord = readRecord(id, verifyContents = true)

    /** Validate only the requested PNG here; a viewer makes seven independent requests. */
    @Synchronized fun file(id: String, name: String): File {
        require(name in IMAGE_NAMES || name == "metadata.json") { "不支持的配准结果文件" }
        val record = readRecord(id, verifyContents = false)
        val target = child(child(resultsDirectory, id), name)
        if (name in IMAGE_NAMES) validateImage(target, record, record.images.getValue(name), verifyContents = true)
        return target
    }

    @Synchronized fun archive(id: String, destination: File) {
        val record = readRecord(id, verifyContents = true)
        val resultDirectory = child(resultsDirectory, id)
        val output = destination.canonicalFile
        // The export cache must never overwrite a result, its manifest or a pending transfer.
        require(output != directory && !output.path.startsWith(directory.path + File.separator)) { "导出路径不能覆盖配准记录" }
        check(output.parentFile?.let { it.mkdirs() || it.isDirectory } == true) { "无法创建导出目录" }
        val partial = File(output.path + ".part")
        try {
            ZipOutputStream(FileOutputStream(partial)).use { zip ->
                zip.setLevel(0)
                for (name in IMAGE_NAMES) {
                    zip.putNextEntry(ZipEntry(name))
                    child(resultDirectory, name).inputStream().use { it.copyTo(zip, 64 * 1024) }
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("metrics.json"))
                zip.write(record.metadata.getJSONObject("metrics").toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("metadata.json"))
                zip.write(manifest(record).toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("README.txt"))
                zip.write(("PairShot 手机本地配准结果\n" +
                    "homography.png：全局透视；geometry.png：稠密几何配准；global.png：全局颜色对齐；aligned.png：最终结果。\n" +
                    "reference.png：参考图；mask.png：几何筛选掩码；training_mask.png：较严格的训练掩码。\n" +
                    "metrics.json 包含原算法参数、变换与诊断；metadata.json 包含输入说明、图像尺寸和 SHA-256。\n" +
                    "结果在手机本地计算，原始拍摄图片未修改。掩码为启发式筛选，不是准确率或像素对齐保证。\n").toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            check(partial.renameTo(output)) { "无法保存配准结果包" }
        } catch (error: Exception) {
            partial.delete()
            throw error
        }
    }

    private fun readRecord(id: String, verifyContents: Boolean): RegistrationRecord {
        validateId(id)
        val resultDirectory = child(resultsDirectory, id)
        check(resultDirectory.isDirectory) { "配准结果不存在" }
        val file = child(resultDirectory, "metadata.json")
        check(file.isFile && file.length() in 1..(MAX_METADATA_BYTES + 16_384).toLong()) { "配准记录已损坏" }
        val json = AtomicFile(file).openRead().bufferedReader().use { JSONObject(it.readText()) }
        check(json.getInt("schemaVersion") == 1 && json.getString("id") == id) { "配准记录身份不匹配" }
        val metadata = json.getJSONObject("metadata")
        val (width, height) = outputSize(metadata)
        check(json.getInt("width") == width && json.getInt("height") == height) { "配准记录尺寸不匹配" }
        val entries = json.getJSONObject("images")
        check(entries.keys().asSequence().toSet() == IMAGE_NAMES.toSet()) { "配准记录图片不完整" }
        val images = IMAGE_NAMES.associateWith { name ->
            val entry = entries.getJSONObject(name)
            RegistrationImage(entry.getLong("bytes"), entry.getString("sha256")).also {
                check(it.size in 45..MAX_IMAGE_BYTES && HASH.matches(it.sha256)) { "配准记录校验信息无效" }
            }
        }
        check(images.values.sumOf { it.size } <= MAX_RESULT_BYTES) { "配准记录超过大小限制" }
        val record = RegistrationRecord(id, json.getLong("createdAt"), json.getString("label"), width, height, metadata, images)
        check(record.createdAt > 0 && record.label.length <= 300) { "配准记录元数据无效" }
        images.forEach { (name, image) -> validateImage(child(resultDirectory, name), record, image, verifyContents) }
        return record
    }

    private fun validateImage(file: File, record: RegistrationRecord, expected: RegistrationImage, verifyContents: Boolean) {
        check(file.isFile && file.length() == expected.size) { "配准结果文件缺失或长度已改变" }
        if (verifyContents) {
            val image = inspectPng(file)
            check(image.width == record.width && image.height == record.height && image.sha256 == expected.sha256) { "配准结果校验失败" }
        } else {
            val dimensions = pngDimensions(file)
            check(dimensions == (record.width to record.height)) { "配准结果尺寸已改变" }
        }
    }

    private fun manifest(record: RegistrationRecord): JSONObject = JSONObject().put("schemaVersion", 1)
        .put("id", record.id).put("createdAt", record.createdAt).put("label", record.label)
        .put("width", record.width).put("height", record.height).put("metadata", record.metadata)
        .put("images", JSONObject().apply {
            record.images.forEach { (name, image) -> put(name, JSONObject().put("bytes", image.size).put("sha256", image.sha256)) }
        })

    private fun outputSize(metadata: JSONObject): Pair<Int, Int> {
        val size = metadata.optJSONObject("metrics")?.optJSONArray("output_wh")
        require(size != null && size.length() == 2) { "配准结果缺少输出尺寸" }
        val values = (0..1).map { index ->
            val value = size.getDouble(index)
            require(value.isFinite() && value == value.toInt().toDouble() && value in 1.0..1920.0) { "配准结果尺寸超出限制" }
            value.toInt()
        }
        return values[0] to values[1]
    }

    private fun resultLabel(metadata: JSONObject): String {
        fun name(slot: String, fallback: String): String = metadata.optJSONObject("inputs")?.optJSONObject(slot)
            ?.optString("name")?.filterNot(Char::isISOControl)?.take(120)?.ifBlank { fallback } ?: fallback
        return "${name("main", "待配准图")} → ${name("tele", "参考图")}".take(300)
    }

    private fun validateId(id: String) { require(ID.matches(id)) { "无效的配准结果编号" } }

    private fun child(parent: File, name: String): File = File(parent, name).canonicalFile.also {
        require(it.path.startsWith(parent.canonicalPath + File.separator)) { "配准结果路径越界" }
    }

    private fun pngDimensions(file: File): Pair<Int, Int> = DataInputStream(file.inputStream().buffered()).use { input ->
        val signature = ByteArray(8).also(input::readFully)
        check(signature.contentEquals(PNG_SIGNATURE) && input.readInt() == 13 && input.readInt() == 0x49484452) { "配准图片不是有效 PNG" }
        val width = input.readInt(); val height = input.readInt()
        check(width in 1..1920 && height in 1..1920) { "配准图片尺寸无效" }
        width to height
    }

    private data class Png(val width: Int, val height: Int, val sha256: String)

    /** Check the complete PNG chunk framing and CRCs without allocating a decoded bitmap. */
    private fun inspectPng(file: File): Png {
        check(file.isFile && file.length() in 45..MAX_IMAGE_BYTES) { "配准 PNG 文件大小无效" }
        val digest = MessageDigest.getInstance("SHA-256")
        var width = 0; var height = 0
        DataInputStream(DigestInputStream(file.inputStream().buffered(), digest)).use { input ->
            val signature = ByteArray(8).also(input::readFully)
            check(signature.contentEquals(PNG_SIGNATURE)) { "配准图片不是有效 PNG" }
            var offset = 8L; var first = true; var hasPixels = false; var ended = false
            val buffer = ByteArray(8192)
            while (!ended) {
                check(offset + 12 <= file.length()) { "配准 PNG 文件不完整" }
                val length = input.readInt()
                check(length >= 0 && offset + 12L + length <= file.length()) { "配准 PNG 数据长度无效" }
                val typeBytes = ByteArray(4).also(input::readFully)
                val type = typeBytes.toString(Charsets.US_ASCII)
                check(typeBytes.all { it.toInt() in 65..90 || it.toInt() in 97..122 }) { "配准 PNG 数据块无效" }
                val crc = CRC32().apply { update(typeBytes) }
                if (first) {
                    check(type == "IHDR" && length == 13) { "配准 PNG 缺少图像头" }
                    val header = ByteArray(13).also(input::readFully)
                    crc.update(header)
                    DataInputStream(header.inputStream()).use { values -> width = values.readInt(); height = values.readInt() }
                    check(width in 1..1920 && height in 1..1920) { "配准图片尺寸无效" }
                    first = false
                } else {
                    check(type != "IHDR") { "配准 PNG 包含重复图像头" }
                    var remaining = length
                    while (remaining > 0) {
                        val count = minOf(buffer.size, remaining)
                        input.readFully(buffer, 0, count); crc.update(buffer, 0, count); remaining -= count
                    }
                }
                check((input.readInt().toLong() and 0xffffffffL) == crc.value) { "配准 PNG 校验失败" }
                if (type == "IDAT" && length > 0) hasPixels = true
                if (type == "IEND") { check(length == 0 && hasPixels) { "配准 PNG 缺少图像数据" }; ended = true }
                offset += 12L + length
            }
            check(offset == file.length() && input.read() == -1) { "配准 PNG 包含多余数据" }
        }
        return Png(width, height, digest.digest().joinToString("") { "%02x".format(it) })
    }
}

/** Only already imported JPEG/PNG/WebP files are offered; RAW import is not RAW decoding. */
fun listRegistrationPhotos(store: SessionStore): List<RegistrationPhoto> {
    val sessions = (listOf(store.current) + store.history()).distinctBy { it.id }.sortedByDescending { it.createdMs }
    val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    val photos = mutableListOf<RegistrationPhoto>()
    for (session in sessions) {
        for ((index, shot) in session.shots.withIndex().reversed()) {
            val prefix = "${formatter.format(Date(shot.wallTimeMs))} · 第 ${index + 1} 组"
            fun offer(path: String, label: String) {
                if (path.isBlank()) return
                val file = runCatching { store.file(session, path) }.getOrNull() ?: return
                if (!file.isFile || file.length() <= 0 || file.extension.lowercase(Locale.ROOT) !in setOf("jpg", "jpeg", "png", "webp")) return
                val id = UUID.nameUUIDFromBytes("${session.id}\u0000${shot.id}\u0000$path".toByteArray(Charsets.UTF_8)).toString()
                photos += RegistrationPhoto(id, "$prefix · ${label.filterNot(Char::isISOControl).take(180)}", file)
            }
            shot.phone.forEach { offer(it.path, "手机 ${it.label}") }
            shot.camera.filter { it.error.isBlank() }.forEach { offer(it.path, "相机 ${it.info.filename}") }
        }
    }
    return photos.distinctBy { it.id }
}
