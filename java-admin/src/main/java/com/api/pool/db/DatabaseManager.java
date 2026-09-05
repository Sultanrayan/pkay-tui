package com.api.pool.db;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * SQLite access for the admin layer. The schema is identical to the one used
 * by the Rust proxy core, so both processes share the same database file.
 */
public class DatabaseManager implements AutoCloseable {

    private static final DateTimeFormatter DB_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String SCHEMA = """
        CREATE TABLE IF NOT EXISTS users (
            id VARCHAR(36) PRIMARY KEY,
            username VARCHAR(50) UNIQUE NOT NULL,
            password_hash VARCHAR(255) NOT NULL,
            email VARCHAR(100),
            tier VARCHAR(20) DEFAULT 'default',
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        );
        CREATE TABLE IF NOT EXISTS user_keys (
            id VARCHAR(36) PRIMARY KEY,
            user_id VARCHAR(36) NOT NULL,
            key VARCHAR(255) UNIQUE NOT NULL,
            tier VARCHAR(20) DEFAULT 'default',
            status VARCHAR(20) DEFAULT 'active',
            max_limit INT DEFAULT 60,
            used INT DEFAULT 0,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            expires_at TIMESTAMP
        );
        CREATE TABLE IF NOT EXISTS provider_keys (
            id VARCHAR(36) PRIMARY KEY,
            provider VARCHAR(50) NOT NULL,
            key VARCHAR(255) UNIQUE NOT NULL,
            model VARCHAR(50),
            auth_header VARCHAR(50),
            status VARCHAR(20) DEFAULT 'active',
            used INT DEFAULT 0,
            failed INT DEFAULT 0,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        );
        CREATE TABLE IF NOT EXISTS providers (
            id VARCHAR(36) PRIMARY KEY,
            name VARCHAR(50) UNIQUE NOT NULL,
            base_url VARCHAR(255) NOT NULL,
            status VARCHAR(20) DEFAULT 'active',
            health INT DEFAULT 100,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        );
        CREATE TABLE IF NOT EXISTS logs (
            id VARCHAR(36) PRIMARY KEY,
            type VARCHAR(20) NOT NULL,
            message TEXT NOT NULL,
            ip VARCHAR(45),
            user_id VARCHAR(36),
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        );
        CREATE TABLE IF NOT EXISTS models (
            id VARCHAR(36) PRIMARY KEY,
            provider_id VARCHAR(36) NOT NULL,
            name VARCHAR(100) NOT NULL
        );
        """;

    private final Connection conn;

