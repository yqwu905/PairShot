package com.pairshot.app.ptp

import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bounded USB container reader. Handles split headers and coalesced data/response packets. */
class PtpInput(private val readChunk: (ByteArray) -> Int) {
    private val buffer = ByteArray(16 * 1024)
    private var position = 0
    private var limit = 0
    private data class Header(val size: Long, val type: Int, val code: Int, val transaction: Long)

    private fun fill() {
        if (position < limit) return
        val deadline = System.nanoTime() + 5_000_000_000L
        do {
            limit = readChunk(buffer)
            position = 0
            if (limit < 0) throw IOException("USB 读取失败或相机响应超时")
            require(limit <= buffer.size) { "USB 读取长度无效" }
            if (System.nanoTime() > deadline) throw IOException("USB 数据传输停滞")
        } while (limit == 0)
    }
    private fun bytes(count: Int): ByteArray {
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            fill()
            val size = minOf(count - offset, limit - position)
            buffer.copyInto(result, offset, position, position + size)
            position += size; offset += size
        }
        return result
    }
    private fun header(): Header {
        val b = ByteBuffer.wrap(bytes(PtpCodec.HEADER_SIZE)).order(ByteOrder.LITTLE_ENDIAN)
        val size = b.int.toLong() and 0xffffffffL
        require(size >= PtpCodec.HEADER_SIZE) { "PTP 数据长度无效" }
        val type = b.short.toInt() and 0xffff
        require(type in PtpCodec.COMMAND..PtpCodec.EVENT) { "PTP 数据类型无效" }
        return Header(size, type, b.short.toInt() and 0xffff, b.int.toLong() and 0xffffffffL)
    }
    fun receive(): PtpContainer {
        val header = header()
        require(header.size <= PtpCodec.MAX_CONTAINER_SIZE) { "PTP 普通数据过大" }
        return PtpContainer(header.type, header.code, header.transaction, bytes((header.size - PtpCodec.HEADER_SIZE).toInt()))
    }
    fun receiveData(code: Int, transaction: Long, output: OutputStream): Long {
        val header = header()
        require(header.transaction == transaction) { "下载事务不匹配" }
        if (header.type == PtpCodec.RESPONSE) {
            require(header.size <= 32) { "PTP 响应长度无效" }
            bytes((header.size - PtpCodec.HEADER_SIZE).toInt())
            if (header.code != PtpCodec.OK) throw PtpResponseException(header.code, code, transaction)
            throw IOException("相机未返回照片数据")
        }
        require(header.type == PtpCodec.DATA && header.code == code) { "PTP 下载数据类型不匹配" }
        var remaining = header.size - PtpCodec.HEADER_SIZE
        while (remaining > 0) {
            fill()
            val count = minOf(remaining, (limit - position).toLong()).toInt()
            output.write(buffer, position, count)
            position += count; remaining -= count
        }
        return header.size - PtpCodec.HEADER_SIZE
    }
}
