package com.nettarion.hyperborea.core

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
            0x04.toByte() -> { // Set Target Resistance
                if (payload.size < 3) return ControlPointResult.Unsupported(opcode)
                val raw = uint16LEAt(payload, 1)
                ControlPointResult.DeviceCmd(DeviceCommand.SetResistance((raw / 10.0).toInt()))
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
            else -> ControlPointResult.Unsupported(opcode)
        }
    }

    /**
     * Parses a write to Wahoo Control (0xE005).
     */
    fun parseWahooControl(payload: ByteArray): ControlPointResult {
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
            else -> ControlPointResult.Unsupported(payload[0])
        }
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
