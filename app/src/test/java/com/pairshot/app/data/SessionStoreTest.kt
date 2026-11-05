package com.pairshot.app.data

import com.pairshot.app.ptp.PtpDeviceInfo
import com.pairshot.app.ptp.PtpObjectInfo
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun archiveContainsExactPhoneRawAndJpegFilesWithOnePairingManifest() {
        val store = completeStore()
        val zip = File(temp.root, "photos.zip")
        val snapshot = store.archive(zip, false)
        ZipFile(zip).use { archive ->
            val names = archive.entries().asSequence().map { it.name }.toSet()
            assertEquals(setOf("manifest.json", "README.txt", "phone/shot/lens.jpg", "camera/shot/12_DSC_0021.NEF", "camera/shot/13_DSC_0021.JPG"), names)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), archive.getInputStream(archive.getEntry("camera/shot/12_DSC_0021.NEF")).readBytes())
            val manifest = JSONObject(archive.getInputStream(archive.getEntry("manifest.json")).bufferedReader().readText())
            assertEquals(snapshot.id, manifest.getString("id"))
            val shots = manifest.getJSONArray("shots")
            assertEquals(1, shots.length())
            assertEquals(2, shots.getJSONObject(0).getJSONArray("camera").length())
            assertTrue(shots.getJSONObject(0).getBoolean("complete"))
        }
        assertTrue(store.file("camera/shot/12_DSC_0021.NEF").exists())
    }
    @Test fun incompleteRowsRequireExplicitConsentAndKeepMissingMetadata() {
        val store = SessionStore(temp.newFolder("incomplete"))
        store.add(ShotRecord(id = "pending", mode = "PHONE_LEADS", triggerElapsedNs = 123, lenses = listOf("0"), errors = listOf("相机忙"), awaitingCamera = false))
        val zip = File(temp.root, "incomplete.zip")
        assertThrows(IllegalStateException::class.java) { store.archive(zip, false) }
        assertFalse(zip.exists())
        store.archive(zip, true)
        ZipFile(zip).use {
            val manifest = JSONObject(it.getInputStream(it.getEntry("manifest.json")).bufferedReader().readText())
            assertFalse(manifest.getJSONArray("shots").getJSONObject(0).getBoolean("complete"))
            assertEquals("相机忙", manifest.getJSONArray("shots").getJSONObject(0).getJSONArray("errors").getString(0))
        }
    }
    @Test fun corruptFileCannotPassExportEvenWithIncompleteExportEnabled() {
        val store = completeStore()
        store.file("phone/shot/lens.jpg").writeBytes(byteArrayOf(9, 9, 9))
        val zip = File(temp.root, "corrupt.zip")
        assertThrows(IllegalStateException::class.java) { store.archive(zip, true) }
        assertFalse(zip.exists())
    }
    @Test fun processRestartRestoresRecordsAndMarksInFlightShotIncomplete() {
        val directory = temp.newFolder("recovery")
        val store = SessionStore(directory)
        store.add(ShotRecord(id = "inflight", mode = "PHONE_LEADS", triggerElapsedNs = 123, lenses = listOf("0")))
        val reopened = SessionStore(directory)
        assertEquals(store.current.id, reopened.current.id)
        val recovered = reopened.current.shots.single()
        assertFalse(recovered.awaitingCamera)
        assertFalse(recovered.complete)
        assertTrue(recovered.errors.isNotEmpty())
    }
    @Test fun traversalAndCrossCameraBindingAreRejected() {
        val store = completeStore()
        assertThrows(IllegalArgumentException::class.java) { store.file("../outside.jpg") }
        val other = PtpDeviceInfo("Nikon", "Z6", "1", emptySet(), 10, identity = "different")
        assertThrows(IllegalStateException::class.java) { store.bind(other) }
    }
    @Test fun newSessionRetainsPriorHistoryAndCanBeReopened() {
        val store = completeStore()
        val first = store.current
        store.newSession(null)
        assertNotEquals(first.id, store.current.id)
        assertEquals(2, store.history().size)
        store.select(first.id)
        assertEquals(first.shots, store.current.shots)
    }
    @Test fun phoneOnlyArchiveIsCompleteWithoutCameraAndKeepsFocalMetadata() {
        val store = SessionStore(temp.newFolder())
        val photos = listOf(phonePhoto(store, "0", 1f), phonePhoto(store, "1", 2f))
        store.add(ShotRecord(id = "phone", mode = PHONE_ONLY_MODE, triggerElapsedNs = 100,
            lenses = listOf("0", "1"), phone = photos))
        assertTrue(store.current.shots.single().complete)
        assertFalse(store.current.shots.single().awaitingCamera)
        val zip = File(temp.root, "phone-only.zip")
        store.archive(zip, false)
        ZipFile(zip).use { archive ->
            val names = archive.entries().asSequence().map { it.name }.toSet()
            assertEquals(setOf("manifest.json", "README.txt", "phone/phone/0.jpg", "phone/phone/1.jpg"), names)
            val manifest = JSONObject(archive.getInputStream(archive.getEntry("manifest.json")).bufferedReader().readText())
            val shot = manifest.getJSONArray("shots").getJSONObject(0)
            assertEquals(PHONE_ONLY_MODE, shot.getString("mode"))
            assertTrue(shot.getBoolean("complete"))
            assertEquals(0, shot.getJSONArray("camera").length())
            val restored = CaptureSession.from(manifest).shots.single()
            assertEquals(photos, restored.phone)
            assertEquals(2f, restored.phone[1].requestedZoomRatio)
            assertEquals(4.2f, restored.phone[1].nominalFocalLengthMm)
        }
    }
    @Test fun missingOrDuplicatePhoneLensesCannotSatisfyCompletePhoneOnlyRecord() {
        val store = SessionStore(temp.newFolder())
        val photo = phonePhoto(store, "0", 1f)
        val shot = ShotRecord(id = "phone", mode = PHONE_ONLY_MODE, triggerElapsedNs = 100,
            lenses = listOf("0", "1"), phone = listOf(photo))
        assertFalse(shot.complete)
        assertFalse(shot.copy(phone = listOf(photo, photo)).complete)
        store.add(shot)
        assertThrows(IllegalStateException::class.java) { store.archive(File(temp.root, "missing.zip"), false) }
    }
    @Test fun phoneOnlyRecoveryPreservesFinishedPhotosAndMarksInterruptedCapture() {
        val directory = temp.newFolder()
        val store = SessionStore(directory)
        store.add(ShotRecord(id = "phone", mode = PHONE_ONLY_MODE, triggerElapsedNs = 100,
            lenses = listOf("0"), phone = listOf(phonePhoto(store, "0", 2f))))
        store.add(ShotRecord(id = "interrupted", mode = PHONE_ONLY_MODE, triggerElapsedNs = 200, lenses = listOf("1")))
        val recovered = SessionStore(directory).current.shots
        assertTrue(recovered[0].complete)
        assertTrue(recovered[0].errors.isEmpty())
        assertFalse(recovered[1].complete)
        assertFalse(recovered[1].awaitingCamera)
        assertTrue(recovered[1].errors.isNotEmpty())
    }
    @Test fun phoneOnlyBatchCanLaterBindItsFirstCameraButCannotChangeIdentity() {
        val store = SessionStore(temp.newFolder())
        store.add(ShotRecord(mode = PHONE_ONLY_MODE, triggerElapsedNs = 100, lenses = listOf("0")))
        val camera = PtpDeviceInfo("Nikon", "Z6", "1", emptySet(), 10, identity = "first")
        store.bind(camera)
        assertEquals("first", store.current.cameraIdentity)
        assertThrows(IllegalStateException::class.java) { store.bind(camera.copy(identity = "other")) }
    }
    @Test fun phoneOnlyRowsDoNotMakeMissingLinkedCameraFilesComplete() {
        val store = completeStore()
        store.add(ShotRecord(id = "phone", mode = PHONE_ONLY_MODE, triggerElapsedNs = 200,
            lenses = listOf("0"), phone = listOf(phonePhoto(store, "0", 2f))))
        store.shot("shot") { it.copy(camera = emptyList()) }
        assertTrue(store.current.shots.last().complete)
        assertFalse(store.current.shots.first().complete)
        assertThrows(IllegalStateException::class.java) { store.archive(File(temp.root, "mixed.zip"), false) }
    }
    @Test fun legacyPhotosWithoutFocalMetadataStillLoad() {
        val photo = PhonePhoto("0", "后置", "phone/shot/0.jpg", 3, "hash", 100, "UNKNOWN", 120, 160)
        val legacy = photo.json().apply { remove("nominalFocalLengthMm"); remove("requestedZoomRatio") }
        assertEquals(photo, PhonePhoto.from(legacy))
    }
    private fun phonePhoto(store: SessionStore, lens: String, zoom: Float): PhonePhoto {
        val path = "phone/phone/$lens.jpg"
        val file = store.file(path).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        return PhonePhoto(lens, "后置 $lens", path, file.length(), sha256(file), 100, "UNKNOWN", 120, 160,
            nominalFocalLengthMm = 4.2f, requestedZoomRatio = zoom)
    }
    private fun completeStore(): SessionStore {
        val store = SessionStore(temp.newFolder())
        val phonePath = "phone/shot/lens.jpg"
        val phone = store.file(phonePath).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(3, 4, 5)) }
        val rawPath = "camera/shot/12_DSC_0021.NEF"
        val raw = store.file(rawPath).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val jpegPath = "camera/shot/13_DSC_0021.JPG"
        val jpeg = store.file(jpegPath).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(6, 7)) }
        store.add(ShotRecord(id = "shot", mode = "PHONE_LEADS", triggerElapsedNs = 120, lenses = listOf("0"),
            phone = listOf(PhonePhoto("0", "后置", phonePath, phone.length(), sha256(phone), 100, "UNKNOWN", 120, 160)),
            camera = listOf(RemotePhoto(PtpObjectInfo(12, 1, 0x3000, raw.length(), 55, "DSC_0021.NEF", "20261103T120102"), "c", rawPath, sha256(raw)),
                RemotePhoto(PtpObjectInfo(13, 1, 0x3801, jpeg.length(), 55, "DSC_0021.JPG", "20261103T120102"), "c", jpegPath, sha256(jpeg))),
            awaitingCamera = false))
        return store
    }
}
