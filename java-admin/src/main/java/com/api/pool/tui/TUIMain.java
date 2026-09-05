package com.api.pool.tui;

import com.api.pool.cli.CLIHandler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Text UI for the admin layer: a menu-driven interface built on top of the
 * same command logic as the CLI.
 */
public class TUIMain {

    private final CLIHandler cli;
    private final BufferedReader reader;

    public TUIMain(CLIHandler cli) {
        this.cli = cli;
        this.reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    }

    public void run() {
        System.out.println();
        System.out.println("===========================================");
        System.out.println("   API Pooling System - Admin TUI v" + CLIHandler.VERSION);
        System.out.println("===========================================");
        boolean running = true;
        while (running) {
            printMenu();
            String choice = prompt("Select an option");
            switch (choice) {
                case "1" -> runLogin();
                case "2" -> runDashboard();
                case "3" -> runUsers();
                case "4" -> runKeys();
                case "5" -> runProviders();
                case "6" -> runSystemInfo();
                case "7" -> runLogs();
                case "8" -> runLogout();
                case "9", "q", "Q" -> {
                    System.out.println("Bye.");
                    running = false;
                }
                default -> System.out.println("Invalid option. Choose 1-9 or q.\n");
            }
        }
    }

    private void printMenu() {
        boolean loggedIn = cli.isLoggedIn();
        System.out.println();
        System.out.println("----- Main Menu -----");
        System.out.println(" 1. Login");
        System.out.println(" 2. Dashboard");
        System.out.println(" 3. Users");
        System.out.println(" 4. Keys");
        System.out.println(" 5. Providers");
        System.out.println(" 6. System Info");
        System.out.println(" 7. Logs");
        System.out.println(" 8. Logout");
        System.out.println(" 9. Exit");
        System.out.println("---------------------");
        System.out.println("Status: " + (loggedIn ? "logged in" : "not logged in"));
    }

    private void runLogin() {
        String username = prompt("Username");
        String password = prompt("Password");
        String out = cliHandle(new String[]{"login", "--username", username, "--password", password});
        System.out.println(out);
    }

    private void runDashboard() {
        showOutput(cliHandle(new String[]{"dashboard"}));
    }

    private void runSystemInfo() {
        showOutput(cliHandle(new String[]{"system-info"}));
    }

    private void runLogs() {
        showOutput(cliHandle(new String[]{"logs"}));
    }

    private void runLogout() {
        System.out.println(cliHandle(new String[]{"logout"}));
    }

    private void runUsers() {
        boolean back = false;
        while (!back) {
            System.out.println();
            System.out.println("--- Users ---");
            System.out.println(" 1. List users");
            System.out.println(" 2. Add user");
            System.out.println(" 3. Set tier");
            System.out.println(" 4. Delete user");
            System.out.println(" 5. Back");
            switch (prompt("Select")) {
                case "1" -> showOutput(cliHandle(new String[]{"list-users"}));
                case "2" -> {
                    String username = prompt("Username");
                    String password = prompt("Password");
                    String tier = prompt("Tier (default)");
                    String email = prompt("Email (optional)");
                    showOutput(cliHandle(new String[]{"add-user", "--username", username,
                            "--password", password, "--tier", tier, "--email", email}));
                }
                case "3" -> {
                    String user = prompt("User id");
                    String tier = prompt("New tier");
                    showOutput(cliHandle(new String[]{"set-tier", "--user", user, "--tier", tier}));
                }
                case "4" -> {
                    String user = prompt("User id");
                    showOutput(cliHandle(new String[]{"delete-user", "--user", user}));
                }
                case "5" -> back = true;
                default -> System.out.println("Invalid option.");
            }
        }
    }

    private void runKeys() {
        boolean back = false;
        while (!back) {
            System.out.println();
            System.out.println("--- Keys ---");
            System.out.println(" 1. List keys");
            System.out.println(" 2. Generate key for user");
            System.out.println(" 3. Revoke key");
            System.out.println(" 4. Back");
            switch (prompt("Select")) {
                case "1" -> showOutput(cliHandle(new String[]{"list-keys"}));
                case "2" -> {
                    String user = prompt("User id");
                    String tier = prompt("Tier");
                    showOutput(cliHandle(new String[]{"generate-key", "--user", user, "--tier", tier}));
                }
                case "3" -> {
                    String key = prompt("Key value");
                    showOutput(cliHandle(new String[]{"revoke-key", "--key", key}));
                }
                case "4" -> back = true;
                default -> System.out.println("Invalid option.");
            }
        }
    }

    private void runProviders() {
        boolean back = false;
        while (!back) {
            System.out.println();
            System.out.println("--- Providers ---");
            System.out.println(" 1. List providers");
            System.out.println(" 2. Add provider");
            System.out.println(" 3. Add model");
            System.out.println(" 4. Import models from file");
            System.out.println(" 5. Add provider key");
            System.out.println(" 6. List provider keys");
            System.out.println(" 7. Back");
            switch (prompt("Select")) {
                case "1" -> showOutput(cliHandle(new String[]{"list-providers"}));
                case "2" -> {
                    String name = prompt("Name");
                    String url = prompt("Base URL");
                    showOutput(cliHandle(new String[]{"add-provider", "--name", name, "--url", url}));
                }
                case "3" -> {
                    String provider = prompt("Provider name");
                    String model = prompt("Model");
                    showOutput(cliHandle(new String[]{"add-model", "--provider", provider, "--model", model}));
                }
                case "4" -> {
                    String provider = prompt("Provider name");
                    String file = prompt("Path to model file");
                    showOutput(cliHandle(new String[]{"import-models", "--provider", provider, "--file", file}));
                }
                case "5" -> {
                    String provider = prompt("Provider name");
                    String key = prompt("API key");
                    String model = prompt("Model (optional)");
                    String authHeader = prompt("Auth header (optional, e.g. x-goog-api-key)");
                    showOutput(cliHandle(new String[]{"add-key", "--provider", provider,
                            "--key", key, "--model", model, "--auth-header", authHeader}));
                }
                case "6" -> showOutput(cliHandle(new String[]{"list-provider-keys"}));
                case "7" -> back = true;
                default -> System.out.println("Invalid option.");
            }
        }
    }

    private String prompt(String label) {
        System.out.print(label + "> ");
        System.out.flush();
        try {
            String line = reader.readLine();
            return line == null ? "" : line.trim();
        } catch (IOException e) {
            return "";
        }
    }

    private void showOutput(String out) {
        System.out.println();
        System.out.println(out);
        System.out.println();
    }

    private String cliHandle(String[] args) {
        return cli.handle(args);
    }
}