package com.nexachat.auth;

import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Guards every {@link PasswordEncoder#matches} call against bcrypt's 72-byte input
 * limit (enforced as {@link IllegalArgumentException} in Spring Security 6.5+).
 * Over-long or NUL-containing raw secrets return {@code false} instead of
 * throwing, so authenticated flows (password change, future account deletion)
 * fail closed like a wrong password. {@link #encode} is delegated unchanged so
 * registration and password policy still reject secrets bcrypt cannot hash.
 */
public final class BcryptBoundedPasswordEncoder implements PasswordEncoder {

    private final PasswordEncoder delegate;

    public BcryptBoundedPasswordEncoder(PasswordEncoder delegate) {
        this.delegate = delegate;
    }

    @Override
    public String encode(CharSequence rawPassword) {
        return delegate.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (rawPassword == null) {
            return false;
        }
        String raw = rawPassword.toString();
        if (!PasswordPolicy.fitsBcrypt(raw)) {
            return false;
        }
        return delegate.matches(raw, encodedPassword);
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return delegate.upgradeEncoding(encodedPassword);
    }
}
