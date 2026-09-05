package com.api.pool.cli;

import com.api.pool.auth.AuthManager;
import com.api.pool.auth.TokenManager;
import com.api.pool.config.ConfigManager;
import com.api.pool.db.DatabaseManager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;

/**
 * Command-line handler for the admin layer. Holds all business logic; both the
 * CLI and the TUI route through it, so behavior stays identical.
 */
public class CLIHandler {

    public static final String VERSION = "1.0.0";

    private final ConfigManager config;
    private final DatabaseManager db;
    private final AuthManager auth;
    private final TokenManager tokens;
    private final Instant startedAt = Instant.now();

    public CLIHandler(ConfigManager config, DatabaseManager db, AuthManager auth, TokenManager tokens) {
        this.config = config;
        this.db = db;
        this.auth = auth;
        this.tokens = tokens;
    }

    public boolean isLoggedIn() {
        return auth.isLoggedIn();
    }

    // ---------------------------------------------------------------
    // dispatch
    // ---------------------------------------------------------------

    /** Execute one command (given as argv) and return the exit code. */
    public int execute(String[] args) {
        String out = handle(args);
        if (!out.isEmpty()) {
            System.out.println(out);
        }
        return out.startsWith("error:") ? 1 : 0;
    }

    /** Execute one command and return the output text. */
    public String handle(String[] args) {
        if (args == null || args.length == 0 || args[0].isBlank()) {
            return "error: no command given. Type 'help' for usage.";
        }
        String cmd = args[0];
        Map<String, String> opts = parseOptions(args);

        if (Commands.requiresLogin(cmd) && !auth.isLoggedIn()) {
            return "error: not logged in. Run: login --username <user> --password <pass>";
        }

        return switch (cmd) {
            case Commands.LOGIN -> cmdLogin(opts);
            case Commands.LOGOUT -> cmdLogout();
            case Commands.ADD_PROVIDER -> cmdAddProvider(opts);
            case Commands.ADD_MODEL -> cmdAddModel(opts);
            case Commands.ADD_KEY -> cmdAddKey(opts);
            case Commands.REMOVE_PROVIDER_KEY -> cmdRemoveProviderKey(opts);
            case Commands.IMPORT_MODELS -> cmdImportModels(opts);
            case Commands.GENERATE_KEY -> cmdGenerateKey(opts);
            case Commands.LIST_KEYS -> cmdListKeys();
            case Commands.REVOKE_KEY -> cmdRevokeKey(opts);
            case Commands.SYSTEM_INFO -> cmdSystemInfo();
            case Commands.DASHBOARD -> cmdDashboard();
            case Commands.ADD_USER -> cmdAddUser(opts);
            case Commands.LIST_USERS -> cmdListUsers();
            case Commands.SET_TIER -> cmdSetTier(opts);
            case Commands.DELETE_USER -> cmdDeleteUser(opts);
            case Commands.LIST_PROVIDERS -> cmdListProviders();
            case Commands.LIST_PROVIDER_KEYS -> cmdListProviderKeys();
            case Commands.LOGS -> cmdLogs();
            case Commands.HELP -> Commands.HELP_TEXT;
            case Commands.EXIT, Commands.QUIT -> "";
            default -> "error: unknown command '" + cmd + "'. Type 'help' for usage.";
        };
    }

