package com.nexachat.core.storage;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * Symmetric encryption boundary for token material. {@link TokenStore} persists only
 * {@link TokenCipher} output, so unit tests can substitute a fake cipher and never
 * touch AndroidKeyStore.
 */
public interface TokenCipher {

    /** Encrypts {@code plaintext}; output format is implementation-defined. */
    byte[] encrypt(byte[] plaintext) throws GeneralSecurityException, IOException;

    /** Reverses {@link #encrypt}. Throws on tampered/corrupt input. */
    byte[] decrypt(byte[] packed) throws GeneralSecurityException, IOException;
}
