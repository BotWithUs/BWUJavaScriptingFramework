package com.botwithus.bot.cli.gui.inspector;

import com.botwithus.bot.cli.gui.nav.PageId;
import com.botwithus.bot.cli.management.Target;

import java.util.Objects;
import java.util.Optional;

/**
 * Which script the inspector is open on. The variant is the inspector's kind: a
 * script running on one client, or a management script running once for the
 * host. Two subjects are the same inspector exactly when they are equal, so a
 * form's pending edits belong to one subject and are dropped when it changes.
 */
public sealed interface InspectorSubject {

    /** The name the script's runtime registers it under. */
    String scriptName();

    /** The page the inspector docks beside; opening it from elsewhere switches there. */
    PageId ownerPage();

    /**
     * A script on one game client.
     *
     * @param clientId the connection the runner belongs to
     */
    record ClientScript(String clientId, String scriptName) implements InspectorSubject {

        @Override
        public PageId ownerPage() {
            return PageId.CLIENTS;
        }
    }

    /**
     * A management script: one runner for the whole host.
     *
     * @param settingsFor the target whose own settings the form edits; empty for
     *                    the defaults every target uses. Each target is its own
     *                    subject, so picking another one starts a fresh form
     */
    record ManagementScript(String scriptName, Optional<Target> settingsFor) implements InspectorSubject {

        public ManagementScript {
            Objects.requireNonNull(scriptName, "scriptName");
            Objects.requireNonNull(settingsFor, "settingsFor");
        }

        /** The management script's defaults. */
        public ManagementScript(String scriptName) {
            this(scriptName, Optional.empty());
        }

        /** The same script, with the form on {@code target}'s settings, or on the defaults when empty. */
        public ManagementScript withSettingsFor(Optional<Target> target) {
            return new ManagementScript(scriptName, target);
        }

        @Override
        public PageId ownerPage() {
            return PageId.MANAGEMENT;
        }
    }
}
