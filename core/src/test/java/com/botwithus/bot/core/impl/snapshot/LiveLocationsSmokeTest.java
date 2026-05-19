package com.botwithus.bot.core.impl.snapshot;

import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.api.snapshot.Location;
import com.botwithus.bot.api.snapshot.LocationFilter;
import com.botwithus.bot.core.shm.Layout;
import com.botwithus.bot.core.shm.SharedRegion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Phase B live smoke test: binds to a running NXTLibrary producer via the
 * shared-memory mapping and asserts that {@code snapshot.locations()} and
 * {@code snapshot.sceneVersion()} return sane values from real game memory.
 *
 * <p>Disabled by default — opt in with
 * {@code -Dbotwithus.smoke.live=true}. Requires the NXTLibrary DLL to be
 * injected into a running game client; the test discovers the pid by
 * scanning for {@code BotWithUs_<pid>} pipes via
 * {@link SharedRegion#openFirstAvailable()}.</p>
 */
class LiveLocationsSmokeTest {

    private static final Logger log = LoggerFactory.getLogger(LiveLocationsSmokeTest.class);

    /** Plausible world-tile bounds — anything outside this is a torn read. */
    private static final int MIN_TILE = 0;
    private static final int MAX_TILE = 25000;
    private static final int MAX_PLANE = 3;

    /** Game state code published by the producer when the client is in-game. */
    private static final int GAME_STATE_IN_GAME = 30;

    @Test
    @EnabledIfSystemProperty(named = "botwithus.smoke.live", matches = "true")
    void readsLocationsFromLiveProducer() {
        Optional<SharedRegion> maybeRegion = SharedRegion.openFirstAvailable();
        if (maybeRegion.isEmpty()) {
            fail("No BotWithUs_<pid> pipe visible — inject the DLL into a running game first");
        }

        try (SharedRegion region = maybeRegion.orElseThrow()) {
            GameSnapshot snap = new GameSnapshotImpl(region.snapshot());

            int count = snap.locations().count();
            int sceneVer = snap.sceneVersion();
            int gameState = snap.gameState();
            LocalPlayer self = snap.self();
            log.info("live snapshot: tick={} gameState={} self={} locations.count={} sceneVer={}",
                    snap.tickId(), gameState,
                    self == null ? "(null)" : "(" + self.tileX() + "," + self.tileY() + ",p" + self.plane() + ")",
                    count, sceneVer);

            assertNotNull(snap.locations(), "locations() table must not be null");
            assertTrue(count >= 0 && count <= Layout.LOCATION_CAP,
                    "locations.count() out of [0, LOCATION_CAP]: " + count);
            assertTrue(sceneVer >= 0,
                    "sceneVersion must be non-negative (u32 cast through int): " + sceneVer);

            if (gameState != GAME_STATE_IN_GAME) {
                log.warn("client not in-game (gameState={}); skipping content checks", gameState);
                return;
            }

            assertTrue(count > 0,
                    "in-game snapshot must surface scene Locations; got count=0");

            List<Location> direct = snap.locations().filter(LocationFilter.direct());
            List<Location> sections = snap.locations().filter(LocationFilter.combinedSection());
            assertEquals(count, direct.size() + sections.size(),
                    "direct + combined_section must partition the set");

            log.info("breakdown: direct={} combined_section={} hidden={} deleted={} animating={}",
                    direct.size(), sections.size(),
                    snap.locations().filter(Location::isHidden).size(),
                    snap.locations().filter(Location::isDeleted).size(),
                    snap.locations().filter(LocationFilter.animating()).size());

            assertAll("per-row invariants",
                    () -> snap.locations().stream().forEach(l -> {
                        assertTrue(l.tileX() >= MIN_TILE && l.tileX() <= MAX_TILE,
                                "tileX out of range: " + l);
                        assertTrue(l.tileY() >= MIN_TILE && l.tileY() <= MAX_TILE,
                                "tileY out of range: " + l);
                        assertTrue(l.plane() >= 0 && l.plane() <= MAX_PLANE,
                                "plane out of [0..3]: " + l);
                        if (l.isCombinedSection()) {
                            assertEquals(-1, l.interactId(),
                                    "section interactId must be -1 (Phase A design): " + l);
                            assertEquals(-1, l.animationId(),
                                    "section animationId must be -1 (Phase A design): " + l);
                        }
                    }));

            // Two reads within the same Java tick should agree on the count
            // and scene version — the producer flips buffers on its own clock,
            // but back-to-back reads from the same MemorySegment must be stable.
            GameSnapshot snap2 = new GameSnapshotImpl(region.snapshot());
            assertEquals(sceneVer, snap2.sceneVersion(),
                    "sceneVersion must be stable across back-to-back reads of the same front buffer");

            // Sample log: first row gives a quick eyeball check in CI logs.
            Location first = snap.locations().at(0);
            log.info("first row: type={} interact={} anim={} tile=({},{},p{}) shape={} rot={} flags=0x{}",
                    first.typeId(), first.interactId(), first.animationId(),
                    first.tileX(), first.tileY(), first.plane(),
                    first.shape(), first.rotation(),
                    Integer.toHexString(first.flags()));
        }
    }
}
