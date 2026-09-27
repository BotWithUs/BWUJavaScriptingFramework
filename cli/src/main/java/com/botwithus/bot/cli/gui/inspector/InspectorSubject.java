package com.botwithus.bot.cli.gui.inspector;

import com.botwithus.bot.cli.gui.nav.PageId;

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

    /** A management script: one runner for the whole host. */
    record ManagementScript(String scriptName) implements InspectorSubject {

        @Override
        public PageId ownerPage() {
            return PageId.MANAGEMENT;
        }
    }
}
