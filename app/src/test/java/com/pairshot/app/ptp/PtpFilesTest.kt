package com.pairshot.app.ptp

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PtpFilesTest {
    @Test fun objectInfoPreservesUnsignedHandleSizeAndRawGrouping() {
        val info = PtpObjectInfo.decode(0xffffffffL, objectInfo("DSC_0021.NEF", 0xf0000000L))
        assertEquals(0xffffffffL, info.handle)
        assertEquals(0xf0000000L, info.size)
        assertEquals(55L, info.parent)
        assertEquals("20261103T120102", info.captureDate)
        assertTrue(info.isPhoto)
        val jpeg = PtpObjectInfo.decode(21, objectInfo("DSC_0021.JPG", 1024))
        assertEquals(info.groupKey, jpeg.groupKey)
        assertNotEquals(info.groupKey, jpeg.copy(parent = 56).groupKey)
        assertFalse(info.copy(filename = "DSC_0021.MOV").isPhoto)
        assertThrows(IllegalArgumentException::class.java) { PtpObjectInfo.decode(1, ByteArray(51)) }
    }
    @Test fun nikonEventPacketsAreStrictAndPreserveUnsignedParameters() {
        val bytes = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN).putShort(2)
            .putShort(0x4002).putInt(-1).putShort(0x400d).putInt(0).array()
        val events = PtpEvent.decodeNikon(bytes)
        assertEquals(listOf(0x4002, 0x400d), events.map { it.code })
        assertEquals(0xffffffffL, events.first().parameter)
        assertThrows(IllegalArgumentException::class.java) { PtpEvent.decodeNikon(bytes.copyOf(13)) }
        assertEquals(emptyList<PtpEvent>(), PtpEvent.decodeNikon(byteArrayOf(0, 0)))
    }
    @Test fun fragmentedHeadersAndCoalescedResponsesRemainAligned() {
        val data = ByteArray(48001) { (it % 251).toByte() }
        val wire = packet(2, 0x1009, 7, data) + packet(3, 0x2001, 7, byteArrayOf())
        val input = fragmented(wire, 7)
        val output = ByteArrayOutputStream()
        assertEquals(data.size.toLong(), input.receiveData(0x1009, 7, output))
        assertArrayEquals(data, output.toByteArray())
        assertEquals(PtpContainer(3, 0x2001, 7, byteArrayOf()).code, input.receive().code)
    }
    @Test fun rawLargerThanControlContainerLimitStreamsInBoundedChunks() {
        val size = PtpCodec.MAX_CONTAINER_SIZE + 101
        val payload = ByteArray(size) { 42 }
        val input = fragmented(packet(2, 0x1009, 99, payload) + packet(3, 0x2001, 99, byteArrayOf()), 16384)
        var count = 0L
        val output = object : OutputStream() {
            override fun write(b: Int) { count++ }
            override fun write(b: ByteArray, off: Int, len: Int) {
                assertTrue(len <= 16384)
                assertEquals(42.toByte(), b[off]); assertEquals(42.toByte(), b[off + len - 1])
                count += len
            }
        }
        assertEquals(size.toLong(), input.receiveData(0x1009, 99, output))
        assertEquals(size.toLong(), count)
        assertEquals(99L, input.receive().transaction)
    }
    @Test fun mismatchedTransferCannotWriteAnyOutputBytes() {
        val input = fragmented(packet(2, 0x1009, 8, ByteArray(20)), 10)
        val output = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) { input.receiveData(0x1009, 7, output) }
        assertEquals(0, output.size())
    }
    @Test fun errorResponseBeforeDataIsSurfacedWithoutLeavingUnreadBytes() {
        val input = fragmented(packet(3, 0x2009, 3, byteArrayOf()) + packet(3, 0x2001, 4, byteArrayOf()), 4096)
        val error = assertThrows(PtpResponseException::class.java) { input.receiveData(0x1009, 3, ByteArrayOutputStream()) }
        assertEquals(0x2009, error.responseCode)
        assertEquals(4L, input.receive().transaction)
    }
    @Test fun incompleteTransferFailsInsteadOfProducingSuccess() {
        val wire = packet(2, 0x1009, 7, ByteArray(4096)).dropLast(30).toByteArray()
        assertThrows(java.io.IOException::class.java) { fragmented(wire, 3072).receiveData(0x1009, 7, ByteArrayOutputStream()) }
    }
    private fun fragmented(bytes: ByteArray, max: Int): PtpInput {
        val input = ByteArrayInputStream(bytes)
        return PtpInput { target -> input.read(target, 0, minOf(max, target.size)) }
    }
    private fun packet(type: Int, code: Int, tx: Long, data: ByteArray) = ByteBuffer.allocate(12 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(12 + data.size).putShort(type.toShort()).putShort(code.toShort()).putInt(tx.toInt()).put(data).array()
    private fun objectInfo(filename: String, size: Long): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(1); putShort(0x3801); putShort(0); putInt(size.toInt()); put(ByteArray(26)); putInt(55); put(ByteArray(10))
        }.array())
        for (text in listOf(filename, "20261103T120102", "", "")) {
            output.write(text.length + 1); output.write(text.toByteArray(Charsets.UTF_16LE)); output.write(byteArrayOf(0, 0))
        }
        return output.toByteArray()
    }
}
