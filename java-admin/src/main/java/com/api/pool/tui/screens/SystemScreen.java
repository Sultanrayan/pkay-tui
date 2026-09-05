package com.api.pool.tui.screens;

import com.api.pool.cli.CLIHandler;

/**
 * System info screen for the TUI.
 */
public class SystemScreen {

    private final CLIHandler cli;

    public SystemScreen(CLIHandler cli) {
        this.cli = cli;
    }

    public void show() {
        System.out.println();
        System.out.println(cli.handle(new String[]{"system-info"}));
        System.out.println();
    }
}