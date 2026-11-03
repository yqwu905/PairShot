package com.pairshot.app.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.pairshot.app.ptp.PtpContainer
import com.pairshot.app.ptp.PtpTransport
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

class UsbPtpTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val input: UsbEndpoint,
    private val output: UsbEndpoint,
) : PtpTransport {
    private val closed = AtomicBoolean(false)
    private val reader = com.pairshot.app.ptp.PtpInput { buffer ->
        if (closed.get()) throw IOException("相机连接已关闭")
        connection.bulkTransfer(input, buffer, buffer.size, TIMEOUT)
    }

    override fun send(command: ByteArray) {
        if (closed.get() || connection.bulkTransfer(output, command, command.size, TIMEOUT) != command.size) {
            throw IOException("发送失败，请检查数据线及相机连接")
        }
    }

    override fun receive(): PtpContainer = reader.receive()
    override fun receiveData(code: Int, transaction: Long, output: OutputStream): Long = reader.receiveData(code, transaction, output)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            connection.releaseInterface(usbInterface)
            connection.close()
        }
    }

    companion object {
        private const val TIMEOUT = 5000

        fun isCamera(device: UsbDevice): Boolean = findInterface(device) != null

        private fun findInterface(device: UsbDevice): UsbInterface? =
            (0 until device.interfaceCount).map(device::getInterface).firstOrNull {
                it.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE &&
                    it.interfaceSubclass == 1 && it.interfaceProtocol == 1
            }

        fun open(manager: UsbManager, device: UsbDevice): UsbPtpTransport {
            check(manager.hasPermission(device)) { "尚未获得 USB 访问授权" }
            val usbInterface = requireNotNull(findInterface(device)) { "设备没有 PTP 接口" }
            val endpoints = (0 until usbInterface.endpointCount).map(usbInterface::getEndpoint)
                .filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
            val input = requireNotNull(endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_IN }) { "设备没有 USB 输入端点" }
            val output = requireNotNull(endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }) { "设备没有 USB 输出端点" }
            val connection = manager.openDevice(device) ?: throw IOException("无法打开 USB 设备")
            if (!connection.claimInterface(usbInterface, true)) {
                connection.close()
                throw IOException("无法占用相机接口，请关闭其他联机应用")
            }
            return UsbPtpTransport(connection, usbInterface, input, output)
        }
    }
}
