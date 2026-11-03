package com.pairshot.app.ptp

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class PtpProtocolTest {
    @Test fun commandPreservesUnsignedTransactionAndParameters() {
        val bytes = PtpCodec.command(PtpCodec.INITIATE_CAPTURE, 0xffffffffL, 0, 0)
        val decoded = PtpCodec.decode(bytes)
        assertEquals(20, bytes.size)
        assertEquals(PtpCodec.COMMAND, decoded.type)
        assertEquals(PtpCodec.INITIATE_CAPTURE, decoded.code)
        assertEquals(0xffffffffL, decoded.transaction)
        assertArrayEquals(ByteArray(8), decoded.payload)
    }

    @Test fun invalidContainerLengthsAreRejectedBeforeAllocation() {
        assertThrows(IllegalArgumentException::class.java) { PtpCodec.length(ByteArray(11)) }
        val huge = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(-1).array()
        assertThrows(IllegalArgumentException::class.java) { PtpCodec.length(huge) }
        val truncated = PtpCodec.command(PtpCodec.OPEN_SESSION, 0, 1).copyOf(12)
        assertThrows(IllegalArgumentException::class.java) { PtpCodec.decode(truncated) }
    }

    @Test fun deviceInfoReadsUnicodeAndCaptureCapability() {
        val info = PtpDeviceInfo.decode(deviceInfo(capture = true))
        assertEquals("示例相机", info.manufacturer)
        assertEquals("PTP Camera", info.model)
        assertTrue(info.supportsCapture)
        assertEquals(0xffffffffL, info.vendorExtensionId)
    }

    @Test fun malformedDeviceInfoIsRejected() {
        val bytes = deviceInfo(capture = true)
        assertThrows(IllegalArgumentException::class.java) { PtpDeviceInfo.decode(bytes.copyOf(10)) }
        // String byte count includes its required UTF-16 terminator.
        bytes[bytes.lastIndex] = 1
        assertThrows(IllegalArgumentException::class.java) { PtpDeviceInfo.decode(bytes) }
    }

    @Test fun opensSessionAndSendsStandardCaptureWithCameraDefaults() {
        val transport = FakeTransport(openResponses(capture = true) + response(transaction = 1))
        val session = PtpSession(transport)
        assertTrue(session.open().supportsCapture)
        session.capture()
        val commands = transport.commands.map(PtpCodec::decode)
        assertEquals(listOf(PtpCodec.GET_DEVICE_INFO, PtpCodec.OPEN_SESSION, PtpCodec.INITIATE_CAPTURE), commands.map { it.code })
        assertEquals(listOf(0L, 0L, 1L), commands.map { it.transaction })
        assertArrayEquals(ByteArray(8), commands.last().payload)
    }

    @Test fun unsupportedCameraCannotReceiveCaptureCommand() {
        val transport = FakeTransport(openResponses(capture = false))
        val session = PtpSession(transport)
        session.open()
        assertThrows(IllegalStateException::class.java) { session.capture() }
        assertEquals(2, transport.commands.size)
    }

    @Test fun cameraBusyIsReportedAsFailure() {
        val transport = FakeTransport(openResponses(capture = true) + response(transaction = 1, code = 0x2019))
        val session = PtpSession(transport)
        session.open()
        val error = assertThrows(PtpResponseException::class.java) { session.capture() }
        assertEquals(0x2019, error.responseCode)
    }

    @Test fun unexpectedTransactionIsRejected() {
        val transport = FakeTransport(listOf(response(transaction = 42)))
        assertThrows(IllegalArgumentException::class.java) { PtpSession(transport).open() }
    }

    @Test fun closeReleasesUsbEvenWhenCameraRejectsClose() {
        val transport = FakeTransport(openResponses(capture = true) + response(transaction = 1, code = 0x2002))
        val session = PtpSession(transport)
        session.open()
        assertThrows(PtpResponseException::class.java) { session.close() }
        assertTrue(transport.closed)
        assertThrows(IllegalStateException::class.java) { session.capture() }
    }

    @Test fun abortReleasesUsbWithoutWaitingForResponse() {
        val transport = FakeTransport(openResponses(capture = true))
        val session = PtpSession(transport)
        session.open()
        session.abort()
        assertTrue(transport.closed)
        assertEquals(2, transport.commands.size)
    }

    @Test fun nikonRouteRequiresExactZ6ModelVendorAndAdvertisedOperation() {
        val info = PtpDeviceInfo("Nikon", "NIKON Z 6", "1.0", setOf(PtpCodec.NIKON_CAPTURE_TO_MEDIA), PtpCodec.NIKON_VENDOR_ID)
        assertEquals(CaptureMode.NIKON_Z6, info.captureMode)
        assertEquals(CaptureMode.NIKON_Z6, info.copy(model = "Z6").captureMode)
        assertEquals(CaptureMode.UNSUPPORTED, info.copy(model = "NIKON Z 6II").captureMode)
        assertEquals(CaptureMode.UNSUPPORTED, info.copy(vendorExtensionId = 1).captureMode)
        assertEquals(CaptureMode.UNSUPPORTED, info.copy(operations = emptySet()).captureMode)
    }

    @Test fun nikonCaptureUsesRemoteModeReadinessAndCardTargetWithoutChangingFocus() {
        val transport = FakeTransport(nikonOpenResponses(standardCapture = true) + (1L..3L).map { response(it) })
        val session = PtpSession(transport)
        assertEquals(CaptureMode.NIKON_Z6, session.open().captureMode)
        session.capture()
        val commands = transport.commands.drop(2).map(PtpCodec::decode)
        assertEquals(listOf(PtpCodec.NIKON_CHANGE_CAMERA_MODE, PtpCodec.NIKON_DEVICE_READY, PtpCodec.NIKON_CAPTURE_TO_MEDIA), commands.map { it.code })
        assertEquals(listOf(1L, 2L, 3L), commands.map { it.transaction })
        val parameters = ByteBuffer.wrap(commands.last().payload).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0xffffffffL, parameters.int.toLong() and 0xffffffffL)
        assertEquals(0, parameters.int)
    }

    @Test fun nikonDoesNotSendOptionalOperationsUnlessAdvertised() {
        val transport = FakeTransport(nikonOpenResponses(optionalOperations = false) + response(1))
        val session = PtpSession(transport)
        session.open()
        session.capture()
        assertEquals(PtpCodec.NIKON_CAPTURE_TO_MEDIA, PtpCodec.decode(transport.commands.last()).code)
        assertEquals(3, transport.commands.size)
    }

    @Test fun nikonBusyReadinessDoesNotFireOrAutomaticallyRetryShutter() {
        val transport = FakeTransport(nikonOpenResponses() + listOf(response(1), response(2, 0x2019), response(3), response(4)))
        val session = PtpSession(transport)
        session.open()
        assertThrows(PtpResponseException::class.java) { session.capture() }
        assertFalse(transport.commands.map(PtpCodec::decode).any { it.code == PtpCodec.NIKON_CAPTURE_TO_MEDIA })
        session.capture() // A separate, explicit user retry.
        val operations = transport.commands.map(PtpCodec::decode).map { it.code }
        assertEquals(1, operations.count { it == PtpCodec.NIKON_CHANGE_CAMERA_MODE })
        assertEquals(2, operations.count { it == PtpCodec.NIKON_DEVICE_READY })
        assertEquals(1, operations.count { it == PtpCodec.NIKON_CAPTURE_TO_MEDIA })
    }

    @Test fun normalDisconnectRestoresNikonCameraModeBeforeClosingSession() {
        val transport = FakeTransport(nikonOpenResponses() + (1L..5L).map { response(it) })
        val session = PtpSession(transport)
        session.open()
        session.capture()
        session.close()
        val lastCommands = transport.commands.takeLast(2).map(PtpCodec::decode)
        assertEquals(listOf(PtpCodec.NIKON_CHANGE_CAMERA_MODE, PtpCodec.CLOSE_SESSION), lastCommands.map { it.code })
        assertArrayEquals(ByteArray(4), lastCommands.first().payload)
        assertTrue(transport.closed)
    }

    @Test fun nikonVendorOperationIsNeverSentToDifferentVendor() {
        val data = deviceInfo(false, model = "Z6", vendor = 1, extraOperations = listOf(PtpCodec.NIKON_CAPTURE_TO_MEDIA))
        val transport = FakeTransport(listOf(PtpContainer(PtpCodec.DATA, PtpCodec.GET_DEVICE_INFO, 0, data), response(0), response(0)))
        val session = PtpSession(transport)
        session.open()
        assertThrows(IllegalStateException::class.java) { session.capture() }
        assertEquals(2, transport.commands.size)
    }

    @Test fun nikonKnownModeChangeRejectionStillPermitsCaptureAndDoesNotRestoreMode() {
        val transport = FakeTransport(nikonOpenResponses() + listOf(response(1, 0xa003), response(2), response(3), response(4)))
        val session = PtpSession(transport)
        session.open()
        session.capture()
        session.close()
        val commands = transport.commands.map(PtpCodec::decode)
        assertEquals(1, commands.count { it.code == PtpCodec.NIKON_CAPTURE_TO_MEDIA })
        assertEquals(1, commands.count { it.code == PtpCodec.NIKON_CHANGE_CAMERA_MODE })
        assertEquals(PtpCodec.CLOSE_SESSION, commands.last().code)
        assertTrue(transport.closed)
    }

    @Test fun nikonOtherModeChangeErrorsDoNotTriggerCapture() {
        val transport = FakeTransport(nikonOpenResponses() + response(1, 0x200f))
        val session = PtpSession(transport)
        session.open()
        assertThrows(PtpResponseException::class.java) { session.capture() }
        assertFalse(transport.commands.map(PtpCodec::decode).any { it.code == PtpCodec.NIKON_CAPTURE_TO_MEDIA })
    }

    @Test fun leftoverSessionIsClosedAndReopenedOnceBeforeAnyShutter() {
        val transport = FakeTransport(openResponses(true).take(2) + listOf(
            response(0, PtpCodec.SESSION_ALREADY_OPEN), response(1), response(0), response(1)))
        val session = PtpSession(transport)
        assertTrue(session.open().supportsCapture)
        assertTrue(session.isOpen)
        assertFalse(transport.commands.map(PtpCodec::decode).any { it.code == PtpCodec.INITIATE_CAPTURE })
        assertEquals(1L, session.capture())
        val commands = transport.commands.map(PtpCodec::decode)
        assertEquals(listOf(PtpCodec.GET_DEVICE_INFO, PtpCodec.OPEN_SESSION, PtpCodec.CLOSE_SESSION,
            PtpCodec.OPEN_SESSION, PtpCodec.INITIATE_CAPTURE), commands.map { it.code })
        assertEquals(listOf(0L, 0L, 1L, 0L, 1L), commands.map { it.transaction })
    }

    @Test fun recoveryToleratesSessionAlreadyClosedByTheCamera() {
        val transport = FakeTransport(openResponses(true).take(2) + listOf(
            response(0, PtpCodec.SESSION_ALREADY_OPEN), response(1, PtpCodec.SESSION_NOT_OPEN), response(0)))
        val session = PtpSession(transport)
        session.open()
        assertTrue(session.isOpen)
        assertEquals(2, transport.commands.map(PtpCodec::decode).count { it.code == PtpCodec.OPEN_SESSION })
    }

    @Test fun repeatedOpenConflictStopsAndReleasesUsbInsteadOfPretendingConnected() {
        val transport = FakeTransport(openResponses(true).take(2) + listOf(
            response(0, PtpCodec.SESSION_ALREADY_OPEN), response(1), response(0, PtpCodec.SESSION_ALREADY_OPEN)))
        val session = PtpSession(transport)
        val error = assertThrows(PtpResponseException::class.java) { session.open() }
        assertEquals(PtpCodec.OPEN_SESSION, error.operationCode)
        assertFalse(session.isOpen)
        assertTrue(transport.closed)
        assertEquals(2, transport.commands.map(PtpCodec::decode).count { it.code == PtpCodec.OPEN_SESSION })
        assertThrows(IllegalStateException::class.java) { session.capture() }
    }

    @Test fun otherOpenErrorsDoNotCloseOrRetryAnUnrelatedSession() {
        val transport = FakeTransport(openResponses(true).take(2) + response(0, 0x2019))
        val session = PtpSession(transport)
        assertThrows(PtpResponseException::class.java) { session.open() }
        assertEquals(2, transport.commands.size)
        assertTrue(transport.closed)
    }

    @Test fun normalArmingReusesTheConnectedSession() {
        val transport = FakeTransport(openResponses(true) + handles(1, 42, 43) + response(2))
        val session = PtpSession(transport)
        session.open()
        assertEquals(setOf(42L, 43L), session.prepareSync())
        assertEquals(2L, session.capture())
        assertEquals(1, transport.commands.map(PtpCodec::decode).count { it.code == PtpCodec.OPEN_SESSION })
    }

    @Test fun armingRecoversStaleSessionAndResetsTransactionIdsWithoutTakingPictures() {
        for (code in listOf(PtpCodec.SESSION_ALREADY_OPEN, PtpCodec.SESSION_NOT_OPEN)) {
            val messages = mutableListOf<String>()
            val transport = FakeTransport(openResponses(true) + listOf(response(1, code), response(2), response(0)) + handles(1, 99))
            val session = PtpSession(transport, messages::add)
            session.open()
            assertEquals(setOf(99L), session.prepareSync())
            val commands = transport.commands.map(PtpCodec::decode)
            assertEquals(listOf(PtpCodec.GET_DEVICE_INFO, PtpCodec.OPEN_SESSION, PtpCodec.GET_OBJECT_HANDLES,
                PtpCodec.CLOSE_SESSION, PtpCodec.OPEN_SESSION, PtpCodec.GET_OBJECT_HANDLES), commands.map { it.code })
            assertEquals(listOf(0L, 0L, 1L, 2L, 0L, 1L), commands.map { it.transaction })
            assertTrue(messages.any { it.contains("重建会话") })
            assertTrue(session.isOpen)
        }
    }

    @Test fun armingDoesNotSwallowNikonEventSessionErrors() {
        val data = deviceInfo(true, model = "NIKON Z 6", vendor = PtpCodec.NIKON_VENDOR_ID,
            extraOperations = listOf(PtpCodec.NIKON_GET_EVENT))
        val transport = FakeTransport(listOf(PtpContainer(PtpCodec.DATA, PtpCodec.GET_DEVICE_INFO, 0, data), response(0), response(0),
            response(1, PtpCodec.SESSION_ALREADY_OPEN), response(2), response(0),
            PtpContainer(PtpCodec.DATA, PtpCodec.NIKON_GET_EVENT, 1, byteArrayOf(0, 0)), response(1)) + handles(2, 8))
        val session = PtpSession(transport)
        session.open()
        assertEquals(setOf(8L), session.prepareSync())
        assertEquals(2, transport.commands.map(PtpCodec::decode).count { it.code == PtpCodec.NIKON_GET_EVENT })
    }

    @Test fun unsupportedOptionalEventQueryFallsBackToFilesWithoutReopening() {
        val data = deviceInfo(true, vendor = PtpCodec.NIKON_VENDOR_ID, extraOperations = listOf(PtpCodec.NIKON_GET_EVENT))
        val transport = FakeTransport(listOf(PtpContainer(PtpCodec.DATA, PtpCodec.GET_DEVICE_INFO, 0, data), response(0), response(0),
            response(1, 0x2005)) + handles(2, 8))
        val session = PtpSession(transport)
        session.open()
        assertEquals(setOf(8L), session.prepareSync())
        assertEquals(1, transport.commands.map(PtpCodec::decode).count { it.code == PtpCodec.OPEN_SESSION })
    }

    @Test fun busyArmingReportsTheActualOperationWithoutResettingSession() {
        val transport = FakeTransport(openResponses(true) + response(1, 0x2019))
        val session = PtpSession(transport)
        session.open()
        val error = assertThrows(PtpResponseException::class.java) { session.prepareSync() }
        assertEquals(PtpCodec.GET_OBJECT_HANDLES, error.operationCode)
        assertEquals(1L, error.transactionId)
        assertTrue(error.message!!.contains("GetObjectHandles 0x1007"))
        assertTrue(error.message!!.contains("0x2019"))
        assertFalse(error.message!!.contains("会话"))
        assertTrue(session.isOpen)
        assertEquals(1, transport.commands.map(PtpCodec::decode).count { it.code == PtpCodec.OPEN_SESSION })
    }

    @Test fun persistentArmingConflictStopsAfterOneRecovery() {
        val transport = FakeTransport(openResponses(true) + listOf(response(1, PtpCodec.SESSION_ALREADY_OPEN), response(2), response(0),
            response(1, PtpCodec.SESSION_ALREADY_OPEN)))
        val session = PtpSession(transport)
        session.open()
        val error = assertThrows(PtpResponseException::class.java) { session.prepareSync() }
        assertEquals(PtpCodec.GET_OBJECT_HANDLES, error.operationCode)
        assertFalse(error.message!!.contains("重新插拔"))
        assertFalse(session.isOpen)
        assertTrue(transport.closed)
        assertEquals(2, transport.commands.map(PtpCodec::decode).count { it.code == PtpCodec.OPEN_SESSION })
        assertFalse(transport.commands.map(PtpCodec::decode).any { it.code == PtpCodec.INITIATE_CAPTURE })
    }

    @Test fun lifecycleCleanupSendsCloseSessionBeforeReleasingUsb() {
        val transport = FakeTransport(openResponses(true) + response(1))
        val session = PtpSession(transport)
        session.open()
        com.pairshot.app.releasePtpSession(session).get(2, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(transport.closed)
        assertFalse(session.isOpen)
        assertEquals(PtpCodec.CLOSE_SESSION, PtpCodec.decode(transport.commands.last()).code)
    }

    private fun handles(transaction: Long, vararg values: Long): List<PtpContainer> {
        val bytes = ByteBuffer.allocate(4 + values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(values.size); values.forEach { putInt(it.toInt()) }
        }.array()
        return listOf(PtpContainer(PtpCodec.DATA, PtpCodec.GET_OBJECT_HANDLES, transaction, bytes), response(transaction))
    }

    private fun response(transaction: Long, code: Int = PtpCodec.OK) =
        PtpContainer(PtpCodec.RESPONSE, code, transaction, ByteArray(0))

    private fun openResponses(capture: Boolean) = listOf(
        PtpContainer(PtpCodec.DATA, PtpCodec.GET_DEVICE_INFO, 0, deviceInfo(capture)),
        response(0),
        response(0),
    )

    private fun nikonOpenResponses(optionalOperations: Boolean = true, standardCapture: Boolean = false): List<PtpContainer> {
        val operations = listOf(PtpCodec.NIKON_CAPTURE_TO_MEDIA) +
            if (optionalOperations) listOf(PtpCodec.NIKON_CHANGE_CAMERA_MODE, PtpCodec.NIKON_DEVICE_READY) else emptyList()
        return listOf(
            PtpContainer(PtpCodec.DATA, PtpCodec.GET_DEVICE_INFO, 0,
                deviceInfo(standardCapture, "NIKON Z 6", PtpCodec.NIKON_VENDOR_ID, operations)),
            response(0), response(0),
        )
    }

    private fun deviceInfo(
        capture: Boolean,
        model: String = "PTP Camera",
        vendor: Long = 0xffffffffL,
        extraOperations: List<Int> = emptyList(),
    ): ByteArray {
        val output = ByteArrayOutputStream()
        fun u16(value: Int) { output.write(byteArrayOf(value.toByte(), (value ushr 8).toByte())) }
        fun u32(value: Int) { output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()) }
        fun string(value: String) {
            output.write(value.length + 1)
            output.write(value.toByteArray(Charsets.UTF_16LE))
            u16(0)
        }
        u16(100); u32(vendor.toInt()); u16(100); string(""); u16(0)
        val operations = listOf(PtpCodec.GET_DEVICE_INFO, PtpCodec.OPEN_SESSION, PtpCodec.CLOSE_SESSION) + extraOperations +
            if (capture) listOf(PtpCodec.INITIATE_CAPTURE) else emptyList()
        u32(operations.size); operations.forEach(::u16)
        repeat(4) { u32(0) }
        string("示例相机"); string(model); string("1.0"); string("not-retained")
        return output.toByteArray()
    }

    private class FakeTransport(responses: List<PtpContainer>) : PtpTransport {
        val commands = mutableListOf<ByteArray>()
        private val incoming = ArrayDeque(responses)
        var closed = false
        override fun send(command: ByteArray) { commands += command }
        override fun receive(): PtpContainer = incoming.removeFirst()
        override fun close() { closed = true }
    }
}
