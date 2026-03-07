package com.nettarion.hyperborea.hardware.fitpro.protocol

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.hardware.fitpro.v1.V1Security
import org.junit.Test

class V1SecurityTest {

    @Test
    fun `hash is always 32 bytes`() {
        val hash = V1Security.calculateHash(123456, 789012, 345678)
        assertThat(hash).hasLength(32)
    }

    @Test
    fun `all-zero inputs produce 32-byte hash`() {
        val hash = V1Security.calculateHash(0, 0, 0)
        assertThat(hash).hasLength(32)
    }

    @Test
    fun `all-zero serialNumber uses model path for every bit`() {
        // serialNumber=0 means every bit is 0, so all indices take the model branch:
        // hash[i] = ((model + i) * (i+1)) xor (i+1)
        val model = 42
        val hash = V1Security.calculateHash(serialNumber = 0, partNumber = 999, model = model)

        for (i in 0 until 32) {
            val pos = (i + 1).toByte()
            val expected = (((model + i) * pos.toInt()).toByte().toInt() xor pos.toInt()).toByte()
            assertThat(hash[i]).isEqualTo(expected)
        }
    }

    @Test
    fun `all-ones serialNumber uses partNumber path for every bit`() {
        // serialNumber=-1 (all bits set) means every bit is 1
        val partNumber = 0x12345678
        val hash = V1Security.calculateHash(serialNumber = -1, partNumber = partNumber, model = 0)

        for (i in 0 until 32) {
            val pos = (i + 1).toByte()
            val expected = if (i < 16) {
                (((partNumber shl 16) or (partNumber ushr 16)) shr i).toByte().toInt() xor pos.toInt()
            } else {
                (partNumber shr i).toByte().toInt() xor pos.toInt()
            }.toByte()
            assertThat(hash[i]).isEqualTo(expected)
        }
    }

    @Test
    fun `known value test`() {
        // 0xAAAAAAAA: bits 1,3,5,7,... are set; bits 0,2,4,6,... are clear
        val serial = 0xAAAAAAAA.toInt()
        val partNumber = 0x00ABCDEF
        val model = 100
        val hash = V1Security.calculateHash(serial, partNumber, model)
        assertThat(hash).hasLength(32)

        // Bit 0 is clear → model path
        val pos0 = 1.toByte()
        val expected0 = (((model + 0) * pos0.toInt()).toByte().toInt() xor pos0.toInt()).toByte()
        assertThat(hash[0]).isEqualTo(expected0)

        // Bit 1 is set → partNumber path, i<16
        val pos1 = 2.toByte()
        val swapped = (partNumber shl 16) or (partNumber ushr 16)
        val expected1 = ((swapped shr 1).toByte().toInt() xor pos1.toInt()).toByte()
        assertThat(hash[1]).isEqualTo(expected1)
    }

    @Test
    fun `different inputs produce different hashes`() {
        val hash1 = V1Security.calculateHash(1, 2, 3)
        val hash2 = V1Security.calculateHash(4, 5, 6)
        assertThat(hash1).isNotEqualTo(hash2)
    }
}
