/**
 * Client-side end-to-end encryption (v1, DIRECT conversations only).
 *
 * <p>Trust model: v1 protects stored traffic against passive database exposure
 * (rows hold ciphertext, not plaintext) but trusts the authenticated server for
 * key-directory truth, sender identity, and toggle state — there are no sender
 * signatures, key pinning, change alerts, or ratchet, and the safety code is a
 * manual comparison, not enforced verification. Each installation owns one
 * X25519 identity key pair sealed by the Android Keystore. Every message uses
 * a fresh random content key and a fresh ephemeral X25519 pair; the content
 * key is wrapped per recipient device with a KEK derived via X25519+HKDF-SHA256,
 * and the comparable safety code is a SHA-512 digest of the sorted
 * {@code userId:deviceId:publicKey} lines. A failed unwrap/decrypt never
 * propagates plaintext or key material and yields a placeholder row instead
 * of a crash.
 */
package com.nexachat.core.crypto;
