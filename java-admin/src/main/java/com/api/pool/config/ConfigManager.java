package com.api.pool.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads the shared {@code config.json}. Prefers a local file in the working
 * directory and falls back to the bundled resource inside the jar.
 */
public class ConfigManager {

    private final JsonObject root;

    public ConfigManager() {
        this.root = load();
    }

    private JsonObject load() {
        Path local = Path.of("config.json");
        if (Files.exists(local)) {
            try (InputStream in = Files.newInputStream(local)) {
                return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read config.json: " + e.getMessage(), e);
            }
        }
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.json")) {
            if (in == null) {
                throw new IllegalStateException("No config.json found in working directory or classpath");
            }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load bundled config.json: " + e.getMessage(), e);
        }
    }

    private JsonObject section(String name) {
        JsonObject obj = root.getAsJsonObject(name);
        return obj != null ? obj : new JsonObject();
    }

    public String proxyHost() {
        return str(section("proxy"), "host", "0.0.0.0");
    }

    public int proxyPort() {
        return intVal(section("proxy"), "port", 8080);
    }

    public String baseUrl() {
        String host = proxyHost();
        String displayHost = host.equals("0.0.0.0") || host.equals("::") ? "localhost" : host;
        return "http://" + displayHost + ":" + proxyPort();
    }

    public String dbPath() {
        return str(section("database"), "path", "database/db.sqlite");
    }

    public String adminUsername() {
        return str(section("admin"), "username", "admin");
    }

    public String adminPassword() {
        return str(section("admin"), "password", "admin");
    }

    public int tokenDurationDays() {
        return intVal(section("admin"), "token_duration_days", 7);
    }

    public int keyExpiryDays() {
        return intVal(section("defaults"), "key_expiry_days", 30);
    }

    public int defaultKeyLimit() {
        return intVal(section("defaults"), "default_key_limit", 60);
    }

    public String sessionFile() {
        return ".admin_session";
    }

    private static String str(JsonObject obj, String key, String fallback) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return fallback;
    }

    private static int intVal(JsonObject obj, String key, int fallback) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            try {
                return obj.get(key).getAsInt();
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return fallback;
    }
}