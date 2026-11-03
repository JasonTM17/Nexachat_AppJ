package com.nexachat.core.storage

/**
 * Symmetric encryption boundary for token material. [TokenStore] persists only
 * [TokenCipher] output, so unit tests can substitute a fake cipher and never
 * touch AndroidKeyStore.
 */
interface TokenCipher {
    /** Encrypts [plaintext]; output format is implementation-defined. */
    fun encrypt(plaintext: ByteArray): ByteArray

    /** Reverses [encrypt]. Throws on tampered/corrupt input. */
    fun decrypt(packed: ByteArray): ByteArray
}
