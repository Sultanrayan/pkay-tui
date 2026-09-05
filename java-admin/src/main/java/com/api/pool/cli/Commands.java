package com.api.pool.cli;

/**
 * Command names and help text for the admin CLI (mirrors the README command
 * table, plus a few extra user-management commands).
 */
public final class Commands {

    private Commands() {
    }

    public static final String LOGIN = "login";
    public static final String LOGOUT = "logout";
    public static final String ADD_PROVIDER = "add-provider";
    public static final String ADD_MODEL = "add-model";
    public static final String ADD_KEY = "add-key";
    public static final String IMPORT_MODELS = "import-models";
    public static final String REMOVE_PROVIDER_KEY = "remove-provider-key";
    public static final String GENERATE_KEY = "generate-key";
    public static final String LIST_KEYS = "list-keys";
    public static final String REVOKE_KEY = "revoke-key";
    public static final String SYSTEM_INFO = "system-info";
    public static final String DASHBOARD = "dashboard";
    public static final String ADD_USER = "add-user";
    public static final String LIST_USERS = "list-users";
    public static final String SET_TIER = "set-tier";
    public static final String DELETE_USER = "delete-user";
    public static final String LIST_PROVIDERS = "list-providers";
    public static final String LIST_PROVIDER_KEYS = "list-provider-keys";
    public static final String LOGS = "logs";
    public static final String HELP = "help";
    public static final String EXIT = "exit";
    public static final String QUIT = "quit";

    public static final String HELP_TEXT = """
        API Pooling System - Admin CLI
        ==============================

        Authentication:
          login --username <user> --password <pass>     Login as admin
          logout                                        Logout

        Providers:
          add-provider --name <name> --url <url>        Add a provider
          add-model --provider <name> --model <model>   Add a model to a provider
          add-key --provider <name> --key <api-key>     Add an API key to a provider
                                                          [--model <model>]
                                                          [--auth-header <header>]
                                                          (default header is 'authorization'
                                                          (Bearer); use 'x-goog-api-key' for Gemini)
          import-models --provider <name> --file <path> Import models from a file
                                                          (JSON array / {\"models\": [...]}
                                                          or one model per line)
          remove-provider-key --key <api-key>           Remove a provider key
          list-providers                                List providers
          list-provider-keys                            List provider keys

        Users:
          add-user --username <user> --password <pass>  Create a user
                                                          [--email <email>] [--tier <tier>]
          list-users                                    List users
          set-tier --user <user_id> --tier <tier>       Set a user's tier
          delete-user --user <user_id>                  Delete a user

        Keys:
          generate-key --user <user_id> --tier <tier>   Generate a key for a user
          list-keys                                     List all user keys
          revoke-key --key <key>                        Revoke a user key

        System:
          system-info                                   Show system information
          dashboard                                     Show dashboard
          logs                                          Show recent logs
          help                                          Show this help
          exit / quit                                   Leave interactive mode
        """;

    /** Commands that require an active admin session. */
    public static boolean requiresLogin(String cmd) {
        return switch (cmd) {
            case LOGIN, LOGOUT, SYSTEM_INFO, HELP, EXIT, QUIT -> false;
            default -> true;
        };
    }
}