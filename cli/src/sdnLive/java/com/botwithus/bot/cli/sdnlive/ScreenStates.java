package com.botwithus.bot.cli.sdnlive;

import com.botwithus.bot.cli.gui.pages.store.InstallActivity;
import com.botwithus.bot.cli.gui.pages.store.RowState;
import com.botwithus.bot.cli.gui.usermode.board.SubscriptionState;

/**
 * What a screen's state means for the check, decided by exhaustive switches so
 * a state added to either screen fails to compile here rather than reading as
 * "not installed".
 */
final class ScreenStates {

    private ScreenStates() {
    }

    static boolean isInstalling(InstallActivity activity) {
        return switch (activity) {
            case InstallActivity.Installing ignored -> true;
            case InstallActivity.Idle ignored -> false;
            case InstallActivity.Finished ignored -> false;
            case InstallActivity.Failed ignored -> false;
        };
    }

    static boolean isFinished(InstallActivity activity) {
        return switch (activity) {
            case InstallActivity.Finished ignored -> true;
            case InstallActivity.Installing ignored -> false;
            case InstallActivity.Idle ignored -> false;
            case InstallActivity.Failed ignored -> false;
        };
    }

    static boolean isFailed(InstallActivity activity) {
        return switch (activity) {
            case InstallActivity.Failed ignored -> true;
            case InstallActivity.Installing ignored -> false;
            case InstallActivity.Idle ignored -> false;
            case InstallActivity.Finished ignored -> false;
        };
    }

    /** The Store calls it installed, with no update pending. */
    static boolean isInstalled(RowState state) {
        return switch (state) {
            case RowState.Installed ignored -> true;
            case RowState.NotInstalled ignored -> false;
            case RowState.UpdateAvailable ignored -> false;
        };
    }

    static boolean isNotInstalled(RowState state) {
        return switch (state) {
            case RowState.NotInstalled ignored -> true;
            case RowState.Installed ignored -> false;
            case RowState.UpdateAvailable ignored -> false;
        };
    }

    /** The picker calls it installed, so its click starts it rather than offering Install & start. */
    static boolean isInstalled(SubscriptionState state) {
        return switch (state) {
            case SubscriptionState.Installed ignored -> true;
            case SubscriptionState.NotInstalled ignored -> false;
            case SubscriptionState.UpdateAvailable ignored -> false;
            case SubscriptionState.Installing ignored -> false;
            case SubscriptionState.Failed ignored -> false;
        };
    }

    static boolean isInstalling(SubscriptionState state) {
        return switch (state) {
            case SubscriptionState.Installing ignored -> true;
            case SubscriptionState.NotInstalled ignored -> false;
            case SubscriptionState.Installed ignored -> false;
            case SubscriptionState.UpdateAvailable ignored -> false;
            case SubscriptionState.Failed ignored -> false;
        };
    }

    /** True for a failed row, which is where the picker's "but not ..." text would live. */
    static boolean isFailed(SubscriptionState state) {
        return switch (state) {
            case SubscriptionState.Failed ignored -> true;
            case SubscriptionState.NotInstalled ignored -> false;
            case SubscriptionState.Installed ignored -> false;
            case SubscriptionState.UpdateAvailable ignored -> false;
            case SubscriptionState.Installing ignored -> false;
        };
    }
}