    /** Interactive REPL. */
    public void runInteractive() {
        System.out.println("API Pooling System - Admin CLI v" + VERSION);
        System.out.println("Type 'help' for commands, 'exit' to quit.");
        if (!auth.isLoggedIn()) {
            System.out.println("You are not logged in. Run: login --username admin --password admin");
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while (true) {
                System.out.print("api-pool> ");
                System.out.flush();
                line = reader.readLine();
                if (line == null) {
                    System.out.println();
                    break;
                }
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] args = splitArgs(line);
                if (args[0].equals(Commands.EXIT) || args[0].equals(Commands.QUIT)) {
                    break;
                }
                String out = handle(args);
                if (!out.isEmpty()) {
                    System.out.println(out);
                }
            }
        } catch (Exception e) {
            System.err.println("error: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------
    // commands
    // ---------------------------------------------------------------

    private String cmdLogin(Map<String, String> opts) {
        String user = opts.get("username");
        String pass = opts.get("password");
        if (user == null || pass == null) {
            return "error: usage: login --username <user> --password <pass>";
        }
        if (auth.login(user, pass)) {
            String expires = tokens.expiry() != null
                    ? " (expires " + DateTimeFormatter.ISO_INSTANT.format(tokens.expiry()) + ")"
                    : "";
            return "Login successful. Token: " + tokens.currentToken() + expires;
        }
        return "error: invalid username or password";
    }

    private String cmdLogout() {
        auth.logout();
        return "Logged out.";
    }

    private String cmdAddProvider(Map<String, String> opts) {
        String name = opts.get("name");
        String url = opts.get("url");
        if (name == null || url == null) {
            return "error: usage: add-provider --name <name> --url <url>";
        }
        if (db.getProviderByName(name) != null) {
            return "error: provider '" + name + "' already exists";
        }
        db.addProvider(name, url);
        db.log("provider", "added provider " + name + " (" + url + ")", null, null);
        return "Provider '" + name + "' added (" + url + ").";
    }

    private String cmdAddModel(Map<String, String> opts) {
        String provider = opts.get("provider");
        String model = opts.get("model");
        if (provider == null || model == null) {
            return "error: usage: add-model --provider <name> --model <model>";
        }
        if (db.getProviderByName(provider) == null) {
            return "error: provider '" + provider + "' not found";
        }
        db.addModel(provider, model);
        db.log("provider", "added model " + model + " to provider " + provider, null, null);
        return "Model '" + model + "' added to provider '" + provider + "'.";
    }

    private String cmdAddKey(Map<String, String> opts) {
        String provider = opts.get("provider");
        String key = opts.get("key");
        String model = opts.get("model");
        String authHeader = opts.get("auth-header");
        if (provider == null || key == null) {
            return "error: usage: add-key --provider <name> --key <api-key> [--model <model>] [--auth-header <header>]";
        }
        if (db.getProviderByName(provider) == null) {
            return "error: provider '" + provider + "' not found";
        }
        db.addProviderKey(provider, key, model, authHeader);
        db.log("provider", "added key for provider " + provider, null, null);
        String authNote = (authHeader == null || authHeader.isEmpty())
                ? " (Authorization: Bearer)"
                : " (" + authHeader + ")";
        return "API key added to provider '" + provider + "'" + authNote + ".";
    }

    private String cmdRemoveProviderKey(Map<String, String> opts) {
        String key = opts.get("key");
        if (key == null) {
            return "error: usage: remove-provider-key --key <api-key>";
        }
        if (db.removeProviderKey(key)) {
            return "Provider key removed.";
        }
        return "error: provider key not found";
    }

    private String cmdImportModels(Map<String, String> opts) {
        String provider = opts.get("provider");
        String file = opts.get("file");
        if (provider == null || file == null) {
            return "error: usage: import-models --provider <name> --file <path>";
        }
        if (db.getProviderByName(provider) == null) {
            return "error: provider '" + provider + "' not found";
        }
        List<String> models;
        try {
            models = parseModelsFile(file);
        } catch (IOException e) {
            return "error: cannot read file '" + file + "': " + e.getMessage();
        } catch (Exception e) {
            return "error: invalid model file '" + file + "': " + e.getMessage();
        }
        if (models.isEmpty()) {
            return "error: no models found in '" + file + "'";
        }
        int added = db.importModels(provider, models);
        db.log("provider", "imported " + added + " models for provider " + provider, null, null);
        return "Imported " + added + " models into provider '" + provider + "'"
                + " (" + (models.size() - added) + " skipped as duplicates).";
    }

    /**
     * Parse a model list file. Supported formats:
     *  - JSON: an array of strings, an array of objects with a "name" field,
     *    or an object with a "models" key containing either
     *  - plain text: one model name per line
     */
    private static List<String> parseModelsFile(String path) throws IOException {
        String content = Files.readString(Path.of(path), StandardCharsets.UTF_8).trim();
        if (content.isEmpty()) {
            return List.of();
        }
        if (content.startsWith("[") || content.startsWith("{")) {
            JsonElement root = JsonParser.parseString(content);
            JsonArray arr;
            if (root.isJsonArray()) {
                arr = root.getAsJsonArray();
            } else if (root.isJsonObject() && root.getAsJsonObject().has("models")) {
                arr = root.getAsJsonObject().getAsJsonArray("models");
            } else {
                throw new IllegalArgumentException("expected a JSON array or an object with a 'models' key");
            }
            List<String> out = new ArrayList<>();
            for (JsonElement el : arr) {
                if (el.isJsonPrimitive()) {
                    out.add(el.getAsString());
                } else if (el.isJsonObject() && el.getAsJsonObject().has("name")) {
                    out.add(el.getAsJsonObject().get("name").getAsString());
                }
            }
            return out;
        }
        // plain text: one model per line
        return content.lines().map(String::trim).filter(l -> !l.isEmpty()).toList();
    }

    private String cmdGenerateKey(Map<String, String> opts) {
        String userRef = opts.get("user");
        String tier = opts.get("tier");
        if (userRef == null) {
            return "error: usage: generate-key --user <user_id> --tier <tier>";
        }
        DatabaseManager.User user = db.getUserById(userRef);
        if (user == null) {
            user = db.getUserByName(userRef);
        }
        if (user == null) {
            return "error: user '" + userRef + "' not found";
        }
        String effectiveTier = tier != null ? tier : user.tier();
        String generated = "pkay_" + HexFormat.of().formatHex(randomBytes(24));
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(config.keyExpiryDays());
        db.addUserKey(user.id(), generated, effectiveTier, config.defaultKeyLimit(), expiresAt);
        db.log("key", "generated key for user " + user.username(), null, user.id());
        return "Generated key for user '" + user.username() + "' (tier: " + effectiveTier + "):\n"
                + generated + "\n"
                + "Expires: " + expiresAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private String cmdListKeys() {
        List<DatabaseManager.UserKey> keys = db.listUserKeys();
        if (keys.isEmpty()) {
            return "No keys yet.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-40s %-10s %-10s %-8s %-6s %-6s %s%n",
                "KEY", "USER", "TIER", "STATUS", "USED", "LIMIT", "EXPIRES"));
        sb.append("-".repeat(100)).append('\n');
        for (DatabaseManager.UserKey k : keys) {
            sb.append(String.format("%-40s %-10s %-10s %-8s %-6d %-6d %s%n",
                    k.key(), k.userId(), k.tier(), k.status(), k.used(), k.maxLimit(),
                    k.expiresAt() == null ? "never" : k.expiresAt()));
        }
        return sb.toString().stripTrailing();
    }

    private String cmdRevokeKey(Map<String, String> opts) {
        String key = opts.get("key");
        if (key == null) {
            return "error: usage: revoke-key --key <key>";
        }
        if (db.revokeUserKey(key)) {
            db.log("key", "revoked key " + key, null, null);
            return "Key revoked.";
        }
        return "error: key not found";
    }

    private String cmdSystemInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("API Pooling System v").append(VERSION).append('\n');
        sb.append("Base URL   : ").append(config.baseUrl()).append('\n');
        sb.append("Status     : running\n");
        sb.append("Database   : ").append(config.dbPath()).append('\n');
        sb.append("Proxy Core : rust-core (proxy-server)\n");
        sb.append("Admin      : java-admin (this tool)\n");
        long up = java.time.Duration.between(startedAt, Instant.now()).toSeconds();
        sb.append("Uptime     : ").append(up).append("s\n");
        sb.append("Logged in  : ").append(auth.isLoggedIn() ? "yes" : "no").append('\n');
        sb.append("Rate limit window: ").append(config.defaultKeyLimit())
                .append(" req / ").append(config.keyExpiryDays()).append(" day key expiry (defaults)");
        return sb.toString();
    }

    private String cmdDashboard() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Dashboard ===\n");
        sb.append(String.format("Users          : %d%n", db.countUsers()));
        sb.append(String.format("User Keys      : %d (active: %d)%n", db.countUserKeys(), db.countActiveUserKeys()));
        sb.append(String.format("Providers      : %d%n", db.countProviders()));
        sb.append(String.format("Provider Keys  : %d%n", db.countProviderKeys()));
        sb.append(String.format("Log Entries    : %d%n", db.countLogs()));
        sb.append('\n').append("Recent Logs:\n");
        List<DatabaseManager.LogEntry> logs = db.listLogs(10);
        if (logs.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (DatabaseManager.LogEntry l : logs) {
                sb.append(String.format("  [%s] %s: %s%n", l.createdAt(), l.type(), l.message()));
            }
        }
        return sb.toString().stripTrailing();
    }

