package com.botwithus.bot.cli.gui.pages.installed;

import com.botwithus.bot.core.sdn.InstalledSdnScript;
import com.botwithus.bot.core.sdn.SdnCatalogueEntry;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The row's "v2.1 in Store" pill: shown only when the Store's build is newer than the one installed. */
class UpdateBadgeTest {

    private static final Instant INSTALLED_AT = Instant.parse("2026-09-12T09:00:00Z");
    private static final int OLD_BUILD = 5;
    private static final int NEW_BUILD = 7;

    static SdnCatalogueEntry entry(String version, Integer build) {
        return new SdnCatalogueEntry("wc-1", "Woodcutting", "BotWithUs", "me", version, "2", "", "",
                "com.example.Woodcutting", false, true, true, true, "woodcutting", null, build);
    }

    private static Optional<InstalledSdnScript> installed(Integer build) {
        return Optional.of(new InstalledSdnScript("wc-1", build, INSTALLED_AT));
    }

    @Test
    void aNewerBuild_namesTheStoresVersion() {
        UpdateBadge badge = UpdateBadge.of(installed(OLD_BUILD), Optional.of(entry("2.1", NEW_BUILD)), "2.0")
                .orElseThrow();

        assertEquals("v2.1 in Store", badge.badge());
        assertEquals("v2.1 is in the Store", badge.headline());
        assertEquals("You have v2.0 (build 5). Update it from the Script Store.", badge.detail());
    }

    @Test
    void aVersionLabelThatDidNotMove_fallsBackToTheBuildNumber() {
        UpdateBadge badge = UpdateBadge.of(installed(OLD_BUILD), Optional.of(entry("v2.0", NEW_BUILD)), "2.0")
                .orElseThrow();

        assertEquals("build 7 in Store", badge.badge());
        assertEquals("Build 7 is in the Store", badge.headline());
    }

    @Test
    void theSameOrAnOlderBuild_isNoUpdate() {
        assertTrue(UpdateBadge.of(installed(NEW_BUILD), Optional.of(entry("2.1", NEW_BUILD)), "2.1").isEmpty());
        assertTrue(UpdateBadge.of(installed(NEW_BUILD), Optional.of(entry("2.0", OLD_BUILD)), "2.1").isEmpty());
    }

    @Test
    void anUnknownBuildOnEitherSide_showsNoBadge() {
        assertTrue(UpdateBadge.of(installed(null), Optional.of(entry("2.1", NEW_BUILD)), "2.0").isEmpty());
        assertTrue(UpdateBadge.of(installed(OLD_BUILD), Optional.of(entry("2.1", null)), "2.0").isEmpty());
    }

    @Test
    void aLocalBuild_orAScriptTheCatalogueDoesNotList_showsNoBadge() {
        assertTrue(UpdateBadge.of(Optional.empty(), Optional.of(entry("2.1", NEW_BUILD)), "2.0").isEmpty());
        assertTrue(UpdateBadge.of(installed(OLD_BUILD), Optional.empty(), "2.0").isEmpty());
    }
}
