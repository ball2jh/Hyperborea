package com.nettarion.hyperborea.broadcast.ftms

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.ftms.ControlPointParser
import com.nettarion.hyperborea.core.model.DeviceCommand
import com.nettarion.hyperborea.core.model.DeviceType
import com.nettarion.hyperborea.core.test.buildDeviceInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Test
import java.util.UUID

class FtmsGattCallbackTest {

    private val device = mockk<BluetoothDevice>(relaxed = true) {
        every { address } returns "AA:BB:CC:DD:EE:FF"
    }
    private val gattServer = mockk<BluetoothGattServer>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val deviceInfo = buildDeviceInfo()
    private val commands = mutableListOf<DeviceCommand>()
    private val connectedDevices = mutableListOf<BluetoothDevice>()
    private val disconnectedDevices = mutableListOf<BluetoothDevice>()
    private var serviceAddedCalled = false
    private val logger = object : AppLogger {
        override fun d(tag: String, message: String) {}
        override fun i(tag: String, message: String) {}
        override fun w(tag: String, message: String) {}
        override fun e(tag: String, message: String, throwable: Throwable?) {}
    }

    private val callback = FtmsGattCallback(
        context = context,
        logger = logger,
        deviceInfo = deviceInfo,
        onClientConnected = { connectedDevices.add(it) },
        onClientDisconnected = { disconnectedDevices.add(it) },
        onCommand = { commands.add(it) },
        onServiceAdded = { serviceAddedCalled = true },
    ).apply {
        gattServer = this@FtmsGattCallbackTest.gattServer
    }

    @Test
    fun `connected invokes onClientConnected`() {
        callback.onConnectionStateChange(device, 0, BluetoothProfile.STATE_CONNECTED)

        assertThat(connectedDevices).containsExactly(device)
    }

    @Test
    fun `disconnected clears CCCD and invokes callback`() {
        val charUuid = FtmsServiceBuilder.dataCharacteristicUuid(DeviceType.BIKE)
        val charMock = mockk<BluetoothGattCharacteristic> { every { uuid } returns charUuid }
        val descriptor = mockk<BluetoothGattDescriptor> {
            every { uuid } returns FtmsServiceBuilder.CCCD_UUID
            every { characteristic } returns charMock
        }
        callback.onDescriptorWriteRequest(device, 1, descriptor, false, true, 0, byteArrayOf(0x01, 0x00))
        assertThat(callback.isSubscribed(charUuid)).isTrue()

        callback.onConnectionStateChange(device, 0, BluetoothProfile.STATE_DISCONNECTED)

        assertThat(callback.isSubscribed(charUuid)).isFalse()
        assertThat(disconnectedDevices).containsExactly(device)
    }

    @Test
    fun `read request returns FTMS Feature value`() {
        val mockChar = mockk<BluetoothGattCharacteristic> {
            every { uuid } returns FtmsServiceBuilder.FTMS_FEATURE_UUID
        }
        val valueSlot = slot<ByteArray>()

        callback.onCharacteristicReadRequest(device, 1, 0, mockChar)

        verify { gattServer.sendResponse(device, 1, BluetoothGatt.GATT_SUCCESS, 0, capture(valueSlot)) }
        assertThat(valueSlot.captured).isEqualTo(FtmsServiceBuilder.ftmsFeatureValue(DeviceType.BIKE))
    }

    @Test
    fun `read request rejects unknown UUID`() {
        val mockChar = mockk<BluetoothGattCharacteristic> {
            every { uuid } returns UUID.randomUUID()
        }

        callback.onCharacteristicReadRequest(device, 1, 0, mockChar)

        verify { gattServer.sendResponse(device, 1, BluetoothGatt.GATT_READ_NOT_PERMITTED, 0, null) }
    }

    @Test
    fun `read request with offset returns remaining bytes`() {
        val mockChar = mockk<BluetoothGattCharacteristic> {
            every { uuid } returns FtmsServiceBuilder.FTMS_FEATURE_UUID
        }
        val valueSlot = slot<ByteArray>()

        callback.onCharacteristicReadRequest(device, 1, 4, mockChar)

        verify { gattServer.sendResponse(device, 1, BluetoothGatt.GATT_SUCCESS, 4, capture(valueSlot)) }
        assertThat(valueSlot.captured).hasLength(4)
    }

    @Test
    fun `write FTMS CP emits SetResistance command`() {
        val mockChar = mockk<BluetoothGattCharacteristic> {
            every { uuid } returns FtmsServiceBuilder.FTMS_CONTROL_POINT_UUID
        }
        val mockService = mockk<BluetoothGattService>()
        val mockCpChar = mockk<BluetoothGattCharacteristic>(relaxed = true)
        every { gattServer.getService(FtmsServiceBuilder.FTMS_SERVICE_UUID) } returns mockService
        every { mockService.getCharacteristic(FtmsServiceBuilder.FTMS_CONTROL_POINT_UUID) } returns mockCpChar

        callback.onCharacteristicWriteRequest(device, 1, mockChar, false, true, 0, byteArrayOf(0x04, 0x64, 0x00))

        assertThat(commands).containsExactly(DeviceCommand.SetResistance(10))
    }

