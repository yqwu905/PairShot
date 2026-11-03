package com.pairshot.app.data

import android.util.AtomicFile
import com.pairshot.app.ptp.PtpDeviceInfo
import com.pairshot.app.ptp.PtpObjectInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun safeName(value: String): String = value.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(120).ifBlank { "photo" }

data class PhonePhoto(
    val lens: String, val label: String, val path: String, val size: Long, val sha256: String,
    val sensorTimestampNs: Long, val timestampSource: String,
    val requestedAtElapsedNs: Long, val callbackAtElapsedNs: Long,
) {
    fun json() = JSONObject().put("lens", lens).put("label", label).put("path", path).put("bytes", size)
        .put("sha256", sha256).put("sensorTimestampNs", sensorTimestampNs).put("timestampSource", timestampSource)
        .put("requestedAtElapsedNs", requestedAtElapsedNs).put("callbackAtElapsedNs", callbackAtElapsedNs)
    companion object {
        fun from(j: JSONObject) = PhonePhoto(j.getString("lens"), j.getString("label"), j.getString("path"), j.getLong("bytes"),
            j.getString("sha256"), j.getLong("sensorTimestampNs"), j.getString("timestampSource"),
            j.getLong("requestedAtElapsedNs"), j.getLong("callbackAtElapsedNs"))
    }
}

data class RemotePhoto(
    val info: PtpObjectInfo, val connection: String,
    val path: String = "", val sha256: String = "", val error: String = "",
) {
    fun json() = JSONObject().put("handle", info.handle).put("storage", info.storage).put("format", info.format)
        .put("bytes", info.size).put("parent", info.parent).put("filename", info.filename).put("captureDate", info.captureDate)
        .put("connection", connection).put("path", path).put("sha256", sha256).put("error", error)
    companion object {
        fun from(j: JSONObject) = RemotePhoto(PtpObjectInfo(j.getLong("handle"), j.getLong("storage"), j.getInt("format"),
            j.getLong("bytes"), j.getLong("parent"), j.getString("filename"), j.getString("captureDate")),
            j.getString("connection"), j.optString("path"), j.optString("sha256"), j.optString("error"))
    }
}

data class ShotRecord(
    val id: String = UUID.randomUUID().toString(), val mode: String,
    val wallTimeMs: Long = System.currentTimeMillis(), val triggerElapsedNs: Long,
    val lenses: List<String>, val phone: List<PhonePhoto> = emptyList(),
    val camera: List<RemotePhoto> = emptyList(), val errors: List<String> = emptyList(),
    val awaitingCamera: Boolean = true, val cameraTransaction: Long = 0,
) {
    val complete: Boolean get() = !awaitingCamera && lenses.isNotEmpty() && phone.size == lenses.size &&
        camera.isNotEmpty() && camera.all { it.path.isNotEmpty() && it.error.isEmpty() } && errors.isEmpty()
    fun json() = JSONObject().put("id", id).put("mode", mode).put("wallTimeMs", wallTimeMs)
        .put("triggerElapsedNs", triggerElapsedNs).put("expectedLenses", JSONArray(lenses))
        .put("phone", JSONArray(phone.map { it.json() })).put("camera", JSONArray(camera.map { it.json() }))
        .put("errors", JSONArray(errors)).put("awaitingCamera", awaitingCamera).put("cameraTransaction", cameraTransaction)
        .put("complete", complete)
    companion object {
        fun from(j: JSONObject) = ShotRecord(j.getString("id"), j.getString("mode"), j.getLong("wallTimeMs"),
            j.getLong("triggerElapsedNs"), j.getJSONArray("expectedLenses").strings(),
            j.getJSONArray("phone").objects().map(PhonePhoto::from), j.getJSONArray("camera").objects().map(RemotePhoto::from),
            j.getJSONArray("errors").strings(), j.getBoolean("awaitingCamera"), j.optLong("cameraTransaction"))
    }
}

data class CaptureSession(
    val id: String = UUID.randomUUID().toString(), val createdMs: Long = System.currentTimeMillis(),
    val cameraIdentity: String = "", val cameraName: String = "", val reliableIdentity: Boolean = false,
    val shots: List<ShotRecord> = emptyList(),
) {
    fun json() = JSONObject().put("schemaVersion", 1).put("id", id).put("createdMs", createdMs)
        .put("cameraIdentitySha256", cameraIdentity).put("cameraName", cameraName).put("reliableIdentity", reliableIdentity)
        .put("syncSemantics", "Best-effort software triggering. Camera-led mode is triggered by ObjectAdded or file discovery AFTER exposure. No hardware exposure synchronization is claimed.")
        .put("clockNotes", "Elapsed nanoseconds use Android elapsedRealtimeNanos; sensor timestamps are comparable only when timestampSource is REALTIME. Camera captureDate is uncalibrated camera-local time. No camera sensor timestamp is available.")
        .put("matchingNotes", "RAW+JPEG group by storage/parent/filename stem. Phone-led matching requires one outstanding capture and no independent camera shots. Verify physical results.")
        .put("shots", JSONArray(shots.map { it.json() }))
    companion object {
        fun from(j: JSONObject) = CaptureSession(j.getString("id"), j.getLong("createdMs"), j.optString("cameraIdentitySha256"),
            j.optString("cameraName"), j.optBoolean("reliableIdentity"), j.getJSONArray("shots").objects().map(ShotRecord::from))
    }
}

private fun JSONArray.objects() = List(length()) { getJSONObject(it) }
private fun JSONArray.strings() = List(length()) { getString(it) }

