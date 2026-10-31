package com.botwithus.bot.api.model;

import com.botwithus.bot.api.model.WorldPathConfig.MovementOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldPathConfigTest {

    private static final int AGILITY = 70;
    private static final int ITERATIONS = 1_000;
    private static final float COST = 2.5f;

    @Test
    void legacyConstructor_fifteenArguments_allowsEveryMovementOption() {
        WorldPathConfig config = new WorldPathConfig(AGILITY, ITERATIONS,
                true, false, true, false, true, false,
                COST, COST, COST, COST, COST, COST, COST);

        assertSame(MovementOptions.ALL, config.movement());
        assertEquals(AGILITY, config.agilityLevel());
        assertFalse(config.allowShortcuts());
        assertFalse(config.allowTeleports());
    }

    @Test
    void default_keepsEveryFlagOnAndEveryMovementOptionAllowed() {
        WorldPathConfig config = WorldPathConfig.DEFAULT;

        assertTrue(config.allowDoors() && config.allowShortcuts()
                && config.allowPlaneTransitions() && config.allowClimbovers()
                && config.allowTransports() && config.allowTeleports());
        assertEquals(MovementOptions.ALL, config.movement());
    }

    @Test
    void builder_untouched_equalsDefault() {
        assertEquals(WorldPathConfig.DEFAULT, WorldPathConfig.builder().build());
    }

    @Test
    void movementOptionsAll_allowsEverything() {
        MovementOptions all = MovementOptions.ALL;

        assertTrue(all.allowSpiritTrees() && all.allowGliders() && all.allowCharters()
                && all.allowFairyRings() && all.allowLodestones() && all.allowMagicCarpets()
                && all.allowOtherTeleportChains() && all.allowSurge() && all.allowDive());
    }

    @Test
    void builder_eachMovementFlag_landsOnItsOwnComponent() {
        MovementOptions movement = WorldPathConfig.builder()
                .allowSpiritTrees(false).allowGliders(true).allowCharters(false)
                .allowFairyRings(true).allowLodestones(false).allowMagicCarpets(true)
                .allowOtherTeleportChains(false).allowSurge(true).allowDive(false)
                .build().movement();

        assertEquals(new MovementOptions(false, true, false, true, false, true, false, true,
                false), movement);
    }

    @Test
    void canonicalConstructor_nullMovement_isRefused() {
        assertThrows(NullPointerException.class, () -> new WorldPathConfig(AGILITY, ITERATIONS,
                true, true, true, true, true, true,
                COST, COST, COST, COST, COST, COST, COST, null));
    }
}
