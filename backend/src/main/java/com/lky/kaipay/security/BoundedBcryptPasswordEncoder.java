package com.lky.kaipay.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;

/** Align login with provisioning's byte limit; BCrypt retains legacy truncating verification. */
public final class BoundedBcryptPasswordEncoder implements PasswordEncoder {
    private final BCryptPasswordEncoder delegate = new BCryptPasswordEncoder(12);

    @Override public String encode(CharSequence rawPassword) {
        if (!acceptedInput(rawPassword)) {
            throw new IllegalArgumentException("Password contains unsupported input or exceeds the byte length");
        }
        return delegate.encode(rawPassword);
    }
    @Override public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (!acceptedInput(rawPassword)) {
            // Preserve the framework provider's BCrypt work path for known and unknown
            // accounts, but never allow a longer candidate to match its truncated prefix.
            delegate.matches("rejected-password-byte-boundary", encodedPassword);
            return false;
        }
        return delegate.matches(rawPassword, encodedPassword);
    }
    private boolean acceptedInput(CharSequence rawPassword) {
        if (rawPassword == null) return false;
        String value = rawPassword.toString();
        return value.indexOf('\0') < 0 && value.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
    @Override public boolean upgradeEncoding(String encodedPassword) { return delegate.upgradeEncoding(encodedPassword); }
}
