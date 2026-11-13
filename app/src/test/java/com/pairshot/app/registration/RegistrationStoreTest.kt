package com.pairshot.app.registration

import com.pairshot.app.data.PHONE_ONLY_MODE
import com.pairshot.app.data.PhonePhoto
import com.pairshot.app.data.RemotePhoto
import com.pairshot.app.data.SessionStore
import com.pairshot.app.data.ShotRecord
import com.pairshot.app.data.sha256
import com.pairshot.app.ptp.PtpObjectInfo
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RegistrationStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun chunkedTransferBecomesOneDurableResultOnlyAfterAllImagesCommit() {
        val directory = temp.newFolder()
        val store = RegistrationStore(directory)
        val metadata = metadata()
        val id = store.begin(metadata)
        metadata.getJSONObject("metrics").put("output_wh", JSONArray(listOf(99, 99)))
        assertTrue(store.history().isEmpty())
        val pixels = png()
        for (name in RegistrationStore.IMAGE_NAMES) {
            pixels.toList().chunked(7).forEach { part -> store.append(id, name, encode(part.toByteArray())) }
        }
        assertTrue(store.history().isEmpty())
        val saved = store.commit(id)
        assertEquals(4, saved.width)
        assertEquals(3, saved.height)
        assertEquals("主摄.jpg → 长焦.jpg", saved.label)
        assertEquals(RegistrationStore.IMAGE_NAMES.toSet(), saved.images.keys)
        assertFalse(File(directory, "pending/$id").exists())
        val reopened = RegistrationStore(directory)
        val recovered = reopened.load(id)
        assertEquals(saved.id, recovered.id)
        assertEquals(saved.createdAt, recovered.createdAt)
        assertEquals(saved.images, recovered.images)
        assertEquals("color-v2", recovered.metadata.getJSONObject("metrics").getString("algorithm_version"))
        assertEquals(id, reopened.history().single().id)
        assertArrayEquals(pixels, reopened.file(id, "aligned.png").readBytes())
        assertEquals(sha256(reopened.file(id, "aligned.png")), saved.images.getValue("aligned.png").sha256)
    }

    @Test fun incompleteTransfersCannotPublishAndCancellationOnlyRemovesOwnedPendingData() {
        val directory = temp.newFolder()
        val store = RegistrationStore(directory)
        val id = store.begin(metadata())
        store.append(id, "aligned.png", encode(png()))
        assertThrows(IllegalStateException::class.java) { store.commit(id) }
        assertTrue(store.history().isEmpty())
        val other = RegistrationStore(directory)
        other.abandon(id)
        other.abandonAll()
        assertTrue(File(directory, "pending/$id/aligned.png").isFile)
        store.abandonAll()
        assertFalse(File(directory, "pending/$id").exists())
        assertThrows(IllegalStateException::class.java) { store.append(id, "aligned.png", encode(png())) }
        val completed = completed(store)
        store.abandon(completed.id)
        assertEquals(completed.id, store.load(completed.id).id)
    }

    @Test fun traversalUnknownFilesAndPostCommitWritesAreRejected() {
        val store = RegistrationStore(temp.newFolder())
        val id = store.begin(metadata())
        assertThrows(IllegalArgumentException::class.java) { store.append("../outside", "aligned.png", encode(png())) }
        assertThrows(IllegalArgumentException::class.java) { store.append(id, "../aligned.png", encode(png())) }
        assertThrows(IllegalArgumentException::class.java) { store.append(id, "settings.json", encode(png())) }
        assertThrows(IllegalArgumentException::class.java) { store.load("../outside") }
        assertThrows(IllegalArgumentException::class.java) { store.file(id, "../metadata.json") }
        store.abandon(id)
        val completed = completed(store)
        assertThrows(IllegalStateException::class.java) { store.append(completed.id, "aligned.png", encode(png())) }
        assertThrows(IllegalStateException::class.java) { store.commit(completed.id) }
    }

    @Test fun inputMetadataAndTransferChunkLimitsAreEnforcedBeforeWriting() {
        val store = RegistrationStore(temp.newFolder())
        assertThrows(IllegalArgumentException::class.java) { store.begin(JSONObject()) }
        assertThrows(IllegalArgumentException::class.java) { store.begin(metadata(1921, 3)) }
        assertThrows(IllegalArgumentException::class.java) { store.begin(metadata(0, 3)) }
        assertThrows(IllegalArgumentException::class.java) { store.begin(metadata().put("extra", "x".repeat(RegistrationStore.MAX_METADATA_BYTES))) }
        val id = store.begin(metadata())
        assertThrows(IllegalArgumentException::class.java) { store.begin(metadata()) }
        assertThrows(IllegalArgumentException::class.java) { store.append(id, "aligned.png", "A".repeat(RegistrationStore.MAX_CHUNK_CHARS + 4)) }
        assertThrows(IllegalArgumentException::class.java) { store.append(id, "aligned.png", "!invalid-base64!") }
        assertThrows(IllegalArgumentException::class.java) { store.append(id, "aligned.png", "") }
        RegistrationStore.IMAGE_NAMES.forEach { store.append(id, it, encode(png())) }
        assertEquals(id, store.commit(id).id)
    }

    @Test fun mismatchedDimensionsBadCrcAndTruncatedPngNeverBecomeResults() {
        val store = RegistrationStore(temp.newFolder())
        for (badImage in listOf(png(5, 3), png().copyOf(24), png().also { it[it.lastIndex - 5] = (it[it.lastIndex - 5].toInt() xor 1).toByte() })) {
            val id = store.begin(metadata())
            RegistrationStore.IMAGE_NAMES.forEach { name -> store.append(id, name, encode(if (name == "aligned.png") badImage else png())) }
            assertThrows(Exception::class.java) { store.commit(id) }
            assertTrue(store.history().isEmpty())
            store.abandon(id)
        }
    }

    @Test fun exportContainsAllStagesMetricsAndManifestWithoutChangingStoredImages() {
        val store = RegistrationStore(temp.newFolder())
        val record = completed(store)
        val destination = File(temp.root, "export/result.zip")
        store.archive(record.id, destination)
        ZipFile(destination).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            assertEquals(RegistrationStore.IMAGE_NAMES.toSet() + setOf("metrics.json", "metadata.json", "README.txt"), names)
            assertArrayEquals(png(), zip.getInputStream(zip.getEntry("aligned.png")).readBytes())
            val metrics = JSONObject(zip.getInputStream(zip.getEntry("metrics.json")).bufferedReader().readText())
            assertEquals("color-v2", metrics.getString("algorithm_version"))
            val manifest = JSONObject(zip.getInputStream(zip.getEntry("metadata.json")).bufferedReader().readText())
            assertEquals(record.id, manifest.getString("id"))
            assertEquals(record.images.getValue("aligned.png").sha256,
                manifest.getJSONObject("images").getJSONObject("aligned.png").getString("sha256"))
        }
        assertArrayEquals(png(), store.file(record.id, "aligned.png").readBytes())
        assertFalse(File(destination.path + ".part").exists())
        assertThrows(IllegalArgumentException::class.java) { store.archive(record.id, store.file(record.id, "aligned.png")) }
    }

    @Test fun tamperedPixelDataWithValidPngCrcFailsStoredHashAndCannotBeExported() {
        val store = RegistrationStore(temp.newFolder())
        val record = completed(store)
        val target = store.file(record.id, "aligned.png")
        val replacement = png(4, 3, 53)
        assertEquals(target.length(), replacement.size.toLong())
        target.writeBytes(replacement)
        assertThrows(IllegalStateException::class.java) { store.load(record.id) }
        assertThrows(IllegalStateException::class.java) { store.file(record.id, "aligned.png") }
        val export = File(temp.root, "tampered.zip")
        assertThrows(IllegalStateException::class.java) { store.archive(record.id, export) }
        assertFalse(export.exists())
    }

    @Test fun historySkipsMissingImagesCorruptManifestsAndUncommittedDirectories() {
        val directory = temp.newFolder()
        val store = RegistrationStore(directory)
        val complete = completed(store)
        val missing = completed(store)
        store.file(missing.id, "mask.png").delete()
        val invalid = completed(store)
        store.file(invalid.id, "metadata.json").writeText("not json")
        store.begin(metadata())
        assertEquals(listOf(complete.id), RegistrationStore(directory).history().map { it.id })
    }

    @Test fun photoLibraryReadsImportedImagesAcrossSessionsWithoutChangingCaptureSelection() {
        val sessions = SessionStore(temp.newFolder())
        fun phone(path: String, lens: String): PhonePhoto {
            val file = sessions.file(path).apply { parentFile!!.mkdirs(); writeBytes(png()) }
            return PhonePhoto(lens, "镜头 $lens", path, file.length(), sha256(file), 1, "UNKNOWN", 1, 1)
        }
        val firstPhone = phone("phone/first/0.jpg", "0")
        sessions.add(ShotRecord(id = "first", mode = PHONE_ONLY_MODE, triggerElapsedNs = 1, lenses = listOf("0"), phone = listOf(firstPhone)))
        val firstSession = sessions.current
        sessions.newSession(null)
        val secondPhone = phone("phone/second/1.png", "1")
        fun remote(name: String, handle: Long, imported: Boolean): RemotePhoto {
            val path = if (imported) "camera/second/$name" else ""
            if (imported) sessions.file(path).apply { parentFile!!.mkdirs(); writeBytes(png()) }
            return RemotePhoto(PtpObjectInfo(handle, 1, 0x3801, png().size.toLong(), 0, name, "20261113T120000"), "camera", path)
        }
        sessions.add(ShotRecord(id = "second", mode = "PHONE_LEADS", triggerElapsedNs = 2, lenses = listOf("1"), phone = listOf(secondPhone),
            camera = listOf(remote("imported.JPG", 1, true), remote("raw.NEF", 2, true), remote("missing.JPG", 3, false)), awaitingCamera = false))
        val activeId = sessions.current.id
        val all = listRegistrationPhotos(sessions)
        assertEquals(3, all.size)
        assertEquals(setOf("0.jpg", "1.png", "imported.JPG"), all.map { it.file.name }.toSet())
        assertTrue(all.any { it.file == sessions.file(firstSession, firstPhone.path) })
        assertTrue(all.all { it.label.contains("第 1 组") && !it.id.contains('/') })
        assertEquals(activeId, sessions.current.id)
        assertEquals(all.map { it.id }, listRegistrationPhotos(sessions).map { it.id })
        assertTrue(sessions.file(firstSession, firstPhone.path).isFile)
    }

    private fun completed(store: RegistrationStore): RegistrationRecord {
        val id = store.begin(metadata())
        RegistrationStore.IMAGE_NAMES.forEach { store.append(id, it, encode(png())) }
        return store.commit(id)
    }

    private fun metadata(width: Int = 4, height: Int = 3) = JSONObject()
        .put("metrics", JSONObject().put("algorithm_version", "color-v2").put("output_wh", JSONArray(listOf(width, height))))
        .put("inputs", JSONObject().put("main", JSONObject().put("name", "主摄.jpg")).put("tele", JSONObject().put("name", "长焦.jpg")))
        .put("options", JSONObject().put("maxSide", 1024).put("localColor", true))

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /** Small real RGB PNGs, including valid zlib data and all chunk CRCs. */
    private fun png(width: Int = 4, height: Int = 3, value: Int = 52): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { file ->
            file.write(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10))
            fun chunk(type: String, data: ByteArray) {
                val typeBytes = type.toByteArray(Charsets.US_ASCII)
                file.writeInt(data.size); file.write(typeBytes); file.write(data)
                file.writeInt(CRC32().apply { update(typeBytes); update(data) }.value.toInt())
            }
            val header = ByteArrayOutputStream().apply {
                DataOutputStream(this).use { it.writeInt(width); it.writeInt(height); it.write(byteArrayOf(8, 2, 0, 0, 0)) }
            }.toByteArray()
            val compressed = ByteArrayOutputStream().apply {
                DeflaterOutputStream(this).use { data -> repeat(height) { data.write(0); data.write(ByteArray(width * 3) { value.toByte() }) } }
            }.toByteArray()
            chunk("IHDR", header); chunk("IDAT", compressed); chunk("IEND", byteArrayOf())
        }
        return output.toByteArray()
    }
}
