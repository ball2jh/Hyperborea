package com.nettarion.hyperborea.broadcast.ftms

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.DeviceInfo
import com.nettarion.hyperborea.core.DeviceType
import com.nettarion.hyperborea.core.test.buildDeviceInfo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FtmsServiceBuilderTest {

    // --- FTMS Service ---

    @Test
    fun `FTMS service has correct UUID`() {
        val service = FtmsServiceBuilder.buildFtmsService(DeviceType.BIKE)
        assertThat(service.uuid).isEqualTo(FtmsServiceBuilder.FTMS_SERVICE_UUID)
    }

    @Test
    fun `FTMS service is primary`() {
        val service = FtmsServiceBuilder.buildFtmsService(DeviceType.BIKE)
        assertThat(service.type).isEqualTo(BluetoothGattService.SERVICE_TYPE_PRIMARY)
    }

    @Test
    fun `FTMS service has 8 characteristics`() {
        val service = FtmsServiceBuilder.buildFtmsService(DeviceType.BIKE)
        assertThat(service.characteristics).hasSize(8)
    }

    @Test
    fun `FTMS Feature is read-only`() {
        val char = ftmsChar(FtmsServiceBuilder.FTMS_FEATURE_UUID)
        assertThat(char.properties and BluetoothGattCharacteristic.PROPERTY_READ).isNotEqualTo(0)
        assertThat(char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE).isEqualTo(0)
    }

    @Test
    fun `Supported Resistance Range is read-only`() {
        val char = ftmsChar(FtmsServiceBuilder.SUPPORTED_RESISTANCE_UUID)
        assertThat(char.properties and BluetoothGattCharacteristic.PROPERTY_READ).isNotEqualTo(0)
    }

    @Test
    fun `FTMS Control Point has write and indicate`() {
        val char = ftmsChar(FtmsServiceBuilder.FTMS_CONTROL_POINT_UUID)
        assertThat(char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE).isNotEqualTo(0)
        assertThat(char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE).isNotEqualTo(0)
    }

    @Test
    fun `FTMS Control Point has CCCD descriptor`() {
        val char = ftmsChar(FtmsServiceBuilder.FTMS_CONTROL_POINT_UUID)
        val cccd = char.getDescriptor(FtmsServiceBuilder.CCCD_UUID)
        assertThat(cccd).isNotNull()
    }

    @Test
    fun `Indoor Bike Data has notify`() {
        val char = ftmsChar(FtmsServiceBuilder.dataCharacteristicUuid(DeviceType.BIKE))
        assertThat(char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY).isNotEqualTo(0)
    }

    @Test
    fun `Indoor Bike Data has CCCD descriptor`() {
        val char = ftmsChar(FtmsServiceBuilder.dataCharacteristicUuid(DeviceType.BIKE))
        val cccd = char.getDescriptor(FtmsServiceBuilder.CCCD_UUID)
        assertThat(cccd).isNotNull()
    }

    @Test
    fun `Supported Inclination Range is read-only`() {
        val char = ftmsChar(FtmsServiceBuilder.SUPPORTED_INCLINATION_UUID)
        assertThat(char.properties and BluetoothGattCharacteristic.PROPERTY_READ).isNotEqualTo(0)
    }

    @Test
    fun `Supported Power Range is read-only`() {
        val char = ftmsChar(FtmsServiceBuilder.SUPPORTED_POWER_UUID)
        assertThat(char.properties and BluetoothGattCharacteristic.PROPERTY_READ).isNotEqualTo(0)
    }

    @Test
    fun `Training Status has read and notify`() {
        val char = ftmsChar(FtmsServiceBuilder.TRAINING_STATUS_UUID)
        assertThat(char.properties and BluetoothGattCharacteristic.PROPERTY_READ).isNotEqualTo(0)
        assertThat(char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY).isNotEqualTo(0)
    }

    @Test
    fun `Training Status has CCCD descriptor`() {
        val char = ftmsChar(FtmsServiceBuilder.TRAINING_STATUS_UUID)
        val cccd = char.getDescriptor(FtmsServiceBuilder.CCCD_UUID)
        assertThat(cccd).isNotNull()
    }

    @Test
    fun `Fitness Machine Status has notify and CCCD`() {
        val char = ftmsChar(FtmsServiceBuilder.FITNESS_MACHINE_STATUS_UUID)
        assertThat(char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY).isNotEqualTo(0)
        val cccd = char.getDescriptor(FtmsServiceBuilder.CCCD_UUID)
        assertThat(cccd).isNotNull()
    }

    // --- CPS Service ---

    @Test
    fun `CPS service has correct UUID`() {
        val service = FtmsServiceBuilder.buildCpsService()
        assertThat(service.uuid).isEqualTo(FtmsServiceBuilder.CPS_SERVICE_UUID)
    }

    @Test
    fun `CPS service has 3 characteristics`() {
        val service = FtmsServiceBuilder.buildCpsService()
        assertThat(service.characteristics).hasSize(3)
    }

    @Test
    fun `CPS Feature is read-only`() {
        val char = cpsChar(FtmsServiceBuilder.CPS_FEATURE_UUID)
        assertThat(char.properties and BluetoothGattCharacteristic.PROPERTY_READ).isNotEqualTo(0)
    }

    @Test
    fun `CPS Measurement has notify and CCCD`() {
        val char = cpsChar(FtmsServiceBuilder.CPS_MEASUREMENT_UUID)
        assertThat(char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY).isNotEqualTo(0)
        val cccd = char.getDescriptor(FtmsServiceBuilder.CCCD_UUID)
        assertThat(cccd).isNotNull()
    }

    @Test
    fun `Sensor Location is read-only`() {
        val char = cpsChar(FtmsServiceBuilder.SENSOR_LOCATION_UUID)
        assertThat(char.properties and BluetoothGattCharacteristic.PROPERTY_READ).isNotEqualTo(0)
    }

    // --- Static values ---

    private val testDeviceInfo = buildDeviceInfo(
        maxResistance = 24,
        minResistance = 1,
        minIncline = -6f,
        maxIncline = 40f,
        maxPower = 2000,
    )

    @Test
    fun `FTMS Feature static value is 8 bytes`() {
        val value = FtmsServiceBuilder.staticValueFor(FtmsServiceBuilder.FTMS_FEATURE_UUID, testDeviceInfo)
        assertThat(value).isNotNull()
        assertThat(value!!.size).isEqualTo(8)
        assertThat(value).isEqualTo(FtmsServiceBuilder.ftmsFeatureValue(DeviceType.BIKE))
    }

    @Test
    fun `CPS Feature static value is 4 bytes`() {
        val value = FtmsServiceBuilder.staticValueFor(FtmsServiceBuilder.CPS_FEATURE_UUID, testDeviceInfo)
        assertThat(value).isNotNull()
        assertThat(value!!.size).isEqualTo(4)
    }

    @Test
    fun `Supported Resistance Range encodes from DeviceInfo`() {
        val value = FtmsServiceBuilder.staticValueFor(FtmsServiceBuilder.SUPPORTED_RESISTANCE_UUID, testDeviceInfo)
        assertThat(value).isNotNull()
        assertThat(value!!.size).isEqualTo(6)
        // min=1 → 10 (0x0A,0x00), max=24 → 240 (0xF0,0x00), step=10 (0x0A,0x00)
        assertThat(value).isEqualTo(byteArrayOf(0x0A, 0x00, 0xF0.toByte(), 0x00, 0x0A, 0x00))
    }

    @Test
    fun `Supported Inclination Range encodes from DeviceInfo`() {
        val value = FtmsServiceBuilder.staticValueFor(FtmsServiceBuilder.SUPPORTED_INCLINATION_UUID, testDeviceInfo)
        assertThat(value).isNotNull()
        assertThat(value!!.size).isEqualTo(6)
        // min=-6 → -60 (0xC4,0xFF), max=40 → 400 (0x90,0x01), step=5 (0x05,0x00)
        assertThat(value).isEqualTo(byteArrayOf(0xC4.toByte(), 0xFF.toByte(), 0x90.toByte(), 0x01, 0x05, 0x00))
    }

    @Test
    fun `Supported Power Range encodes from DeviceInfo`() {
        val value = FtmsServiceBuilder.staticValueFor(FtmsServiceBuilder.SUPPORTED_POWER_UUID, testDeviceInfo)
        assertThat(value).isNotNull()
        assertThat(value!!.size).isEqualTo(6)
        // min=0 (0x00,0x00), max=2000 (0xD0,0x07), step=1 (0x01,0x00)
        assertThat(value).isEqualTo(byteArrayOf(0x00, 0x00, 0xD0.toByte(), 0x07, 0x01, 0x00))
    }

    @Test
    fun `unknown UUID returns null`() {
        val value = FtmsServiceBuilder.staticValueFor(FtmsServiceBuilder.dataCharacteristicUuid(DeviceType.BIKE), testDeviceInfo)
        assertThat(value).isNull()
    }

    // --- Helpers ---

    private fun ftmsChar(uuid: java.util.UUID): BluetoothGattCharacteristic =
        FtmsServiceBuilder.buildFtmsService(DeviceType.BIKE).getCharacteristic(uuid)

    private fun cpsChar(uuid: java.util.UUID): BluetoothGattCharacteristic =
        FtmsServiceBuilder.buildCpsService().getCharacteristic(uuid)
}
