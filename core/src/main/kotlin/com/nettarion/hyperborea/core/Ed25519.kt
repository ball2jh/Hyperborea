package com.nettarion.hyperborea.core

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Verifies Ed25519 signatures using BouncyCastle.
 * Pure Kotlin, no Android dependencies.
 */
object Ed25519Verifier {
    /**
     * Verify an Ed25519 signature.
     * @param message the signed message bytes
     * @param signature 64-byte signature
     * @param publicKey 32-byte public key
     * @return true if valid
     */
    fun verify(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        require(signature.size == 64) { "Signature must be 64 bytes" }
        require(publicKey.size == 32) { "Public key must be 32 bytes" }
        val pubKeyParams = Ed25519PublicKeyParameters(publicKey, 0)
        val signer = Ed25519Signer()
        signer.init(false, pubKeyParams)
        signer.update(message, 0, message.size)
        return signer.verifySignature(signature)
    }

    /** Decode a hex string to bytes. */
    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex string must have even length" }
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
