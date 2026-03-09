package com.botwithus.bot.cli.gui;

import com.botwithus.bot.cli.CliContext;

/**
 * Interface for tabbed GUI panels in the main application window.
 */
public interface GuiPanel {

    /** The tab title displayed in the tab bar. */
    String title();

    /** Render the panel content. Called every frame when this tab is selected. */
    void render(CliContext ctx);
}