/** Immutable journal snapshots; every update is committed atomically before publication. */
class SessionStore(private val root: File) {
    private val mutable = MutableStateFlow(loadLatest())
    val session = mutable.asStateFlow()
    val current get() = mutable.value

    init { root.mkdirs() }

    private fun loadLatest(): CaptureSession {
        val journals = root.listFiles()?.filter { it.isDirectory }?.map { File(it, "session.json") }
            ?.filter { it.exists() || File(it.path + ".bak").exists() }?.sortedByDescending(File::lastModified).orEmpty()
        for (file in journals) {
            val loaded = runCatching { AtomicFile(file).openRead().bufferedReader().use { CaptureSession.from(JSONObject(it.readText())) } }.getOrNull()
            if (loaded != null && loaded.id == file.parentFile?.name) return loaded.copy(shots = loaded.shots.map { shot ->
                if (shot.awaitingCamera) shot.copy(awaitingCamera = false, errors = shot.errors + "上次拍摄未完成，请检查缺失文件") else shot
            })
        }
        return CaptureSession()
    }

    @Synchronized fun update(transform: (CaptureSession) -> CaptureSession) {
        val next = transform(mutable.value)
        val directory = sessionDirectory(next.id).apply { mkdirs() }
        val atomic = AtomicFile(File(directory, "session.json"))
        val stream = atomic.startWrite()
        try {
            stream.write(next.json().toString(2).toByteArray())
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
        mutable.value = next
    }

    fun newSession(info: PtpDeviceInfo?) = update {
        CaptureSession(cameraIdentity = info?.identity.orEmpty(), cameraName = info?.let { "${it.manufacturer} ${it.model}" }.orEmpty(),
            reliableIdentity = info?.reliableIdentity == true)
    }
    fun bind(info: PtpDeviceInfo) = update {
        check(it.shots.isEmpty() || it.cameraIdentity == info.identity) { "本次记录属于另一台相机，请新建拍摄记录" }
        it.copy(cameraIdentity = info.identity, cameraName = "${info.manufacturer} ${info.model}", reliableIdentity = info.reliableIdentity)
    }
    fun add(shot: ShotRecord) = update { it.copy(shots = it.shots + shot) }
    fun shot(id: String, transform: (ShotRecord) -> ShotRecord) = update { s ->
        s.copy(shots = s.shots.map { if (it.id == id) transform(it) else it })
    }
    private fun sessionDirectory(id: String): File {
        val directory = File(root, id).canonicalFile
        require(directory.path.startsWith(root.canonicalPath + File.separator)) { "记录路径越界" }
        return directory
    }
    fun file(session: CaptureSession, path: String): File {
        val directory = sessionDirectory(session.id)
        val file = File(directory, path).canonicalFile
        require(file.path.startsWith(directory.path + File.separator)) { "照片路径越界" }
        return file
    }
    fun file(path: String): File = file(current, path)
    fun history(): List<CaptureSession> = root.listFiles()?.filter(File::isDirectory)?.mapNotNull { directory ->
        runCatching { AtomicFile(File(directory, "session.json")).openRead().bufferedReader().use { CaptureSession.from(JSONObject(it.readText())) } }.getOrNull()
    }?.sortedByDescending { it.createdMs }.orEmpty()
    fun select(id: String) {
        val selected = history().firstOrNull { it.id == id } ?: error("记录不存在")
        update { selected.copy(shots = selected.shots.map { if (it.awaitingCamera) it.copy(awaitingCamera = false, errors = it.errors + "拍摄中断") else it }) }
    }

    fun archive(destination: File, allowIncomplete: Boolean): CaptureSession {
        val snapshot = current
        check(snapshot.shots.isNotEmpty()) { "还没有同步拍摄记录" }
        check(allowIncomplete || snapshot.shots.all { it.complete }) { "还有未完成的照片，请先补齐导入，或明确允许导出不完整记录" }
        val files = snapshot.shots.flatMap { shot ->
            shot.phone.map { Triple(it.path, it.size, it.sha256) } +
                shot.camera.filter { it.path.isNotBlank() }.map { Triple(it.path, it.info.size, it.sha256) }
        }
        for ((path, size, hash) in files) {
            val file = file(snapshot, path)
            check(file.isFile && file.length() == size && hash.isNotBlank() && sha256(file) == hash) { "照片校验失败：$path" }
        }
        val partial = File(destination.path + ".part")
        try {
            ZipOutputStream(FileOutputStream(partial)).use { zip ->
                zip.setLevel(0) // JPEG/NEF are already compressed; keep packaging fast.
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(snapshot.json().toString(2).toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("README.txt"))
                zip.write("PairShot 同步拍摄记录\nphone/ 为手机照片，camera/ 为相机原片。\nmanifest.json 按 shot id 配对，包含 SHA-256、镜头、触发时间与缺失记录。\n相机带动手机为照片生成后的延迟联动；两台设备不保证同时曝光。\n".toByteArray())
                zip.closeEntry()
                for ((path, _, _) in files.distinctBy { it.first }) {
                    zip.putNextEntry(ZipEntry(path))
                    FileInputStream(file(snapshot, path)).use { it.copyTo(zip, 64 * 1024) }
                    zip.closeEntry()
                }
            }
            check(partial.renameTo(destination)) { "无法完成 ZIP 文件" }
            return snapshot
        } catch (error: Exception) {
            partial.delete()
            throw error
        }
    }
}
