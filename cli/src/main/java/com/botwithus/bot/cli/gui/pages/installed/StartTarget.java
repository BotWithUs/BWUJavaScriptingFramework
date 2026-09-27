package com.botwithus.bot.cli.gui.pages.installed;

import java.util.Objects;
import java.util.Optional;

/**
 * One client in the Start-on dialog, and whether the script can be started there.
 *
 * @param note the short state on the right of the row, such as {@code "stopped here"}
 */
public record StartTarget(String clientId, String name, boolean isConnected, Eligibility eligibility,
                          String note) {

    /** Why a client can or cannot be ticked. */
    public enum Eligibility {

        /** Tickable. */
        AVAILABLE(""),

        /** It is already running there. */
        ALREADY_RUNNING(""),

        /** Its last run there would not stop; that thread has to exit first. */
        SHUTTING_DOWN("Its last run here would not stop. It can start again once that thread exits."),

        /**
         * A Store delivery reaches only the clients connected when it was
         * installed; one that connected later has to have it installed again.
         */
        NOT_INSTALLED_HERE("This client connected after the script was installed. "
                + "Install it again from the Script Store to add it here."),

        /**
         * The client is not connected. A queue that starts a script when its
         * client comes back does not exist yet, so these are shown but not tickable.
         */
        OFFLINE("Queuing for offline clients comes with Groups");

        private final String tooltip;

        Eligibility(String tooltip) {
            this.tooltip = tooltip;
        }

        /** Why the row is disabled, shown on hover; empty for a tickable or self-explanatory row. */
        public Optional<String> tooltip() {
            return tooltip.isEmpty() ? Optional.empty() : Optional.of(tooltip);
        }
    }

    public StartTarget {
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(eligibility, "eligibility");
        Objects.requireNonNull(note, "note");
    }

    public boolean isSelectable() {
        return eligibility == Eligibility.AVAILABLE;
    }
}