    @Test
    fun `write rejects non-CP characteristic`() {
        val mockChar = mockk<BluetoothGattCharacteristic> {
            every { uuid } returns UUID.randomUUID()
        }

        callback.onCharacteristicWriteRequest(device, 1, mockChar, false, true, 0, byteArrayOf(0x04, 0x64, 0x00))

        verify { gattServer.sendResponse(device, 1, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, 0, null) }
        assertThat(commands).isEmpty()
    }

    @Test
    fun `descriptor write stores CCCD value`() {
        val charUuid = FtmsServiceBuilder.dataCharacteristicUuid(DeviceType.BIKE)
        val charMock = mockk<BluetoothGattCharacteristic> { every { uuid } returns charUuid }
        val descriptor = mockk<BluetoothGattDescriptor> {
            every { uuid } returns FtmsServiceBuilder.CCCD_UUID
            every { characteristic } returns charMock
        }

        callback.onDescriptorWriteRequest(device, 1, descriptor, false, true, 0, byteArrayOf(0x01, 0x00))

        assertThat(callback.isSubscribed(charUuid)).isTrue()
    }

    @Test
    fun `isSubscribed false when no CCCD written`() {
        assertThat(callback.isSubscribed(FtmsServiceBuilder.FTMS_FEATURE_UUID)).isFalse()
    }

    @Test
    fun `isSubscribed toggles with enable and disable`() {
        val charUuid = FtmsServiceBuilder.dataCharacteristicUuid(DeviceType.BIKE)
        val charMock = mockk<BluetoothGattCharacteristic> { every { uuid } returns charUuid }
        val descriptor = mockk<BluetoothGattDescriptor> {
            every { uuid } returns FtmsServiceBuilder.CCCD_UUID
            every { characteristic } returns charMock
        }

        callback.onDescriptorWriteRequest(device, 1, descriptor, false, true, 0, byteArrayOf(0x01, 0x00))
        assertThat(callback.isSubscribed(charUuid)).isTrue()

        callback.onDescriptorWriteRequest(device, 2, descriptor, false, true, 0, byteArrayOf(0x00, 0x00))
        assertThat(callback.isSubscribed(charUuid)).isFalse()
    }

    @Test
    fun `onServiceAdded invokes callback`() {
        val mockService = mockk<BluetoothGattService>(relaxed = true)

        callback.onServiceAdded(BluetoothGatt.GATT_SUCCESS, mockService)

        assertThat(serviceAddedCalled).isTrue()
    }

    // --- Bug-catching tests below ---

    @Test
    fun `CP write with null gattServer still emits command`() {
        // If someone wraps command emission inside the indication block,
        // a null gattServer would silently stop forwarding resistance/incline to hardware.
        val freshCallback = FtmsGattCallback(
            context = context,
            logger = logger,
            deviceInfo = deviceInfo,
            onClientConnected = {},
            onClientDisconnected = {},
            onCommand = { commands.add(it) },
            onServiceAdded = {},
        )
        // gattServer intentionally left null
        val mockChar = mockk<BluetoothGattCharacteristic> {
            every { uuid } returns FtmsServiceBuilder.FTMS_CONTROL_POINT_UUID
        }

        freshCallback.onCharacteristicWriteRequest(device, 1, mockChar, false, true, 0, byteArrayOf(0x04, 0x64, 0x00))

        assertThat(commands).containsExactly(DeviceCommand.SetResistance(10))
    }

