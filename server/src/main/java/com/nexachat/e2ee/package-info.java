/**
 * End-to-end encrypted direct chats (v1). Trust model: clients register a
 * per-device X25519 public key and encrypt message bodies on the sender
 * device; the server stores only public keys and opaque ciphertext plus the
 * per-device wrapped content-key envelopes. It never sees plaintext and never
 * decrypts — summary, RAG, search, and push previews all exclude encrypted
 * rows. Private keys never leave the device.
 */
package com.nexachat.e2ee;
