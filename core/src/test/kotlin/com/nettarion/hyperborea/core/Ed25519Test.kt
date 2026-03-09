package com.nettarion.hyperborea.core

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Test
import java.security.SecureRandom
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Ed25519Test {

    // Generate a test key pair using BouncyCastle (for test only)
    private val privateKeyParams = Ed25519PrivateKeyParameters(SecureRandom())
    private val publicKeyBytes = privateKeyParams.generatePublicKey().encoded

    private fun sign(message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, privateKeyParams)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    @Test
    fun `verifies valid signature`() {
        val message = """{"active":true,"expiresAt":"2026-04-07T00:00:00Z"}""".toByteArray()
        val signature = sign(message)
        assertTrue(Ed25519Verifier.verify(message, signature, publicKeyBytes))
    }

    @Test
    fun `rejects tampered message`() {
        val message = """{"active":true,"expiresAt":"2026-04-07T00:00:00Z"}""".toByteArray()
        val signature = sign(message)
        val tampered = """{"active":true,"expiresAt":"2099-01-01T00:00:00Z"}""".toByteArray()
        assertFalse(Ed25519Verifier.verify(tampered, signature, publicKeyBytes))
    }

    @Test
    fun `rejects wrong public key`() {
        val message = "test".toByteArray()
        val signature = sign(message)
        val wrongKey = Ed25519PrivateKeyParameters(SecureRandom()).generatePublicKey().encoded
        assertFalse(Ed25519Verifier.verify(message, signature, wrongKey))
    }

    @Test
    fun `hexToBytes converts correctly`() {
        val hex = "48656c6c6f"
        val bytes = Ed25519Verifier.hexToBytes(hex)
        assertTrue(bytes.contentEquals("Hello".toByteArray()))
    }

    @Test
    fun `hexToBytes rejects odd-length string`() {
        assertFailsWith<IllegalArgumentException> {
            Ed25519Verifier.hexToBytes("abc")
        }
    }

    @Test
    fun `verify rejects wrong signature size`() {
        assertFailsWith<IllegalArgumentException> {
            Ed25519Verifier.verify("test".toByteArray(), ByteArray(32), publicKeyBytes)
        }
    }

    @Test
    fun `verify rejects wrong key size`() {
        assertFailsWith<IllegalArgumentException> {
            Ed25519Verifier.verify("test".toByteArray(), ByteArray(64), ByteArray(16))
        }
    }
}
