package com.pairshot.app.registration

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNativeImageDecoder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [RegistrationInputsTest.FileDecoderShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RegistrationInputsTest {
    @get:Rule val temp = TemporaryFolder()

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    // Robolectric's desktop native codec cannot construct a decoder from an
    // Android FileDescriptor. Only change source transport; header callbacks,
    // actual PNG decoding, resizing, color conversion and encoding stay native.
    @Implements(ImageDecoder::class, callNativeMethodsByDefault = true)
    class FileDecoderShadow : ShadowNativeImageDecoder() {
        companion object {
            @JvmStatic @Implementation
            fun createSource(file: File): ImageDecoder.Source = ImageDecoder.createSource(ByteBuffer.wrap(file.readBytes()))
        }
    }

    @Test fun sizingRejectsSmallAndOversizedSourcesBeforePixelAllocation() {
        assertEquals(RegistrationImageSize(128, 128), registrationTargetSize(128, 128))
        assertEquals(RegistrationImageSize(640, 480), registrationTargetSize(640, 480))
        assertThrows(IllegalArgumentException::class.java) { registrationTargetSize(127, 200) }
        assertThrows(IllegalArgumentException::class.java) { registrationTargetSize(200, 127) }
        assertThrows(IllegalArgumentException::class.java) { registrationTargetSize(0, 100) }
        assertThrows(IllegalArgumentException::class.java) { registrationTargetSize(-1, 100) }
        assertThrows(IllegalArgumentException::class.java) { registrationTargetSize(10_000, 6_001) }
        assertThrows(IllegalArgumentException::class.java) { registrationTargetSize(Int.MAX_VALUE, Int.MAX_VALUE) }
        val narrow = assertThrows(IllegalArgumentException::class.java) { registrationTargetSize(10_000, 128) }
        assertTrue(narrow.message.orEmpty().contains("缩放后短边不足 128"))
        assertThrows(IllegalArgumentException::class.java) { registrationTargetSize(128, 10_000) }
        val boundary = registrationTargetSize(10_000, 6_000)
        assertTrue(boundary.width.toLong() * boundary.height <= MAX_REGISTRATION_IMAGE_PIXELS)
    }

    @Test fun sizingHonorsBothLongEdgeAndPixelBudgetsWithoutUpscaling() {
        assertEquals(RegistrationImageSize(3072, 1536), registrationTargetSize(4096, 2048))
        assertEquals(RegistrationImageSize(3000, 2000), registrationTargetSize(6000, 4000))
        val square = registrationTargetSize(6000, 6000)
        assertEquals(square.width, square.height)
        assertTrue(square.width < 3072)
        assertTrue(square.width.toLong() * square.height <= MAX_REGISTRATION_IMAGE_PIXELS)
        assertEquals(RegistrationImageSize(1536, 3072), registrationTargetSize(2048, 4096))
        assertEquals(RegistrationImageSize(128, 1024), registrationTargetSize(128, 1024))
    }

    @Test fun pngImportNormalizesAndKeepsTheSourceUntouched() {
        val source = png("original.png", 320, 240)
        val before = source.readBytes()
        val directory = File(temp.root, "inputs")
        RegistrationInputs(app, directory).use { inputs ->
            val result = inputs.importFile(source, "original.png")
            assertEquals(UUID.fromString(result.token).toString(), result.token)
            assertEquals("original.png", result.name)
            assertEquals("image/png", result.mime)
            assertEquals(320, result.width)
            assertEquals(240, result.height)
            assertEquals(320, result.originalWidth)
            assertEquals(240, result.originalHeight)
            val output = checkNotNull(inputs.file(result.token))
            assertTrue(output.isFile)
            assertNotEquals(source.canonicalFile, output.canonicalFile)
            assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a), output.readBytes().take(8).toByteArray())
            BitmapFactory.decodeFile(output.path).let { decoded ->
                assertNotNull(decoded)
                assertEquals(Color.rgb(40, 90, 160), decoded.getPixel(160, 120))
                decoded.recycle()
            }
            assertArrayEquals(before, source.readBytes())
            assertNull(inputs.file("../original.png"))
            assertNull(inputs.file(output.path))
            assertNull(inputs.file(result.token + ".png"))
        }
        assertArrayEquals(before, source.readBytes())
        assertFalse(directory.exists())
    }

    @Test fun uriImportScalesLargeImagesAndPreservesOriginalDimensions() {
        val source = png("large.png", 4096, 2048)
        RegistrationInputs(app, File(temp.root, "inputs")).use { inputs ->
            val result = inputs.importUri(Uri.fromFile(source), "large.png")
            assertEquals(4096, result.originalWidth)
            assertEquals(2048, result.originalHeight)
            assertEquals(3072, result.width)
            assertEquals(1536, result.height)
            val decoded = BitmapFactory.decodeFile(checkNotNull(inputs.file(result.token)).path)
            assertEquals(result.width, decoded.width)
            assertEquals(result.height, decoded.height)
            decoded.recycle()
        }
        assertTrue(source.isFile)
    }

    @Test fun jpegExifClockwiseOrientationIsAppliedToPixelsWithoutChangingTheOriginal() {
        val top = Color.rgb(210, 35, 50)
        val bottom = Color.rgb(20, 60, 215)
        val bitmap = Bitmap.createBitmap(160, 320, Bitmap.Config.ARGB_8888)
        val jpeg = try {
            bitmap.setPixels(IntArray(160 * 320) { index -> if (index / 160 < 160) top else bottom },
                0, 160, 0, 0, 160, 320)
            ByteArrayOutputStream().use { bytes ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, bytes))
                bytes.toByteArray()
            }
        } finally { bitmap.recycle() }
        assertEquals(0xff, jpeg[0].toInt() and 0xff)
        assertEquals(0xd8, jpeg[1].toInt() and 0xff)
        // APP1 Exif header, little-endian TIFF, one SHORT Orientation entry = 6.
        // The encoded pixels are portrait; viewing orientation is 90 degrees CW.
        val exif = byteArrayOf(
            0x45, 0x78, 0x69, 0x66, 0, 0,
            0x49, 0x49, 0x2a, 0, 8, 0, 0, 0,
            1, 0,
            0x12, 1, 3, 0, 1, 0, 0, 0, 6, 0, 0, 0,
            0, 0, 0, 0,
        )
        val length = exif.size + 2
        val original = jpeg.copyOfRange(0, 2) +
            byteArrayOf(0xff.toByte(), 0xe1.toByte(), (length shr 8).toByte(), length.toByte()) +
            exif + jpeg.copyOfRange(2, jpeg.size)
        val source = temp.newFile("portrait-exif-6.jpg").apply { writeBytes(original) }
        RegistrationInputs(app, File(temp.root, "inputs")).use { inputs ->
            val result = inputs.importFile(source, source.name)
            assertEquals(320, result.width)
            assertEquals(160, result.height)
            val normalized = BitmapFactory.decodeFile(checkNotNull(inputs.file(result.token)).path)
            try {
                assertEquals(320, normalized.width)
                assertEquals(160, normalized.height)
                // Clockwise rotation maps the old bottom half to the left.
                val left = normalized.getPixel(80, 80)
                val right = normalized.getPixel(240, 80)
                for (channel in listOf<(Int) -> Int>(Color::red, Color::green, Color::blue)) {
                    assertTrue("Expected blue on the left after EXIF rotation", kotlin.math.abs(channel(left) - channel(bottom)) <= 10)
                    assertTrue("Expected red on the right after EXIF rotation", kotlin.math.abs(channel(right) - channel(top)) <= 10)
                }
            } finally { normalized.recycle() }
        }
        assertArrayEquals(original, source.readBytes())
    }

    @Test fun failedImportsLeaveNoOwnedFilesAndDoNotChangeInputs() {
        val invalid = temp.newFile("broken.nef").apply { writeText("not an image") }
        val small = png("small.png", 127, 128)
        val tooLarge = temp.newFile("large.nef").apply {
            RandomAccessFile(this, "rw").use { it.setLength(MAX_REGISTRATION_INPUT_BYTES + 1) }
        }
        val directory = File(temp.root, "inputs")
        RegistrationInputs(app, directory).use { inputs ->
            val corrupt = assertThrows(IllegalArgumentException::class.java) { inputs.importFile(invalid, "broken.nef") }
            assertTrue(corrupt.message.orEmpty().contains("无法解码"))
            assertThrows(IllegalArgumentException::class.java) { inputs.importFile(small, "small.png") }
            val limit = assertThrows(IllegalArgumentException::class.java) { inputs.importFile(tooLarge, "large.nef") }
            assertTrue(limit.message.orEmpty().contains("30 MiB"))
            // URI providers need the streamed byte limit too, independent of file.length().
            assertThrows(IllegalArgumentException::class.java) { inputs.importUri(Uri.fromFile(tooLarge), "large.nef") }
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        }
        assertEquals("not an image", invalid.readText())
        assertTrue(small.isFile)
        assertEquals(MAX_REGISTRATION_INPUT_BYTES + 1, tooLarge.length())
    }

    @Test fun discardAndCloseOnlyRemoveThisInstancesFiles() {
        val source = png("source.png", 128, 128)
        val directory = temp.newFolder("inputs")
        val foreign = File(directory, "foreign.txt").apply { writeText("keep me") }
        val inputs = RegistrationInputs(app, directory)
        val first = inputs.importFile(source, "first")
        val firstFile = checkNotNull(inputs.file(first.token))
        val second = inputs.importFile(source, "second")
        val secondFile = checkNotNull(inputs.file(second.token))
        inputs.discard(first.token)
        inputs.discard("../foreign.txt")
        assertFalse(firstFile.exists())
        assertNull(inputs.file(first.token))
        assertTrue(secondFile.exists())
        inputs.close()
        inputs.close()
        assertNull(inputs.file(second.token))
        assertFalse(secondFile.exists())
        assertEquals("keep me", foreign.readText())
        assertThrows(CancellationException::class.java) { inputs.importFile(source, "after close") }
        assertTrue(source.isFile)
        assertEquals(listOf(foreign), directory.listFiles().orEmpty().toList())
    }

    @Test fun closeDoesNotWaitForABlockedProviderAndTheWorkerCannotRecreateFiles() {
        val directory = File(temp.root, "inputs")
        val inputs = RegistrationInputs(app, directory)
        val enteredRead = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val source = {
            object : InputStream() {
                override fun read(): Int = error("Use the buffered read")
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    enteredRead.countDown()
                    releaseRead.await()
                    return -1
                }
            }
        }
        // Both public imports use this exact copy/decode pipeline. A blocked
        // stream models a slow document provider without timing a real decoder.
        val importSource = RegistrationInputs::class.java
            .getDeclaredMethod("importSource", String::class.java, Function0::class.java).apply { isAccessible = true }
        val worker = Thread {
            try { importSource.invoke(inputs, "provider.png", source) }
            catch (error: InvocationTargetException) { failure.set(error.cause) }
            catch (error: Throwable) { failure.set(error) }
        }
        worker.start()
        var closer: Thread? = null
        try {
            assertTrue("Import never started", enteredRead.await(3, TimeUnit.SECONDS))
            closer = Thread { inputs.close(); closeReturned.countDown() }.apply { start() }
            assertTrue("close must not wait for the provider", closeReturned.await(3, TimeUnit.SECONDS))
        } finally {
            releaseRead.countDown()
            worker.join(3_000)
            closer?.join(3_000)
            inputs.close()
        }
        assertFalse(worker.isAlive)
        assertTrue(failure.get() is CancellationException)
        assertFalse(directory.exists())
    }

    private fun png(name: String, width: Int, height: Int): File = temp.newFile(name).also { file ->
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(40, 90, 160))
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
