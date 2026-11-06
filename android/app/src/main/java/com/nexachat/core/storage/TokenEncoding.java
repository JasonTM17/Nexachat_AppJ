package com.nexachat.core.storage;

import java.util.Base64;

/** Base64 helpers for encrypted token blobs stored in DataStore. */
public final class TokenEncoding {

    private TokenEncoding() {}

    public static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    public static byte[] b64decode(String value) {
        return Base64.getDecoder().decode(value);
    }
}
