package com.botwithus.bot.core.sdn;

import java.util.Objects;

/**
 * One script in the user's SDN catalogue, as the launcher reported it.
 *
 * <p>The fields mirror what the website publishes for a script the user owns or
 * subscribes to. Two of them are easy to misread:
 *
 * <ul>
 *   <li>{@code version} is the catalogue entry's own free-text label. It is not
 *       necessarily the version a download will serve, so treat it as display
 *       text rather than as an identifier to compare against.
 *   <li>{@code subscriber} echoes the account that asked for the catalogue. It
 *       says nothing about this particular script — to tell an owned script from
 *       a subscribed one, compare {@link #author()} with the signed-in account.
 * </ul>
 */
public record SdnCatalogueEntry(String id,
                                String name,
                                String author,
                                String subscriber,
                                String version,
                                String apiVersion,
                                String tagline,
                                String description,
                                String scriptClass,
                                boolean agentv1Support,
                                boolean agentv2Support) {

    public SdnCatalogueEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
    }

    /**
     * True iff this script can run on the v2 agent. The catalogue lists scripts
     * regardless of which agent they target, so anything the v2 host offers to
     * install must be filtered on this first — a v1-only script is rejected at
     * download time, long after the user has committed to installing it.
     */
    public boolean runsOnThisHost() {
        return agentv2Support;
    }

    /**
     * True iff the signed-in account wrote this script rather than subscribing
     * to it. {@code subscriber} echoes whoever asked for the catalogue, so the
     * two fields agreeing is what marks a script as the caller's own.
     */
    public boolean isOwned() {
        return !author.isBlank() && author.equalsIgnoreCase(subscriber);
    }

    /** Display label: the tagline when there is one, else the first line of the description. */
    public String summary() {
        if (tagline != null && !tagline.isBlank()) {
            return tagline;
        }
        if (description == null || description.isBlank()) {
            return "";
        }
        int nl = description.indexOf('\n');
        return nl < 0 ? description : description.substring(0, nl);
    }
}
