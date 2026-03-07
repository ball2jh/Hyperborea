package com.nettarion.hyperborea.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeviceIdentityTest {

    @Test
    fun `all fields default to null`() {
        val identity = DeviceIdentity()
        assertThat(identity.serialNumber).isNull()
        assertThat(identity.firmwareVersion).isNull()
        assertThat(identity.hardwareVersion).isNull()
        assertThat(identity.model).isNull()
        assertThat(identity.partNumber).isNull()
    }

    @Test
    fun `construction with all fields`() {
        val identity = DeviceIdentity(
            serialNumber = "12345",
            firmwareVersion = "80",
            hardwareVersion = "3",
            model = "100",
            partNumber = "200",
        )
        assertThat(identity.serialNumber).isEqualTo("12345")
        assertThat(identity.firmwareVersion).isEqualTo("80")
        assertThat(identity.hardwareVersion).isEqualTo("3")
        assertThat(identity.model).isEqualTo("100")
        assertThat(identity.partNumber).isEqualTo("200")
    }

    @Test
    fun `data class equality`() {
        val a = DeviceIdentity(serialNumber = "123", firmwareVersion = "80")
        val b = DeviceIdentity(serialNumber = "123", firmwareVersion = "80")
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `copy preserves unchanged fields`() {
        val original = DeviceIdentity(serialNumber = "123", firmwareVersion = "80")
        val copied = original.copy(model = "S22i")
        assertThat(copied.serialNumber).isEqualTo("123")
        assertThat(copied.firmwareVersion).isEqualTo("80")
        assertThat(copied.model).isEqualTo("S22i")
    }
}
