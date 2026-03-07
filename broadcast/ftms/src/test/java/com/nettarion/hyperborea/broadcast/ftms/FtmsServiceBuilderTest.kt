package com.nettarion.hyperborea.broadcast.ftms

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FtmsServiceBuilderTest {

    // --- FTMS Service ---

    @Test
    fun `FTMS service has correct UUID`() {
        val service = FtmsServiceBuilder.buildFtmsService()
        assertThat(service.uuid).isEqualTo(FtmsServiceBuilder.FTMS_SERVICE_UUID)
    }

    @Test
    fun `FTMS service is primary`() {
        val service = FtmsServiceBuilder.buildFtmsService()
        assertThat(service.type).isEqualTo(BluetoothGattService.SERVICE_TYPE_PRIMARY)
    }

    @Test
    fun `FTMS service has 5 characteristics`() {
        val service = FtmsServiceBuilder.buildFtmsService()
        assertThat(service.characteristics).hasSize(5)
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
        val char = ftmsChar(FtmsServiceBuilder.INDOOR_BIKE_DATA_UUID)
        assertThat(char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY).isNotEqualTo(0)
    }

    @Test
    fun `Indoor Bike Data has CCCD descriptor`() {
        val char = ftmsChar(FtmsServiceBuilder.INDOOR_BIKE_DATA_UUID)
        val cccd = char.getDescriptor(FtmsServiceBuilder.CCCD_UUID)
        assertThat(cccd).isNotNull()
    }

    @Test
    fun `Training Status is read-only`() {
        val char = ftmsChar(FtmsServiceBuilder.TRAINING_STATUS_UUID)
        assertThat(char.properties and BluetoothGattCharacteristic.PROPERTY_READ).isNotEqualTo(0)
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

    @Test
    fun `FTMS Feature static value is 8 bytes`() {
        val value = FtmsServiceBuilder.staticValueFor(FtmsServiceBuilder.FTMS_FEATURE_UUID)
        assertThat(value).isNotNull()
        assertThat(value!!.size).isEqualTo(8)
        assertThat(value).isEqualTo(FtmsServiceBuilder.FTMS_FEATURE_VALUE)
    }

    @Test
    fun `CPS Feature static value is 4 bytes`() {
        val value = FtmsServiceBuilder.staticValueFor(FtmsServiceBuilder.CPS_FEATURE_UUID)
        assertThat(value).isNotNull()
        assertThat(value!!.size).isEqualTo(4)
    }

    @Test
    fun `unknown UUID returns null`() {
        val value = FtmsServiceBuilder.staticValueFor(FtmsServiceBuilder.INDOOR_BIKE_DATA_UUID)
        assertThat(value).isNull()
    }

    // --- Helpers ---

    private fun ftmsChar(uuid: java.util.UUID): BluetoothGattCharacteristic =
        FtmsServiceBuilder.buildFtmsService().getCharacteristic(uuid)

    private fun cpsChar(uuid: java.util.UUID): BluetoothGattCharacteristic =
        FtmsServiceBuilder.buildCpsService().getCharacteristic(uuid)
}
