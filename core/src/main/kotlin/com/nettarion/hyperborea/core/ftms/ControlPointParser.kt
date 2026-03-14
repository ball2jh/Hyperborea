package com.nettarion.hyperborea.core.ftms

import com.nettarion.hyperborea.core.model.DeviceCommand

import kotlin.math.roundToInt

object ControlPointParser {

    sealed interface ControlPointResult {
        data class DeviceCmd(val command: DeviceCommand) : ControlPointResult
        data class SessionControl(val opcode: Byte) : ControlPointResult
        data class Unsupported(val opcode: Byte) : ControlPointResult
    }

    const val RESULT_SUCCESS: Byte = 0x01
    const val RESULT_NOT_SUPPORTED: Byte = 0x02
    const val RESULT_INVALID_PARAM: Byte = 0x03

    /**
     * Parses a write to FTMS Control Point (0x2AD9).
     */
    fun parseFtmsControlPoint(payload: ByteArray): ControlPointResult {
        if (payload.isEmpty()) return ControlPointResult.Unsupported(0x00)
        val opcode = payload[0]
        return when (opcode) {
            0x00.toByte() -> ControlPointResult.SessionControl(opcode) // Request Control
            0x01.toByte() -> ControlPointResult.SessionControl(opcode) // Reset
            0x03.toByte() -> { // Set Target Incline
                if (payload.size < 3) return ControlPointResult.Unsupported(opcode)
                val raw = sint16LEAt(payload, 1)
                ControlPointResult.DeviceCmd(DeviceCommand.SetIncline(raw / 10f))
            }
            0x04.toByte() -> { // Set Target Resistance (UINT8, 0.1 resolution)
                if (payload.size < 2) return ControlPointResult.Unsupported(opcode)
                val raw = payload[1].toInt() and 0xFF
                ControlPointResult.DeviceCmd(DeviceCommand.SetResistance((raw / 10.0).roundToInt()))
            }
            0x05.toByte() -> { // Set Target Power
                if (payload.size < 3) return ControlPointResult.Unsupported(opcode)
                val watts = sint16LEAt(payload, 1)
                ControlPointResult.DeviceCmd(DeviceCommand.SetTargetPower(watts))
            }
            0x07.toByte() -> ControlPointResult.SessionControl(opcode) // Start/Resume
            0x08.toByte() -> ControlPointResult.SessionControl(opcode) // Stop/Pause
            0x11.toByte() -> { // Indoor Bike Simulation Parameters
                if (payload.size < 5) return ControlPointResult.Unsupported(opcode)
                // wind speed at 1-2, grade at 3-4 (sint16 LE, 0.01 resolution)
                val gradeRaw = sint16LEAt(payload, 3)
                ControlPointResult.DeviceCmd(DeviceCommand.SetIncline(gradeRaw / 100f))
            }
            0x12.toByte() -> { // Set Wheel Circumference (uint16 LE, 0.1mm resolution)
                if (payload.size < 3) return ControlPointResult.Unsupported(opcode)
                ControlPointResult.SessionControl(opcode)
            }
            0x13.toByte() -> { // Spin Down Control (uint8 control)
                if (payload.size < 2) return ControlPointResult.Unsupported(opcode)
                ControlPointResult.SessionControl(opcode)
            }
            else -> ControlPointResult.Unsupported(opcode)
        }
    }

    /**
     * Parses a write to trainer control (0xE005).
     */
    fun parseTrainerControl(payload: ByteArray): ControlPointResult {
        if (payload.isEmpty()) return ControlPointResult.Unsupported(0x00)
        val cmd = payload[0].toInt() and 0xFF
        return when (cmd) {
            0x42 -> { // ERG mode: target watts
                if (payload.size < 3) return ControlPointResult.Unsupported(payload[0])
                val watts = uint16LEAt(payload, 1)
                ControlPointResult.DeviceCmd(DeviceCommand.SetTargetPower(watts))
            }
            0x46 -> { // Sim mode: grade
                if (payload.size < 3) return ControlPointResult.Unsupported(payload[0])
                val raw = uint16LEAt(payload, 1)
                val gradePct = ((raw.toDouble() / 65535.0) * 2.0 - 1.0) * 100.0
                ControlPointResult.DeviceCmd(DeviceCommand.SetIncline(gradePct.toFloat()))
            }
            0x03 -> ControlPointResult.DeviceCmd(DeviceCommand.AdjustSpeed(increase = true))
            0x04 -> ControlPointResult.DeviceCmd(DeviceCommand.AdjustSpeed(increase = false))
            0x14 -> ControlPointResult.DeviceCmd(DeviceCommand.AdjustIncline(increase = true))
            0x15 -> ControlPointResult.DeviceCmd(DeviceCommand.AdjustIncline(increase = false))
            else -> ControlPointResult.Unsupported(payload[0])
        }
    }

    /**
     * Extracts a fan speed command from FTMS Indoor Bike Simulation Parameters (opcode 0x11).
     * Maps the wind speed field to a fan level: OFF (0), LOW (1), MEDIUM (2), HIGH (3).
     */
    fun extractFanCommand(ftmsPayload: ByteArray): DeviceCommand.SetFanSpeed? {
        if (ftmsPayload.size < 3 || ftmsPayload[0] != 0x11.toByte()) return null
        val windRaw = sint16LEAt(ftmsPayload, 1) // 0.001 m/s resolution
        val windMps = kotlin.math.abs(windRaw / 1000f)
        val level = when {
            windMps < 1f -> 0  // OFF
            windMps < 4f -> 1  // LOW
            windMps < 8f -> 2  // MEDIUM
            else -> 3          // HIGH
        }
        return DeviceCommand.SetFanSpeed(level)
    }

    /**
     * Encodes a FTMS CP response indication: [0x80, requestOpcode, resultCode].
     */
    fun encodeResponse(requestOpcode: Byte, resultCode: Byte): ByteArray =
        byteArrayOf(0x80.toByte(), requestOpcode, resultCode)

    private fun uint16LEAt(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun sint16LEAt(data: ByteArray, offset: Int): Int {
        val raw = uint16LEAt(data, offset)
        return if (raw >= 0x8000) raw - 0x10000 else raw
    }
}
