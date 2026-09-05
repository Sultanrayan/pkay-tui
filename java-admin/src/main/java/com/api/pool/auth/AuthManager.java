package com.api.pool.auth;

import com.api.pool.db.DatabaseManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Admin authentication. Passwords are stored as salted SHA-256 hashes in the
 * format {@code sha256:<salt-hex>:<hash-hex>}.
 */
public class AuthManager {

    private final DatabaseManager db;
    private final TokenManager tokens;

    public AuthManager(DatabaseManager db, TokenManager tokens) {
        this.db = db;
        this.tokens = tokens;
    }

    /** Verify credentials and, on success, create a session token. */
    public boolean login(String username, String password) {
        DatabaseManager.User user = db.getUserByName(username);
        if (user == null || !verifyPassword(password, user.passwordHash())) {
            return false;
        }
        tokens.createToken();
        return true;
    }

    public void logout() {
        tokens.revoke();
    }

    public boolean isLoggedIn() {
        return tokens.isValid();
    }

    /** Hash a password with a fresh random salt. */
    public static String hashPassword(String password) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] hash = sha256(salt, password);
        return "sha256:" + HexFormat.of().formatHex(salt) + ":" + HexFormat.of().formatHex(hash);
    }

    private static boolean verifyPassword(String password, String stored) {
        if (stored == null) {
            return false;
        }
        String[] parts = stored.split(":");
        if (parts.length != 3 || !parts[0].equals("sha256")) {
            return false;
        }
        byte[] salt = HexFormat.of().parseHex(parts[1]);
        byte[] expected = HexFormat.of().parseHex(parts[2]);
        byte[] actual = sha256(salt, password);
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] sha256(byte[] salt, String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            return md.digest(password.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}