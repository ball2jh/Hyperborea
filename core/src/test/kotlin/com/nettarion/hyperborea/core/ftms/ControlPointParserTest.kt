package com.nettarion.hyperborea.core.ftms

import com.nettarion.hyperborea.core.model.DeviceCommand

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ControlPointParserTest {

    // --- FTMS Control Point ---

    @Test
    fun `parseFtmsControlPoint request control returns SessionControl`() {
        val result = ControlPointParser.parseFtmsControlPoint(byteArrayOf(0x00))
        assertThat(result).isInstanceOf(ControlPointParser.ControlPointResult.SessionControl::class.java)
    }

    @Test
    fun `parseFtmsControlPoint reset returns SessionControl`() {
        val result = ControlPointParser.parseFtmsControlPoint(byteArrayOf(0x01))
        assertThat(result).isInstanceOf(ControlPointParser.ControlPointResult.SessionControl::class.java)
    }

    @Test
    fun `parseFtmsControlPoint set target incline`() {
        // 5.0% = 50 as sint16 LE = 0x32, 0x00
        val result = ControlPointParser.parseFtmsControlPoint(byteArrayOf(0x03, 0x32, 0x00))
        assertThat(result).isInstanceOf(ControlPointParser.ControlPointResult.DeviceCmd::class.java)
        val cmd = (result as ControlPointParser.ControlPointResult.DeviceCmd).command
        assertThat(cmd).isInstanceOf(DeviceCommand.SetIncline::class.java)
        assertThat((cmd as DeviceCommand.SetIncline).percent).isWithin(0.01f).of(5.0f)
    }

    @Test
    fun `parseFtmsControlPoint set target incline negative`() {
        // -3.0% = -30 as sint16 LE = 0xE2, 0xFF
        val result = ControlPointParser.parseFtmsControlPoint(byteArrayOf(0x03, 0xE2.toByte(), 0xFF.toByte()))
        val cmd = (result as ControlPointParser.ControlPointResult.DeviceCmd).command
        assertThat((cmd as DeviceCommand.SetIncline).percent).isWithin(0.01f).of(-3.0f)
    }

    @Test
    fun `parseFtmsControlPoint set target resistance`() {
        // Resistance 100 as UINT8 (10.0 / 0.1 resolution) = 0x64
        val result = ControlPointParser.parseFtmsControlPoint(byteArrayOf(0x04, 0x64))
        val cmd = (result as ControlPointParser.ControlPointResult.DeviceCmd).command
        assertThat(cmd).isInstanceOf(DeviceCommand.SetResistance::class.java)
        assertThat((cmd as DeviceCommand.SetResistance).level).isEqualTo(10)
    }

    @Test
    fun `parseFtmsControlPoint set target resistance rounds half-levels up`() {
        // Resistance 55 as UINT8 (5.5 / 0.1 resolution) = 0x37 → rounds to 6
        val result = ControlPointParser.parseFtmsControlPoint(byteArrayOf(0x04, 0x37))
        val cmd = (result as ControlPointParser.ControlPointResult.DeviceCmd).command
        assertThat((cmd as DeviceCommand.SetResistance).level).isEqualTo(6)
    }

    @Test
    fun `parseFtmsControlPoint set target resistance rounds down below half`() {
        // Resistance 54 as UINT8 (5.4 / 0.1 resolution) = 0x36 → rounds to 5
        val result = ControlPointParser.parseFtmsControlPoint(byteArrayOf(0x04, 0x36))
        val cmd = (result as ControlPointParser.ControlPointResult.DeviceCmd).command
        assertThat((cmd as DeviceCommand.SetResistance).level).isEqualTo(5)
    }

    @Test
    fun `parseFtmsControlPoint set target power`() {
        // 200 watts as sint16 LE = 0xC8, 0x00
        val result = ControlPointParser.parseFtmsControlPoint(byteArrayOf(0x05, 0xC8.toByte(), 0x00))
        val cmd = (result as ControlPointParser.ControlPointResult.DeviceCmd).command
        assertThat(cmd).isInstanceOf(DeviceCommand.SetTargetPower::class.java)
        assertThat((cmd as DeviceCommand.SetTargetPower).watts).isEqualTo(200)
    }

    @Test
    fun `parseFtmsControlPoint sim params extracts grade`() {
        // Sim params: wind(2 bytes) + grade(2 bytes) + ...
        // Grade 4.50% = 450 as sint16 LE at offset 3 = bytes[3]=0xC2, bytes[4]=0x01
        val payload = byteArrayOf(0x11, 0x00, 0x00, 0xC2.toByte(), 0x01, 0x00, 0x00)
        val result = ControlPointParser.parseFtmsControlPoint(payload)
        val cmd = (result as ControlPointParser.ControlPointResult.DeviceCmd).command
        assertThat((cmd as DeviceCommand.SetIncline).percent).isWithin(0.01f).of(4.5f)
    }

    @Test
    fun `parseFtmsControlPoint start returns SessionControl`() {
        val result = ControlPointParser.parseFtmsControlPoint(byteArrayOf(0x07))
        assertThat(result).isInstanceOf(ControlPointParser.ControlPointResult.SessionControl::class.java)
    }

    @Test
    fun `parseFtmsControlPoint set wheel circumference returns SessionControl`() {
        // 0x12 with uint16 LE circumference (2096mm = 0x0830)
        val result = ControlPointParser.parseFtmsControlPoint(byteArrayOf(0x12, 0x30, 0x08))
        assertThat(result).isInstanceOf(ControlPointParser.ControlPointResult.SessionControl::class.java)
        assertThat((result as ControlPointParser.ControlPointResult.SessionControl).opcode)
            .isEqualTo(0x12.toByte())
    }

    @Test
    fun `parseFtmsControlPoint set wheel circumference too short returns Unsupported`() {
        val result = ControlPointParser.parseFtmsControlPoint(byteArrayOf(0x12, 0x30))
        assertThat(result).isInstanceOf(ControlPointParser.ControlPointResult.Unsupported::class.java)
    }

    @Test
    fun `parseFtmsControlPoint spin down control returns SessionControl`() {
        // 0x13 with uint8 control parameter (0x01 = start)
        val result = ControlPointParser.parseFtmsControlPoint(byteArrayOf(0x13, 0x01))
        assertThat(result).isInstanceOf(ControlPointParser.ControlPointResult.SessionControl::class.java)
        assertThat((result as ControlPointParser.ControlPointResult.SessionControl).opcode)
            .isEqualTo(0x13.toByte())
    }

    @Test
    fun `parseFtmsControlPoint spin down control too short returns Unsupported`() {
        val result = ControlPointParser.parseFtmsControlPoint(byteArrayOf(0x13))
        assertThat(result).isInstanceOf(ControlPointParser.ControlPointResult.Unsupported::class.java)
    }

    @Test
    fun `parseFtmsControlPoint unknown opcode returns Unsupported`() {
        val result = ControlPointParser.parseFtmsControlPoint(byteArrayOf(0x0F))
        assertThat(result).isInstanceOf(ControlPointParser.ControlPointResult.Unsupported::class.java)
    }

    @Test
    fun `parseFtmsControlPoint empty payload returns Unsupported`() {
        val result = ControlPointParser.parseFtmsControlPoint(byteArrayOf())
        assertThat(result).isInstanceOf(ControlPointParser.ControlPointResult.Unsupported::class.java)
    }

    // --- Trainer Control ---

    @Test
    fun `parseTrainerControl ERG mode sets target power`() {
        // cmd=0x42, 150 watts = 0x96, 0x00
        val result = ControlPointParser.parseTrainerControl(byteArrayOf(0x42, 0x96.toByte(), 0x00))
        val cmd = (result as ControlPointParser.ControlPointResult.DeviceCmd).command
        assertThat(cmd).isInstanceOf(DeviceCommand.SetTargetPower::class.java)
        assertThat((cmd as DeviceCommand.SetTargetPower).watts).isEqualTo(150)
    }

    @Test
    fun `parseTrainerControl sim mode sets incline`() {
        // cmd=0x46, midpoint (32768 = 0x8000) → grade = 0%
        val result = ControlPointParser.parseTrainerControl(byteArrayOf(0x46, 0x00, 0x80.toByte()))
        val cmd = (result as ControlPointParser.ControlPointResult.DeviceCmd).command
        assertThat(cmd).isInstanceOf(DeviceCommand.SetIncline::class.java)
        assertThat((cmd as DeviceCommand.SetIncline).percent).isWithin(0.1f).of(0.0f)
    }

    @Test
    fun `parseTrainerControl sim mode max value is approximately 100 percent`() {
        // 0xFFFF → ((65535/65535)*2-1)*100 = 100%
        val result = ControlPointParser.parseTrainerControl(byteArrayOf(0x46, 0xFF.toByte(), 0xFF.toByte()))
        val cmd = (result as ControlPointParser.ControlPointResult.DeviceCmd).command
        assertThat((cmd as DeviceCommand.SetIncline).percent).isWithin(0.1f).of(100.0f)
    }

    @Test
    fun `parseTrainerControl sim mode min value is approximately negative 100 percent`() {
        // 0x0000 → ((0/65535)*2-1)*100 = -100%
        val result = ControlPointParser.parseTrainerControl(byteArrayOf(0x46, 0x00, 0x00))
        val cmd = (result as ControlPointParser.ControlPointResult.DeviceCmd).command
        assertThat((cmd as DeviceCommand.SetIncline).percent).isWithin(0.1f).of(-100.0f)
    }

    @Test
    fun `parseTrainerControl unknown command returns Unsupported`() {
        val result = ControlPointParser.parseTrainerControl(byteArrayOf(0x10, 0x00, 0x00))
        assertThat(result).isInstanceOf(ControlPointParser.ControlPointResult.Unsupported::class.java)
    }

    // --- extractFanCommand ---

    @Test
    fun `extractFanCommand wind zero returns OFF`() {
        // opcode 0x11, wind=0 (sint16 LE)
        val payload = byteArrayOf(0x11, 0x00, 0x00, 0x00, 0x00)
        val cmd = ControlPointParser.extractFanCommand(payload)
        assertThat(cmd).isNotNull()
        assertThat(cmd!!.level).isEqualTo(0)
    }

    @Test
    fun `extractFanCommand wind 2000 returns LOW`() {
        // wind = 2000 (0x07D0 LE) → 2.0 m/s → LOW
        val payload = byteArrayOf(0x11, 0xD0.toByte(), 0x07, 0x00, 0x00)
        val cmd = ControlPointParser.extractFanCommand(payload)
        assertThat(cmd).isNotNull()
        assertThat(cmd!!.level).isEqualTo(1)
    }

    @Test
    fun `extractFanCommand wind 6000 returns MEDIUM`() {
        // wind = 6000 (0x1770 LE) → 6.0 m/s → MEDIUM
        val payload = byteArrayOf(0x11, 0x70, 0x17, 0x00, 0x00)
        val cmd = ControlPointParser.extractFanCommand(payload)
        assertThat(cmd).isNotNull()
        assertThat(cmd!!.level).isEqualTo(2)
    }

    @Test
    fun `extractFanCommand wind 10000 returns HIGH`() {
        // wind = 10000 (0x2710 LE) → 10.0 m/s → HIGH
        val payload = byteArrayOf(0x11, 0x10, 0x27, 0x00, 0x00)
        val cmd = ControlPointParser.extractFanCommand(payload)
        assertThat(cmd).isNotNull()
        assertThat(cmd!!.level).isEqualTo(3)
    }

    @Test
    fun `extractFanCommand negative wind uses abs`() {
        // wind = -6000 (0xE890 as sint16 LE) → abs = 6.0 m/s → MEDIUM
        val raw = (-6000).toShort()
        val payload = byteArrayOf(0x11, (raw.toInt() and 0xFF).toByte(), ((raw.toInt() shr 8) and 0xFF).toByte(), 0x00, 0x00)
        val cmd = ControlPointParser.extractFanCommand(payload)
        assertThat(cmd).isNotNull()
        assertThat(cmd!!.level).isEqualTo(2)
    }

    @Test
    fun `extractFanCommand non-0x11 opcode returns null`() {
        val payload = byteArrayOf(0x03, 0x00, 0x00)
        assertThat(ControlPointParser.extractFanCommand(payload)).isNull()
    }

    @Test
    fun `extractFanCommand too short payload returns null`() {
        val payload = byteArrayOf(0x11, 0x00)
        assertThat(ControlPointParser.extractFanCommand(payload)).isNull()
    }

    // --- Response encoding ---

    @Test
    fun `encodeResponse produces correct 3-byte response`() {
        val resp = ControlPointParser.encodeResponse(0x05, ControlPointParser.RESULT_SUCCESS)
        assertThat(resp).isEqualTo(byteArrayOf(0x80.toByte(), 0x05, 0x01))
    }
}
