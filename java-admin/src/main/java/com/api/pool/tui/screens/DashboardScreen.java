package com.api.pool.tui.screens;

import com.api.pool.cli.CLIHandler;

/**
 * Dashboard screen for the TUI - shows system status at a glance.
 */
public class DashboardScreen {

    private final CLIHandler cli;

    public DashboardScreen(CLIHandler cli) {
        this.cli = cli;
    }

    public void show() {
        System.out.println();
        System.out.println(cli.handle(new String[]{"dashboard"}));
        System.out.println();
    }
}