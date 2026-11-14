/**
 * Client-side end-to-end encryption (v1, DIRECT conversations only).
 *
 * <p>Trust model: the server is a directory of public device keys and a store of
 * opaque ciphertext; it never sees plaintext or private key material. Each
 * installation owns one X25519 identity key pair sealed by the Android Keystore.
 * Every message uses a fresh random content key and a fresh ephemeral X25519
 * pair; the content key is wrapped per recipient device with a KEK derived via
 * X25519+HKDF-SHA256. A failed unwrap/decrypt never propagates plaintext or key
 * material and yields a placeholder row instead of a crash.
 */
package com.nexachat.core.crypto;
