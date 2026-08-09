package com.botwithus.bot.core.impl.snapshot;

import com.botwithus.bot.api.snapshot.DynamicRegion;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.api.snapshot.SourceTile;
import com.botwithus.bot.core.shm.Layout;
import com.botwithus.bot.core.shm.SharedRegion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Live smoke test for the v19 dynamic-region block: binds to a running
 * NXTLibrary producer over the shared-memory mapping and checks that
 * {@code snapshot.dynamicRegion()} is internally consistent with whatever scene
 * the client happens to be in.
 *
 * <p>Deliberately shape-agnostic. In an ordinary overworld scene the block is
 * inert and the assertions below prove exactly that — which is the more
 * important of the two cases, because a block that is quietly always empty
 * looks identical to a block that works. Standing in an instance (a
 * player-owned house is the easiest) exercises the populated path and the log
 * line reports the grid so it can be eyeballed against the RE capture.</p>
 *
 * <p>Disabled by default — opt in with {@code -Dbotwithus.smoke.live=true}.
 * Requires the NXTLibrary DLL injected into a running client; the pid is
 * discovered by scanning for {@code BotWithUs_<pid>} pipes via
 * {@link SharedRegion#openFirstAvailable()}.</p>
 */
class LiveDynamicRegionSmokeTest {

    private static final Logger log = LoggerFactory.getLogger(LiveDynamicRegionSmokeTest.class);

    /** Game state code published by the producer when the client is in-game. */
    private static final int GAME_STATE_IN_GAME = 30;

    /** Lowest scene mode the client uses for a dynamic region. */
    private static final int MIN_DYNAMIC_SCENE_MODE = 4;

    /** Highest scene mode the client uses for a dynamic region. */
    private static final int MAX_DYNAMIC_SCENE_MODE = 7;

    @Test
    @EnabledIfSystemProperty(named = "botwithus.smoke.live", matches = "true")
    void readsDynamicRegionFromLiveProducer() {
        Optional<SharedRegion> maybeRegion = SharedRegion.openFirstAvailable();
        if (maybeRegion.isEmpty()) {
            fail("No BotWithUs_<pid> pipe visible — inject the DLL into a running game first");
        }

        try (SharedRegion region = maybeRegion.orElseThrow()) {
            GameSnapshot snap = new GameSnapshotImpl(region.snapshot());
            DynamicRegion dyn = snap.dynamicRegion();

            log.info("live dynRegion: instance={} truncated={} mode={} origin=({},{}) max=({},{}) "
                            + "grid={}x{} chunks={}/{} sceneVer={}",
                    dyn.isInstance(), dyn.isTruncated(), dyn.sceneMode(),
                    dyn.originMapX(), dyn.originMapY(), dyn.maxMapX(), dyn.maxMapY(),
                    dyn.gridW(), dyn.gridH(), dyn.chunkCount(), dyn.requiredChunks(),
                    snap.sceneVersion());

            assertTrue(dyn.chunkCount() >= 0 && dyn.chunkCount() <= Layout.DYN_CHUNK_CAP,
                    "chunkCount out of [0, DYN_CHUNK_CAP]: " + dyn.chunkCount());

            if (snap.gameState() != GAME_STATE_IN_GAME) {
                log.warn("client not in-game (gameState={}); skipping content checks",
                        snap.gameState());
                return;
            }

            if (dyn.isInstance()) {
                assertInstanceScene(snap, dyn);
            } else {
                assertStaticScene(snap, dyn);
            }
        }
    }

    /** A static scene must publish an inert block: no grid, no chunks, no sources. */
    private static void assertStaticScene(GameSnapshot snap, DynamicRegion dyn) {
        // NOT asserted: sceneMode == 3. isInstance and sceneMode are two
        // independent client fields, and a scene caught mid-rebuild can report a
        // dynamic mode with no descriptor installed yet — a real state, not a
        // defect, and asserting on it would fail the run at random.
        if (dyn.sceneMode() != DynamicRegion.SCENE_MODE_STATIC) {
            log.info("non-instance scene reports mode {} rather than 3 — likely caught "
                    + "mid-rebuild; isInstance is the predicate, mode is diagnostic",
                    dyn.sceneMode());
        }
        assertEquals(0, dyn.chunkCount(), "a static scene publishes no chunks");
        LocalPlayer self = snap.self();
        if (self != null) {
            assertTrue(dyn.sourceOf(self.tileX(), self.tileY(), self.plane()).isEmpty(),
                    "a static scene must resolve no source tile");
        }
    }

    /** An instance must publish a grid whose geometry, count and decode all agree. */
    private static void assertInstanceScene(GameSnapshot snap, DynamicRegion dyn) {
        assertTrue(dyn.sceneMode() >= MIN_DYNAMIC_SCENE_MODE
                        && dyn.sceneMode() <= MAX_DYNAMIC_SCENE_MODE,
                "instance scene mode out of [4..7]: " + dyn.sceneMode());
        assertTrue(dyn.gridW() > 0 && dyn.gridH() > 0,
                "an instance must report grid dimensions even when truncated");
        assertEquals(DynamicRegion.PLANE_COUNT * dyn.gridW() * dyn.gridH(), dyn.requiredChunks(),
                "requiredChunks must equal 4 * gridW * gridH");

        if (dyn.isTruncated()) {
            assertEquals(0, dyn.chunkCount(), "a truncated grid publishes no chunks");
            log.warn("grid {}x{} exceeded the {}-entry cap; raise kDynChunkCap",
                    dyn.gridW(), dyn.gridH(), Layout.DYN_CHUNK_CAP);
            return;
        }
        assertEquals(dyn.requiredChunks(), dyn.chunkCount(),
                "an untruncated grid publishes every cell");

        LocalPlayer self = snap.self();
        if (self == null) {
            return;
        }
        Optional<SourceTile> source = dyn.sourceOf(self.tileX(), self.tileY(), self.plane());
        log.info("self at ({},{},p{}) sources from {}",
                self.tileX(), self.tileY(), self.plane(),
                source.map(Object::toString).orElse("(no source — hole or outside the grid)"));
        source.ifPresent(src -> {
            assertTrue(src.plane() >= 0 && src.plane() < DynamicRegion.PLANE_COUNT,
                    "source plane out of [0..3]: " + src);
            assertTrue(src.rotation() >= 0 && src.rotation() <= DynamicRegion.MAX_ROTATION,
                    "source rotation out of [0..3]: " + src);
            if (src.rotation() != 0) {
                log.info("ROTATION WITNESSED NON-ZERO: {} — the rotation path has never been "
                        + "seen live before; capture this scene", src);
            }
        });
    }
}
