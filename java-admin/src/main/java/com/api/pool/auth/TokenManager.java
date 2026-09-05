package com.api.pool.auth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * Admin session tokens. A token is a random 32-byte value stored in a local
 * session file together with its expiry timestamp. Tokens expire after the
 * configured number of days (7 by default per the README).
 */
public class TokenManager {

    private final Path file;
    private final long durationMillis;

    public TokenManager(String sessionFile, long durationMillis) {
        this.file = Path.of(sessionFile);
        this.durationMillis = durationMillis;
    }

    /** Create a new session token and persist it. */
    public String createToken() {
        byte[] rnd = new byte[32];
        new SecureRandom().nextBytes(rnd);
        String token = HexFormat.of().formatHex(rnd);
        String expiry = Instant.now().plusMillis(durationMillis).toString();
        try {
            Files.writeString(file, token + System.lineSeparator() + expiry);
        } catch (IOException e) {
            throw new RuntimeException("Cannot write session file: " + e.getMessage(), e);
        }
        return token;
    }

    /** True if a session file exists with a token that has not expired. */
    public boolean isValid() {
        try {
            if (!Files.exists(file)) {
                return false;
            }
            List<String> lines = Files.readAllLines(file);
            if (lines.size() < 2 || lines.get(0).isBlank()) {
                return false;
            }
            Instant expiry = Instant.parse(lines.get(1));
            return expiry.isAfter(Instant.now());
        } catch (Exception e) {
            return false;
        }
    }

    public String currentToken() {
        try {
            if (!Files.exists(file)) {
                return null;
            }
            List<String> lines = Files.readAllLines(file);
            return lines.isEmpty() ? null : lines.get(0);
        } catch (IOException e) {
            return null;
        }
    }

    public Instant expiry() {
        try {
            if (!Files.exists(file)) {
                return null;
            }
            List<String> lines = Files.readAllLines(file);
            if (lines.size() < 2) {
                return null;
            }
            return Instant.parse(lines.get(1));
        } catch (Exception e) {
            return null;
        }
    }

    /** Logout: delete the session file. */
    public void revoke() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }
}