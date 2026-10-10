package com.botwithus.bot.core.sdn;

import com.botwithus.bot.api.ScriptCategory;

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
 *       Whether the account holds a subscription is {@link #subscribed()}, never
 *       an inference from these two.
 * </ul>
 *
 * <p>{@code subscribed}, {@code isFree} and the three Store fields are nullable on
 * purpose. {@code null} means the launcher, or the site behind it, predates the
 * field or had nothing to say, which is not the same answer as {@code false} or
 * zero. Read them as "unknown".
 *
 * @param subscribed   {@code TRUE} iff the signed-in account holds an active
 *                     subscription to this script; {@code null} when not reported
 * @param isFree       {@code TRUE} iff the site offers this script for free;
 *                     {@code null} when not reported. This, not {@link #price()},
 *                     decides whether a script is free: see {@link #pricing()}.
 * @param category     the site's category slug, such as {@code "money_making"};
 *                     {@code null} when not reported. {@link #scriptCategory()}
 *                     maps it onto the host's own categories.
 * @param price        the tier the Store headlines; {@code null} when that tier is
 *                     free, when there is none, or when not reported
 * @param currentBuild the site's number for the build a download would deliver to
 *                     this host; {@code null} when not reported. Each upload gets a
 *                     higher number, so unlike {@code version} it can be compared,
 *                     but the site can make an older build current again.
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
                                boolean agentv2Support,
                                Boolean subscribed,
                                Boolean isFree,
                                String category,
                                SdnPrice price,
                                Integer currentBuild) {

    /** Whether a script costs money, as far as the catalogue can tell. */
    public enum Pricing {
        /** The site offers the script for free, whether or not it also sells a paid tier. */
        FREE,
        /** The site offers no free tier. */
        PAID,
        /** The launcher or site did not say. Matches neither a Free nor a Paid filter. */
        UNKNOWN
    }

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

    /**
     * Whether this script is free, decided by {@link #isFree()} alone.
     *
     * <p>A missing {@link #price()} does not make a script free: an older site or
     * launcher omits the price for every script, and the current site omits it for
     * a paid script with no active tier. And a present price does not make it
     * paid: a script can headline a paid tier while also offering a free one.
     */
    public Pricing pricing() {
        if (isFree == null) {
            return Pricing.UNKNOWN;
        }
        return isFree ? Pricing.FREE : Pricing.PAID;
    }

    /** The host category for {@link #category()}; {@link ScriptCategory#UNCATEGORIZED} when unknown. */
    public ScriptCategory scriptCategory() {
        return CategorySlugs.toCategory(category);
    }

    /**
     * Whether a script that arrived in a delivery of this entry is its script.
     *
     * <p>The class the catalogue names, or the manifest name, ignoring case. The
     * name is not only a fallback for a blank class: an entry shared by both agents
     * carries the agent v1 class, so an agent v2 build of it never matches by class.
     * A delivery holds only what was requested, so the name is safe there. It is not
     * a safe way to recognise an already-loaded script: a same-named local script by
     * someone else would match.
     *
     * @param className    the delivered script's fully-qualified class name
     * @param manifestName the name the runtime registers it under
     */
    public boolean isDeliveredAs(String className, String manifestName) {
        boolean byClass = scriptClass != null && !scriptClass.isBlank() && scriptClass.equals(className);
        return byClass || !name.isBlank() && name.equalsIgnoreCase(manifestName);
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