    private String cmdAddUser(Map<String, String> opts) {
        String username = opts.get("username");
        String password = opts.get("password");
        if (username == null || password == null) {
            return "error: usage: add-user --username <user> --password <pass> [--email <email>] [--tier <tier>]";
        }
        if (db.getUserByName(username) != null) {
            return "error: user '" + username + "' already exists";
        }
        String email = opts.get("email");
        String tier = opts.get("tier");
        db.addUser(username, AuthManager.hashPassword(password), email, tier != null ? tier : "default");
        db.log("user", "created user " + username, null, null);
        return "User '" + username + "' created (tier: " + (tier != null ? tier : "default") + ").";
    }

    private String cmdListUsers() {
        List<DatabaseManager.User> users = db.listUsers();
        if (users.isEmpty()) {
            return "No users yet.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-38s %-15s %-12s %-30s %s%n", "ID", "USERNAME", "TIER", "EMAIL", "CREATED"));
        sb.append("-".repeat(120)).append('\n');
        for (DatabaseManager.User u : users) {
            sb.append(String.format("%-38s %-15s %-12s %-30s %s%n",
                    u.id(), u.username(), u.tier(),
                    u.email() == null ? "-" : u.email(),
                    u.createdAt() == null ? "-" : u.createdAt()));
        }
        return sb.toString().stripTrailing();
    }

    private String cmdSetTier(Map<String, String> opts) {
        String userRef = opts.get("user");
        String tier = opts.get("tier");
        if (userRef == null || tier == null) {
            return "error: usage: set-tier --user <user_id> --tier <tier>";
        }
        DatabaseManager.User user = db.getUserById(userRef);
        if (user == null) {
            user = db.getUserByName(userRef);
        }
        if (user == null) {
            return "error: user '" + userRef + "' not found";
        }
        db.setUserTier(user.id(), tier);
        db.log("user", "set tier " + tier + " for user " + user.username(), null, user.id());
        return "Tier for '" + user.username() + "' set to '" + tier + "'.";
    }

    private String cmdDeleteUser(Map<String, String> opts) {
        String userRef = opts.get("user");
        if (userRef == null) {
            return "error: usage: delete-user --user <user_id>";
        }
        DatabaseManager.User user = db.getUserById(userRef);
        if (user == null) {
            user = db.getUserByName(userRef);
        }
        if (user == null) {
            return "error: user '" + userRef + "' not found";
        }
        db.deleteUser(user.id());
        db.log("user", "deleted user " + user.username(), null, user.id());
        return "User '" + user.username() + "' deleted.";
    }

    private String cmdListProviders() {
        List<DatabaseManager.Provider> providers = db.listProviders();
        if (providers.isEmpty()) {
            return "No providers yet. Run: add-provider --name <name> --url <url>";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-15s %-40s %-10s %-8s %s%n", "NAME", "BASE URL", "STATUS", "HEALTH", "MODELS"));
        sb.append("-".repeat(120)).append('\n');
        for (DatabaseManager.Provider p : providers) {
            String models = String.join(", ", db.listModels(p.name()));
            sb.append(String.format("%-15s %-40s %-10s %-8d %s%n",
                    p.name(), p.baseUrl(), p.status(), p.health(), models.isEmpty() ? "-" : models));
        }
        return sb.toString().stripTrailing();
    }

    private String cmdListProviderKeys() {
        List<DatabaseManager.ProviderKey> keys = db.listProviderKeys();
        if (keys.isEmpty()) {
            return "No provider keys yet. Run: add-key --provider <name> --key <api-key>";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-15s %-20s %-18s %-12s %-10s %-6s %-6s %s%n",
                "PROVIDER", "KEY", "AUTH HEADER", "MODEL", "STATUS", "USED", "FAILED", "CREATED"));
        sb.append("-".repeat(130)).append('\n');
        for (DatabaseManager.ProviderKey k : keys) {
            sb.append(String.format("%-15s %-20s %-18s %-12s %-10s %-6d %-6d %s%n",
                    k.provider(), k.key(),
                    k.authHeader() == null || k.authHeader().isEmpty() ? "authorization" : k.authHeader(),
                    k.model() == null ? "-" : k.model(),
                    k.status(), k.used(), k.failed(), k.createdAt() == null ? "-" : k.createdAt()));
        }
        return sb.toString().stripTrailing();
    }

    private String cmdLogs() {
        List<DatabaseManager.LogEntry> logs = db.listLogs(25);
        if (logs.isEmpty()) {
            return "No logs yet.";
        }
        StringBuilder sb = new StringBuilder();
        for (DatabaseManager.LogEntry l : logs) {
            sb.append(String.format("[%s] %-10s %s%s%n",
                    l.createdAt(), l.type(), l.message(),
                    l.ip() != null ? " (ip: " + l.ip() + ")" : ""));
        }
        return sb.toString().stripTrailing();
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------

    /** Parse `--opt value` pairs into a map (positional args are skipped). */
    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length) {
                String key = args[i].substring(2);
                String value = args[i + 1];
                if (value.startsWith("--")) {
                    opts.put(key, "");
                } else {
                    opts.put(key, value);
                    i++;
                }
            }
        }
        return opts;
    }

    /** Split a command line on whitespace, honoring simple double quotes. */
    private static String[] splitArgs(String line) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (char c : line.toCharArray()) {
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (Character.isWhitespace(c) && !inQuotes) {
                if (cur.length() > 0) {
                    parts.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            parts.add(cur.toString());
        }
        return parts.toArray(new String[0]);
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }
}