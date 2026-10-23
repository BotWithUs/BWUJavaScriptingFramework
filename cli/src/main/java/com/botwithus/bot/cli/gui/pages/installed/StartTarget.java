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
         * The client is not connected, and the host remembers its account: the
         * script starts when it is back. Tickable.
         */
        WHEN_BACK(""),

        /** Not connected, and the host never read an account for it, so it cannot tell when it is back. */
        OFFLINE_NO_ACCOUNT("The host never read an account for this client, so it cannot tell when "
                + "it is back. Start the script once it is connected."),

        /**
         * Not connected, and it was a second client open on an account at the same
         * time: only the first is remembered, so nothing can wait for this one.
         */
        OFFLINE_SECOND_CLIENT("This was a second client on an account that was already open. Only the "
                + "first is remembered, so a start cannot wait for this one."),

        /** Not connected, and the script is a Store delivery, which reaches only connected clients. */
        OFFLINE_STORE_SCRIPT("A Store script reaches a client only while it is connected. Install it "
                + "again from the Script Store once this client is back.");

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

    /** Whether it can be ticked: to start now, or when the client is back. */
    public boolean isSelectable() {
        return eligibility == Eligibility.AVAILABLE || eligibility == Eligibility.WHEN_BACK;
    }

    /** Whether ticking it queues the start for when the client is back, rather than starting it now. */
    public boolean startsWhenBack() {
        return eligibility == Eligibility.WHEN_BACK;
    }
}
