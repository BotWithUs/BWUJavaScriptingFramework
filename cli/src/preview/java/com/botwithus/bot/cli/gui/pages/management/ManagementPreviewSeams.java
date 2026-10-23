package com.botwithus.bot.cli.gui.pages.management;

import com.botwithus.bot.cli.gui.pages.management.ManagementState.DetailTab;
import com.botwithus.bot.cli.management.Target;

import java.nio.file.Path;

/**
 * The dev preview's reach into the Management page, standing in for the
 * clicks a user would make: pick a script and tab, arm Stop all, open a stack
 * trace, set the add-target row, ask for a target change. Lives in the preview
 * source set only; nothing here ships.
 */
public final class ManagementPreviewSeams {

    private ManagementPreviewSeams() {}

    public static void selectOverview(ManagementPage page, String script) {
        page.select(script, DetailTab.OVERVIEW);
    }

    public static void selectSettings(ManagementPage page, String script) {
        page.select(script, DetailTab.SETTINGS);
    }

    public static void selectActivity(ManagementPage page, String script) {
        page.select(script, DetailTab.ACTIVITY);
    }

    public static void armStopAll(ManagementPage page) {
        page.armStopAll();
    }

    public static void openTrace(ManagementPage page, Path jar) {
        page.openTrace(jar);
    }

    /** Sets the add row on {@code script} to the group kind, with its {@code pick}th option chosen. */
    public static void pickGroup(ManagementPage page, String script, int pick) {
        page.pickAddKind(script, TargetKind.GROUP.ordinal(), pick);
    }

    /** Asks to add {@code target} to the selected script, as the add row's Add would. */
    public static void requestAdd(ManagementPage page, Target target, String label) {
        page.requestAdd(target, label);
    }
}
