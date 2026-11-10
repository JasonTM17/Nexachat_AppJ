package com.nexachat.feature.profile;

import androidx.annotation.NonNull;
import java.util.Objects;
import java.util.regex.Pattern;

/** Lightweight client-side email shape check before requesting an OTP. */
public final class RecoveryEmailAddressValidator {
  private static final Pattern EMAIL = Pattern.compile(
          "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

  private RecoveryEmailAddressValidator() {
  }

  public static boolean isValid(@NonNull String raw) {
    Objects.requireNonNull(raw, "raw");
    String trimmed = raw.trim();
    return !trimmed.isEmpty() && EMAIL.matcher(trimmed).matches();
  }
}