    @Test
    fun `CP write with responseNeeded false still emits command`() {
        // BLE write-without-response is valid. If parsing gets gated behind
        // responseNeeded, commands silently stop working for this write type.
        val mockChar = mockk<BluetoothGattCharacteristic> {
            every { uuid } returns FtmsServiceBuilder.FTMS_CONTROL_POINT_UUID
        }
        val mockService = mockk<BluetoothGattService>()
        val mockCpChar = mockk<BluetoothGattCharacteristic>(relaxed = true)
        every { gattServer.getService(FtmsServiceBuilder.FTMS_SERVICE_UUID) } returns mockService
        every { mockService.getCharacteristic(FtmsServiceBuilder.FTMS_CONTROL_POINT_UUID) } returns mockCpChar

        callback.onCharacteristicWriteRequest(device, 1, mockChar, false, false, 0, byteArrayOf(0x05, 0xC8.toByte(), 0x00))

        assertThat(commands).containsExactly(DeviceCommand.SetTargetPower(200))
        // No sendResponse call since responseNeeded=false
        verify(exactly = 0) { gattServer.sendResponse(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `descriptor read returns CCCD disabled by default`() {
        // Tests the completely untested onDescriptorReadRequest path.
        // If the default changes or the charUuid lookup breaks, this catches it.
        val charUuid = FtmsServiceBuilder.dataCharacteristicUuid(DeviceType.BIKE)
        val charMock = mockk<BluetoothGattCharacteristic> { every { uuid } returns charUuid }
        val descriptor = mockk<BluetoothGattDescriptor> {
            every { uuid } returns FtmsServiceBuilder.CCCD_UUID
            every { characteristic } returns charMock
        }
        val valueSlot = slot<ByteArray>()

        callback.onDescriptorReadRequest(device, 1, 0, descriptor)

        verify { gattServer.sendResponse(device, 1, BluetoothGatt.GATT_SUCCESS, 0, capture(valueSlot)) }
        assertThat(valueSlot.captured).isEqualTo(FtmsServiceBuilder.CCCD_DISABLED)
    }

    @Test
    fun `descriptor read returns written CCCD value`() {
        // After enabling notifications, descriptor read must return the enabled value.
        val charUuid = FtmsServiceBuilder.dataCharacteristicUuid(DeviceType.BIKE)
        val charMock = mockk<BluetoothGattCharacteristic> { every { uuid } returns charUuid }
        val descriptor = mockk<BluetoothGattDescriptor> {
            every { uuid } returns FtmsServiceBuilder.CCCD_UUID
            every { characteristic } returns charMock
        }

        callback.onDescriptorWriteRequest(device, 1, descriptor, false, true, 0, byteArrayOf(0x01, 0x00))

        val valueSlot = slot<ByteArray>()
        callback.onDescriptorReadRequest(device, 2, 0, descriptor)

        verify { gattServer.sendResponse(device, 2, BluetoothGatt.GATT_SUCCESS, 0, capture(valueSlot)) }
        assertThat(valueSlot.captured).isEqualTo(byteArrayOf(0x01, 0x00))
    }

    @Test
    fun `non-CCCD descriptor write is rejected`() {
        // If someone removes the UUID check, any descriptor write would be stored
        // as subscription state, breaking CCCD tracking.
        val descriptor = mockk<BluetoothGattDescriptor> {
            every { uuid } returns UUID.randomUUID() // not CCCD
        }

        callback.onDescriptorWriteRequest(device, 1, descriptor, false, true, 0, byteArrayOf(0x01, 0x00))

        verify { gattServer.sendResponse(device, 1, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, 0, null) }
    }

    @Suppress("DEPRECATION")
    @Test
    fun `session control Request Control sends indication but no command`() {
        // Zwift sends opcode 0x00 before real commands. If this emits a DeviceCommand,
        // the hardware gets unexpected instructions. If the indication fails, Zwift hangs.
        val mockChar = mockk<BluetoothGattCharacteristic> {
            every { uuid } returns FtmsServiceBuilder.FTMS_CONTROL_POINT_UUID
        }
        val mockService = mockk<BluetoothGattService>()
        val mockCpChar = mockk<BluetoothGattCharacteristic>(relaxed = true)
        every { gattServer.getService(FtmsServiceBuilder.FTMS_SERVICE_UUID) } returns mockService
        every { mockService.getCharacteristic(FtmsServiceBuilder.FTMS_CONTROL_POINT_UUID) } returns mockCpChar

        callback.onCharacteristicWriteRequest(device, 1, mockChar, false, true, 0, byteArrayOf(0x00))

        assertThat(commands).isEmpty()
        // Verify CP indication was sent with SUCCESS result code
        val valueSlot = slot<ByteArray>()
        verify { mockCpChar.setValue(capture(valueSlot)) }
        assertThat(valueSlot.captured).isEqualTo(
            ControlPointParser.encodeResponse(0x00, ControlPointParser.RESULT_SUCCESS),
        )
    }

    @Test
    fun `read request at exact value boundary returns empty array`() {
        // FTMS Feature is 8 bytes. offset=8 should return empty, not throw.
        // Off-by-one (> instead of >=) would cause index out of bounds.
        val mockChar = mockk<BluetoothGattCharacteristic> {
            every { uuid } returns FtmsServiceBuilder.FTMS_FEATURE_UUID
        }
        val valueSlot = slot<ByteArray>()

        callback.onCharacteristicReadRequest(device, 1, 8, mockChar)

        verify { gattServer.sendResponse(device, 1, BluetoothGatt.GATT_SUCCESS, 8, capture(valueSlot)) }
        assertThat(valueSlot.captured).isEmpty()
    }

    @Test
    fun `non-CCCD descriptor read is rejected`() {
        val descriptor = mockk<BluetoothGattDescriptor> {
            every { uuid } returns UUID.randomUUID()
        }

        callback.onDescriptorReadRequest(device, 1, 0, descriptor)

        verify { gattServer.sendResponse(device, 1, BluetoothGatt.GATT_READ_NOT_PERMITTED, 0, null) }
    }
}
