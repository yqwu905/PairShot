package com.pairshot.app

import android.app.Application
import android.app.PendingIntent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.pairshot.app.ptp.PtpCodec
import com.pairshot.app.ptp.PtpContainer
import com.pairshot.app.ptp.PtpSession
import com.pairshot.app.ptp.PtpTransport
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowUsbManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UsbPermissionTest {
    private lateinit var manager: UsbManager
    private lateinit var usb: ShadowUsbManager
    private lateinit var model: CameraViewModel
    private lateinit var holder: ViewModelStore
    private lateinit var camera: UsbDevice
    private var permission: PendingIntent? = null
    private var opens = 0

    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        manager = app.getSystemService(UsbManager::class.java)
        usb = shadowOf(manager)
        camera = device("/dev/bus/usb/001/002")
        usb.addOrUpdateUsbDevice(camera, false)
        model = CameraViewModel(app,
            sessionFactory = { _, requested, logger ->
                assertEquals(camera.deviceName, requested.deviceName)
                opens++
                PtpSession(InfoTransport(), logger)
            },
            permissionRequester = { _, requested, pending ->
                assertEquals(camera.deviceName, requested.deviceName)
                permission = pending
            })
        holder = ViewModelStore().apply { put("usb-permission", model) }
    }
    @After fun cleanup() { holder.clear() }

    @Test fun immutableAuthorizationCarriesDeviceAndEmptyCallbackStillConnects() {
        model.requestConnection(camera)
        assertEquals(0, opens)
        assertNotNull(model.state.value.permissionDeviceId)
        val saved = shadowOf(checkNotNull(permission)).savedIntent
        assertEquals(camera, saved.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java))
        usb.addOrUpdateUsbDevice(camera, true)
        model.onPermissionResult(null) // The system could not add mutable fill-in extras.
        waitForConnection()
        assertEquals(1, opens)
        assertNull(model.state.value.permissionDeviceId)
        assertTrue(model.state.value.connectionMessage.contains("Z 6 已连接"))
    }

    @Test fun grantedPermissionIsRecoveredAfterLostCallbackOnActivityResume() {
        model.requestConnection(camera)
        usb.addOrUpdateUsbDevice(camera, true)
        model.recheckUsbPermission()
        waitForConnection()
        assertEquals(1, opens)
    }

    @Test fun refreshAlsoContinuesAnApprovedRequestWithoutAnotherAuthorizationDialog() {
        model.requestConnection(camera)
        val first = permission
        usb.addOrUpdateUsbDevice(camera, true)
        model.refresh()
        waitForConnection()
        assertSame(first, permission)
        assertEquals(1, opens)
    }

    @Test fun deniedPermissionDoesNotAttemptPtpAndOffersVisibleRetry() {
        model.requestConnection(camera)
        model.onPermissionResult(null)
        assertEquals(0, opens)
        assertNull(model.state.value.permissionDeviceId)
        assertTrue(model.state.value.connectionMessage.contains("USB 授权未通过"))
        permission = null
        model.requestConnection(camera)
        assertNotNull(permission)
    }

    @Test fun otherDevicesCallbackCannotConsumeThePendingRequest() {
        model.requestConnection(camera)
        val other = device("/dev/bus/usb/001/003")
        usb.addOrUpdateUsbDevice(other, true)
        model.onPermissionResult(other)
        assertEquals(0, opens)
        assertNotNull(model.state.value.permissionDeviceId)
        usb.addOrUpdateUsbDevice(camera, true)
        model.onPermissionResult(camera)
        waitForConnection()
        assertEquals(1, opens)
    }

    @Test fun alreadyGrantedDeviceConnectsDirectlyAndRepeatedTapsDoNotOpenTwice() {
        usb.addOrUpdateUsbDevice(camera, true)
        model.requestConnection(camera)
        model.requestConnection(camera)
        waitForConnection()
        model.requestConnection(camera)
        assertNull(permission)
        assertEquals(1, opens)
    }

    @Test fun detachedDeviceClearsAuthorizationAndLateCallbackCannotReconnect() {
        model.requestConnection(camera)
        usb.removeUsbDevice(camera)
        model.onDetached(camera)
        model.onPermissionResult(null)
        assertEquals(0, opens)
        assertNull(model.state.value.permissionDeviceId)
        assertTrue(model.state.value.connectionMessage.contains("已拔出"))
    }

    @Test fun unsolicitedGrantBroadcastDoesNotOpenAConnection() {
        usb.addOrUpdateUsbDevice(camera, true)
        model.onPermissionResult(camera)
        assertEquals(0, opens)
        assertNull(model.state.value.info)
    }

    private fun waitForConnection() {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (model.state.value.info == null && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertNotNull(model.state.value.connectionMessage, model.state.value.info)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun device(name: String): UsbDevice {
        // Android's device builder is hidden from the compile SDK, but is available
        // in Robolectric; no real USB permission or hardware is used by these tests.
        val builderType = Class.forName("android.hardware.usb.UsbDevice\$Builder")
        val constructor = builderType.declaredConstructors.single { it.parameterCount == 16 }
        val builder = constructor.newInstance(name, 0x04b0, 0x0443, 0, 0, 0,
            "Nikon", "Z 6", "1.0", emptyArray<android.hardware.usb.UsbConfiguration>(), "TEST_SERIAL",
            false, false, false, false, false)
        val readerType = Class.forName("android.hardware.usb.IUsbSerialReader")
        val reader = Proxy.newProxyInstance(readerType.classLoader, arrayOf(readerType)) { _, method, _ ->
            when (method.name) { "asBinder" -> Binder(); "getSerial" -> "TEST_SERIAL"; else -> null }
        }
        return builderType.getMethod("build", readerType).invoke(builder, reader) as UsbDevice
    }

    private class InfoTransport : PtpTransport {
        private val incoming = ArrayDeque(listOf(
            PtpContainer(PtpCodec.DATA, PtpCodec.GET_DEVICE_INFO, 0, info()),
            PtpContainer(PtpCodec.RESPONSE, PtpCodec.OK, 0, byteArrayOf()),
            PtpContainer(PtpCodec.RESPONSE, PtpCodec.OK, 0, byteArrayOf()),
            PtpContainer(PtpCodec.RESPONSE, PtpCodec.OK, 1, byteArrayOf())))
        override fun send(command: ByteArray) = Unit
        override fun receive(): PtpContainer = incoming.removeFirst()
        override fun close() = Unit
        companion object {
            private fun info(): ByteArray {
                val out = ByteArrayOutputStream()
                fun u16(value: Int) { out.write(byteArrayOf(value.toByte(), (value ushr 8).toByte())) }
                fun u32(value: Int) { out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()) }
                fun string(value: String) { out.write(value.length + 1); out.write(value.toByteArray(Charsets.UTF_16LE)); u16(0) }
                u16(100); u32(10); u16(100); string(""); u16(0)
                u32(1); u16(PtpCodec.INITIATE_CAPTURE)
                repeat(4) { u32(0) }
                string("Nikon"); string("Z 6"); string("1.0"); string("DO_NOT_LOG_SERIAL")
                return out.toByteArray()
            }
        }
    }
}
