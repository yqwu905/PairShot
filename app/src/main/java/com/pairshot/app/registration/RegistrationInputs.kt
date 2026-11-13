package com.pairshot.app.registration

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class RegistrationInput(
    val token: String,
    val name: String,
    val width: Int,
    val height: Int,
    val originalWidth: Int,
    val originalHeight: Int,
) {
    val mime: String get() = "image/png"
}

internal const val MAX_REGISTRATION_INPUT_BYTES = 30L * 1024 * 1024
internal const val MAX_REGISTRATION_SOURCE_PIXELS = 60_000_000L
internal const val MAX_REGISTRATION_IMAGE_PIXELS = 6_000_000L
internal const val MAX_REGISTRATION_IMAGE_EDGE = 3072
internal const val MIN_REGISTRATION_SOURCE_EDGE = 128

internal data class RegistrationImageSize(val width: Int, val height: Int)

/** Validate before allocating image pixels and keep the phone working set bounded. */
internal fun registrationTargetSize(width: Int, height: Int): RegistrationImageSize {
    require(width > 0 && height > 0) { "无法读取图片尺寸" }
    require(min(width, height) >= MIN_REGISTRATION_SOURCE_EDGE) { "图片短边至少需要 128 像素" }
    val pixels = width.toLong() * height
    require(pixels <= MAX_REGISTRATION_SOURCE_PIXELS) { "图片不能超过 6000 万像素，请先缩小后再导入" }
    val scale = min(1.0, min(MAX_REGISTRATION_IMAGE_EDGE.toDouble() / max(width, height),
        sqrt(MAX_REGISTRATION_IMAGE_PIXELS.toDouble() / pixels)))
    val target = RegistrationImageSize(floor(width * scale).toInt().coerceAtLeast(1),
        floor(height * scale).toInt().coerceAtLeast(1))
    require(min(target.width, target.height) >= MIN_REGISTRATION_SOURCE_EDGE) {
        "图片过于狭长，缩放后短边不足 128 像素，请裁剪后再导入"
    }
    return target
}

/**
 * A tool page owns one input directory and a private opaque-token map. Call imports
 * from an I/O worker. close() never waits for image decoding or provider reads.
 */
class RegistrationInputs(context: Context, directory: File) : Closeable {
    private val resolver = context.applicationContext.contentResolver
    private val directory = directory.canonicalFile
    private val lock = Any()
    private val inputs = mutableMapOf<String, File>()
    private val owned = mutableSetOf<File>()
    @Volatile private var closed = false

    init {
        check(this.directory.isDirectory || this.directory.mkdirs()) { "无法创建图片导入目录" }
    }

    fun importUri(uri: Uri, name: String): RegistrationInput = importSource(name) {
        resolver.openInputStream(uri) ?: error("无法打开所选图片，请重新选择")
    }

    fun importFile(file: File, name: String): RegistrationInput {
        ensureOpen()
        require(file.isFile) { "图片文件不存在，请重新选择" }
        require(file.length() <= MAX_REGISTRATION_INPUT_BYTES) { "单张图片不能超过 30 MiB" }
        return importSource(name) { file.inputStream() }
    }

    /** Never interpret the caller's value as a file path. */
    fun file(token: String): File? = synchronized(lock) {
        if (closed) null else inputs[token]?.takeIf(File::isFile)
    }

    fun discard(token: String) {
        val file = synchronized(lock) { inputs.remove(token)?.also { owned.remove(it) } }
        file?.delete()
    }

    private fun importSource(name: String, source: () -> InputStream): RegistrationInput {
        ensureOpen()
        var temporary: File? = null
        var output: File? = null
        var bitmap: Bitmap? = null
        var published = false
        try {
            val temporaryOutput = createOwnedOutput("source-", ".tmp")
            temporary = temporaryOutput.first
            temporaryOutput.second.use { target ->
                try {
                    source().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            ensureOpen()
                            val count = input.read(buffer)
                            ensureOpen()
                            if (count < 0) break
                            if (count == 0) continue
                            total += count
                            require(total <= MAX_REGISTRATION_INPUT_BYTES) { "单张图片不能超过 30 MiB" }
                            target.write(buffer, 0, count)
                        }
                        require(total > 0) { "所选图片为空" }
                    }
                } catch (error: SecurityException) {
                    throw IllegalArgumentException("无法读取所选图片，请重新选择并授予访问权限", error)
                }
            }
            ensureOpen()
            var original = RegistrationImageSize(0, 0)
            bitmap = try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(checkNotNull(temporary))) { decoder, info, _ ->
                    ensureOpen()
                    original = RegistrationImageSize(info.size.width, info.size.height)
                    val target = registrationTargetSize(original.width, original.height)
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                    decoder.setTargetSize(target.width, target.height)
                    decoder.setOnPartialImageListener { false }
                }
            } catch (error: IOException) {
                ensureOpen()
                throw IllegalArgumentException("无法解码这张图片，请选择 JPEG、PNG 或系统支持的图片；RAW 原片请先转为 JPEG", error)
            }
            ensureOpen()
            val decoded = checkNotNull(bitmap)
            check(decoded.width <= MAX_REGISTRATION_IMAGE_EDGE && decoded.height <= MAX_REGISTRATION_IMAGE_EDGE &&
                decoded.width.toLong() * decoded.height <= MAX_REGISTRATION_IMAGE_PIXELS) { "图片缩放失败，请先缩小后再导入" }
            val token = UUID.randomUUID().toString()
            val pngOutput = createOwnedOutput("$token-", ".png")
            output = pngOutput.first
            pngOutput.second.use { stream ->
                check(decoded.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "无法保存导入图片" }
                stream.flush()
            }
            val result = RegistrationInput(token, name.trim().take(240).ifBlank { "图片" }, decoded.width, decoded.height,
                original.width, original.height)
            synchronized(lock) {
                ensureOpen()
                inputs[token] = checkNotNull(output)
                published = true
            }
            return result
        } finally {
            bitmap?.recycle()
            temporary?.let(::removeOwned)
            if (!published) output?.let(::removeOwned)
            if (closed) directory.delete() // Only succeeds when no other file remains.
        }
    }

    /** Open the stream under the short lock so close cannot delete and then have
     * a worker recreate the path later. Encoding itself stays outside the lock. */
    private fun createOwnedOutput(prefix: String, suffix: String): Pair<File, FileOutputStream> = synchronized(lock) {
        ensureOpen()
        val file = File.createTempFile(prefix, suffix, directory)
        owned += file
        try { file to file.outputStream() }
        catch (error: Exception) {
            owned.remove(file)
            file.delete()
            throw error
        }
    }

    private fun ensureOpen() {
        if (closed) throw CancellationException("配准工具已关闭，图片导入已取消")
    }

    private fun removeOwned(file: File) {
        synchronized(lock) { owned.remove(file) }
        file.delete()
    }

    override fun close() {
        val files = synchronized(lock) {
            if (closed) return
            closed = true
            inputs.clear()
            owned.toList().also { owned.clear() }
        }
        files.forEach { it.delete() }
        directory.delete() // Never recursively delete files owned by another instance.
    }
}
