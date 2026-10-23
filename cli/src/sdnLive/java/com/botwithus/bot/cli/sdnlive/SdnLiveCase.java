package com.botwithus.bot.cli.sdnlive;

import java.nio.file.Path;

/**
 * One catalogue entry the check installs, and the fixture jar the courier
 * delivers for it.
 *
 * @param id             the catalogue id
 * @param variant        the fixture it is delivered as, which names its module and class
 * @param catalogueClass the class the catalogue says the entry is. For a
 *                       CraftWithUs-shaped entry it is an agent-v1 class that the
 *                       delivered v2 jar does not contain; for a blank-class entry it
 *                       is empty. Either way it never names the delivered class, which
 *                       is the point: pairing must go by the jar
 * @param jar            the plaintext fixture jar the courier seals and delivers
 */
record SdnLiveCase(String id, String variant, String catalogueClass, Path jar) {

    /** Every fixture module is named {@code <this prefix><variant>}. */
    static final String MODULE_PREFIX = "com.botwithus.sdnlive.";
    private static final String SCRIPT_SIMPLE_NAME = "LiveFixtureScript";
    private static final String DISPLAY_PREFIX = "SDN live ";

    /** The module the delivered jar declares. */
    String moduleName() {
        return MODULE_PREFIX + variant;
    }

    /** The class the delivered jar provides, which the catalogue does not name. */
    String deliveredClass() {
        return moduleName() + "." + SCRIPT_SIMPLE_NAME;
    }

    /** The catalogue's display name, and the name the fixture's manifest gives its runner. */
    String displayName() {
        return DISPLAY_PREFIX + variant;
    }
}
