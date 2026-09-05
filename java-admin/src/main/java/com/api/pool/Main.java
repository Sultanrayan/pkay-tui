package com.api.pool;

import com.api.pool.auth.AuthManager;
import com.api.pool.auth.TokenManager;
import com.api.pool.cli.CLIHandler;
import com.api.pool.config.ConfigManager;
import com.api.pool.db.DatabaseManager;
import com.api.pool.tui.TUIMain;

/**
 * Entry point for the Java admin layer.
 *
 * Usage:
 *   java -jar api-pool-admin.jar --cli            interactive CLI
 *   java -jar api-pool-admin.jar --tui            interactive TUI
 *   java -jar api-pool-admin.jar <command> ...    one-shot command
 *   java -jar api-pool-admin.jar --version
 *   java -jar api-pool-admin.jar --help
 */
public class Main {

    public static void main(String[] args) {
        ConfigManager config = new ConfigManager();
        DatabaseManager db = new DatabaseManager(config.dbPath());
        TokenManager tokens = new TokenManager(config.sessionFile(),
                config.tokenDurationDays() * 24L * 60 * 60 * 1000);
        AuthManager auth = new AuthManager(db, tokens);

        // First run: create the default admin user from config
        if (db.getUserByName(config.adminUsername()) == null) {
            db.addUser(config.adminUsername(), AuthManager.hashPassword(config.adminPassword()),
                    null, "admin");
            System.out.println("[setup] created default admin user '" + config.adminUsername() + "'");
        }

        CLIHandler cli = new CLIHandler(config, db, auth, tokens);

        try {
            if (args.length == 0 || args[0].equals("--cli") || args[0].equals("-c")) {
                cli.runInteractive();
            } else {
                switch (args[0]) {
                    case "--tui", "-t" -> new TUIMain(cli).run();
                    case "--version", "-v" -> System.out.println("API Pooling System v" + CLIHandler.VERSION);
                    case "--help", "-h" -> System.out.println(com.api.pool.cli.Commands.HELP_TEXT);
                    default -> System.exit(cli.execute(args));
                }
            }
        } finally {
            db.close();
        }
    }
}