    public DatabaseManager(String path) {
        try {
            Path p = Path.of(path);
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            conn = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA foreign_keys=ON");
                st.execute("PRAGMA busy_timeout=5000");
            }
            initSchema();
        } catch (SQLException | java.io.IOException e) {
            throw new RuntimeException("Failed to open database at " + path + ": " + e.getMessage(), e);
        }
    }

    private void initSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate(SCHEMA);
            // Migration: older databases lack the auth_header column.
            try {
                st.executeUpdate("ALTER TABLE provider_keys ADD COLUMN auth_header VARCHAR(50)");
            } catch (SQLException ignored) {
                // column already exists
            }
        }
    }

    // ---- records ----

    public record User(String id, String username, String passwordHash, String email,
                       String tier, String createdAt, String updatedAt) {
    }

    public record UserKey(String id, String userId, String key, String tier, String status,
                          int maxLimit, int used, String createdAt, String expiresAt) {
    }

    public record Provider(String id, String name, String baseUrl, String status,
                           int health, String createdAt) {
    }

    public record ProviderKey(String id, String provider, String key, String model,
                              String authHeader, String status, int used, int failed,
                              String createdAt) {
    }

    public record LogEntry(String id, String type, String message, String ip,
                           String userId, String createdAt) {
    }

    // ---- helpers ----

    private static String now() {
        return LocalDateTime.now().format(DB_TIMESTAMP);
    }

    private static String parseTs(String raw) {
        return raw == null ? null : raw;
    }

    private User mapUser(ResultSet rs) throws SQLException {
        return new User(rs.getString("id"), rs.getString("username"), rs.getString("password_hash"),
                rs.getString("email"), rs.getString("tier"), parseTs(rs.getString("created_at")),
                parseTs(rs.getString("updated_at")));
    }

    private UserKey mapUserKey(ResultSet rs) throws SQLException {
        return new UserKey(rs.getString("id"), rs.getString("user_id"), rs.getString("key"),
                rs.getString("tier"), rs.getString("status"), rs.getInt("max_limit"),
                rs.getInt("used"), parseTs(rs.getString("created_at")), parseTs(rs.getString("expires_at")));
    }

    private Provider mapProvider(ResultSet rs) throws SQLException {
        return new Provider(rs.getString("id"), rs.getString("name"), rs.getString("base_url"),
                rs.getString("status"), rs.getInt("health"), parseTs(rs.getString("created_at")));
    }

    private ProviderKey mapProviderKey(ResultSet rs) throws SQLException {
        return new ProviderKey(rs.getString("id"), rs.getString("provider"), rs.getString("key"),
                rs.getString("model"), rs.getString("auth_header"), rs.getString("status"),
                rs.getInt("used"), rs.getInt("failed"), parseTs(rs.getString("created_at")));
    }

    private LogEntry mapLog(ResultSet rs) throws SQLException {
        return new LogEntry(rs.getString("id"), rs.getString("type"), rs.getString("message"),
                rs.getString("ip"), rs.getString("user_id"), parseTs(rs.getString("created_at")));
    }

    // ---- users ----

    public User getUserByName(String username) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM users WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapUser(rs) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public User getUserById(String id) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM users WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapUser(rs) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean addUser(String username, String passwordHash, String email, String tier) {
        String sql = "INSERT INTO users (id, username, password_hash, email, tier, created_at, updated_at) VALUES (?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, username);
            ps.setString(3, passwordHash);
            ps.setString(4, email);
            ps.setString(5, tier);
            ps.setString(6, now());
            ps.setString(7, now());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean deleteUser(String id) {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM users WHERE id = ?")) {
            ps.setString(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean setUserTier(String id, String tier) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE users SET tier = ?, updated_at = ? WHERE id = ?")) {
            ps.setString(1, tier);
            ps.setString(2, now());
            ps.setString(3, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<User> listUsers() {
        List<User> users = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM users ORDER BY username")) {
            while (rs.next()) {
                users.add(mapUser(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return users;
    }

    // ---- user keys ----

    public List<UserKey> listUserKeys() {
        List<UserKey> keys = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM user_keys ORDER BY created_at DESC")) {
            while (rs.next()) {
                keys.add(mapUserKey(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return keys;
    }

    public boolean addUserKey(String userId, String key, String tier, int maxLimit, LocalDateTime expiresAt) {
        String sql = "INSERT INTO user_keys (id, user_id, key, tier, status, max_limit, used, created_at, expires_at) VALUES (?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, userId);
            ps.setString(3, key);
            ps.setString(4, tier);
            ps.setString(5, "active");
            ps.setInt(6, maxLimit);
            ps.setInt(7, 0);
            ps.setString(8, now());
            ps.setString(9, expiresAt == null ? null : expiresAt.format(DB_TIMESTAMP));
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean revokeUserKey(String keyValue) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE user_keys SET status = 'revoked' WHERE key = ?")) {
            ps.setString(1, keyValue);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ---- providers ----

    public List<Provider> listProviders() {
        List<Provider> providers = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM providers ORDER BY name")) {
            while (rs.next()) {
                providers.add(mapProvider(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return providers;
    }

    public Provider getProviderByName(String name) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM providers WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapProvider(rs) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean addProvider(String name, String baseUrl) {
        String sql = "INSERT INTO providers (id, name, base_url, status, health, created_at) VALUES (?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, name);
            ps.setString(3, baseUrl);
            ps.setString(4, "active");
            ps.setInt(5, 100);
            ps.setString(6, now());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean addModel(String providerName, String model) {
        Provider provider = getProviderByName(providerName);
        if (provider == null) {
            return false;
        }
        String sql = "INSERT INTO models (id, provider_id, name) VALUES (?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, provider.id());
            ps.setString(3, model);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<String> listModels(String providerName) {
        List<String> models = new ArrayList<>();
        Provider provider = getProviderByName(providerName);
        if (provider == null) {
            return models;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT name FROM models WHERE provider_id = ? ORDER BY name")) {
            ps.setString(1, provider.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    models.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return models;
    }

    /**
     * Bulk-add models for a provider, skipping names that already exist.
     * Returns the number of models actually added, or -1 if the provider does
     * not exist.
     */
    public int importModels(String providerName, List<String> models) {
        Provider provider = getProviderByName(providerName);
        if (provider == null) {
            return -1;
        }
        List<String> existing = listModels(providerName);
        int added = 0;
        for (String raw : models) {
            String name = raw.trim();
            if (name.isEmpty() || existing.contains(name)) {
                continue;
            }
            addModel(providerName, name);
            existing.add(name);
            added++;
        }
        return added;
    }

    // ---- provider keys ----

    public List<ProviderKey> listProviderKeys() {
        List<ProviderKey> keys = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM provider_keys ORDER BY provider, created_at")) {
            while (rs.next()) {
                keys.add(mapProviderKey(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return keys;
    }

    public boolean addProviderKey(String provider, String key, String model, String authHeader) {
        String sql = "INSERT INTO provider_keys (id, provider, key, model, auth_header, status, used, failed, created_at) VALUES (?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, provider);
            ps.setString(3, key);
            ps.setString(4, model);
            ps.setString(5, authHeader);
            ps.setString(6, "active");
            ps.setInt(7, 0);
            ps.setInt(8, 0);
            ps.setString(9, now());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean removeProviderKey(String keyValue) {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM provider_keys WHERE key = ?")) {
            ps.setString(1, keyValue);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ---- logs ----

    public void log(String type, String message, String ip, String userId) {
        String sql = "INSERT INTO logs (id, type, message, ip, user_id, created_at) VALUES (?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, type);
            ps.setString(3, message);
            ps.setString(4, ip);
            ps.setString(5, userId);
            ps.setString(6, now());
            ps.executeUpdate();
        } catch (SQLException e) {
            // logging must never break the caller
            System.err.println("[db] failed to write log: " + e.getMessage());
        }
    }

    public List<LogEntry> listLogs(int limit) {
        List<LogEntry> logs = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM logs ORDER BY created_at DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    logs.add(mapLog(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return logs;
    }

    // ---- stats ----

    public long countUsers() {
        return scalar("SELECT COUNT(*) FROM users");
    }

    public long countUserKeys() {
        return scalar("SELECT COUNT(*) FROM user_keys");
    }

    public long countActiveUserKeys() {
        return scalar("SELECT COUNT(*) FROM user_keys WHERE status = 'active'");
    }

    public long countProviders() {
        return scalar("SELECT COUNT(*) FROM providers");
    }

    public long countProviderKeys() {
        return scalar("SELECT COUNT(*) FROM provider_keys");
    }

    public long countLogs() {
        return scalar("SELECT COUNT(*) FROM logs");
    }

    private long scalar(String sql) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {
        }
    }
}