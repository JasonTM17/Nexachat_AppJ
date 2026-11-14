package com.nexachat.core.crypto;

import androidx.annotation.NonNull;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Renders the 60-digit Signal-style safety number for a conversation. Both sides
 * collect every active device identity ({@code userId:deviceId:publicKeyB64}),
 * sort the lines lexicographically, SHA-512 the UTF-8 join, then read twelve
 * consecutive unsigned 5-byte big-endian integers modulo 100000.
 *
 * <p>Key or membership changes on either side flip the code; ordering of the
 * input list never does.
 */
public final class SafetyCode {

    /** One active device identity line element. */
    public record DeviceIdentity(@NonNull String userId, @NonNull String deviceId,
                                 @NonNull String publicKeyB64) {
    }

    private SafetyCode() {
    }

    /** Twelve groups of five digits, space-separated for display. */
    @NonNull
    public static String compute(@NonNull List<DeviceIdentity> devices) {
        List<String> lines = new ArrayList<>(devices.size());
        for (DeviceIdentity device : devices) {
            lines.add(device.userId() + ":" + device.deviceId() + ":" + device.publicKeyB64());
        }
        Collections.sort(lines);
        byte[] digest = sha512(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(12 * 6 - 1);
        for (int i = 0; i < 12; i++) {
            if (i > 0) out.append(' ');
            long value = new BigInteger(1, Arrays.copyOfRange(digest, 5 * i, 5 * i + 5)).longValue();
            out.append(String.format(Locale.ROOT, "%05d", value % 100_000L));
        }
        return out.toString();
    }

    private static byte[] sha512(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-512").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
