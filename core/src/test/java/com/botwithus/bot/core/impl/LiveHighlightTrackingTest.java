package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.draw.Colors;
import com.botwithus.bot.api.draw.Draw;
import com.botwithus.bot.api.draw.DrawEntry;
import com.botwithus.bot.api.draw.DrawKind;
import com.botwithus.bot.api.draw.DrawLimits;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.Npc;
import com.botwithus.bot.core.impl.snapshot.GameSnapshotImpl;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.rpc.RpcException;
import com.botwithus.bot.core.shm.SharedRegion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.opentest4j.TestAbortedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Does an entity highlight actually follow the entity?</b>
 *
 * <p>This is the one claim that distinguishes {@code highlight_entity} from the
 * world-space rect {@code draw.npc(...)} used to send. The two are
 * <i>indistinguishable</i> on the first frame: same place, same size, same colour, and
 * both come back from {@code debug_draw_list} with a resolved rect. They diverge only
 * once the NPC walks. So a test that asserts "a highlight was created", or even "its
 * rect resolved", passes identically against the thing this change replaced and is
 * worth nothing here.</p>
 *
 * <h2>The measurement, and the two things that would make it lie</h2>
 *
 * <p>Reading the tracked rect at two ticks and requiring it to have changed is necessary
 * but not sufficient, because <b>a camera that moved would change it too</b> — a pinned
 * rect under a moving camera moves exactly as much as a tracking one does. So every
 * window carries a second command: a {@code highlight_tile} at the tile the NPC started
 * on, re-projected by the same camera in the same resolve pass. If <i>its</i> rect is
 * byte-identical across the two samples, the camera did not move, and a change in the
 * tracked rect is attributable to the entity. If it is not identical the window is
 * <b>rejected and retried</b>, never asserted on: a reading that cannot be attributed is
 * not a verdict either way.</p>
 *
 * <p>The other thing that would make it lie is an NPC that did not move. The snapshot's
 * own tile is the independent witness, so a rect that failed to change can be told apart
 * from a quiet afternoon.</p>
 *
 * <p>{@link #aFixedWorldRect_doesNotFollowTheNpc} is the control that makes a positive
 * reading mean something: the same measurement, against the command shape this change
 * replaced — a world-space rect pinned at the NPC's starting tile — requiring the rect
 * <b>not</b> to change. Without it, "the tracked rect changed" could be an artifact of
 * when the samples were taken. With it, the same measurement demonstrably reads both
 * ways.</p>
 *
 * <p><b>An earlier version of this measured the growing distance between the tracked and
 * pinned rects, and that was wrong.</b> A wanderer does not depart monotonically, and the
 * first sample is taken a few ticks after the draw, by which point the NPC has already
 * left its starting tile — so the distance shrank on a run where tracking was working
 * perfectly (73 pixels to 60). The invariant is "the tracked one moves and the fixed one
 * does not", not "they grow apart"; the live run is what caught the difference.</p>
 *
 * <h2>What this refuses to do</h2>
 *
 * <p><b>It never passes by skipping.</b> Every gate that cannot be satisfied is an
 * {@link Assumptions} skip whose message names the specific unmet condition — not
 * logged in passing, but in the skip reason itself, so an "all green" run that skipped
 * this says which condition was missing. In particular, "no NPC moved" is a skip and
 * not a pass: it means the measurement never ran.</p>
 *
 * <p>It also never asserts on a <b>latched</b> value. The NPC's movement is confirmed
 * against the snapshot's own tile between the two samples, so the window is one this
 * test observed rather than one it assumed from {@code isMoving()} at the start.</p>
 *
 * <p>Read-only with respect to the game: it draws and clears its own overlay commands
 * and queues no actions. Disabled by default — opt in with
 * {@code -Dbotwithus.smoke.live=true}, which {@code :core:harnessTest} and
 * {@code :core:liveSmokeTest} set.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "botwithus.smoke.live", matches = "true")
class LiveHighlightTrackingTest {

    private static final Logger log = LoggerFactory.getLogger(LiveHighlightTrackingTest.class);

    /** Key prefix for everything this class draws, so a stray leftover is identifiable. */
    private static final String PREFIX = "live-track-";

    /** Game state the producer publishes when the client is in the world. */
    private static final int GAME_STATE_IN_GAME = 30;

    /** Long enough to outlive the whole measurement window without being permanent. */
    private static final long TTL_MS = 60_000L;

    private static final int RESOLVE_POLL_ATTEMPTS = 40;
    private static final int MOVEMENT_POLL_ATTEMPTS = 120;
    private static final long POLL_SLEEP_MS = 100L;

    /**
     * Tiles the NPC must cover before the second sample.
     *
     * <p>More than one, because a single tile of movement at an oblique camera angle
     * can project to a handful of pixels — comparable with the rounding in a projected
     * bounding box, which is what would make this test's margin depend on where the
     * camera happens to be rather than on whether tracking works.</p>
     */
    private static final int TILES_MOVED_FOR_A_CLEAR_READING = 2;

    /**
     * How many walking NPCs to try before giving up on finding a movement window.
     *
     * <p>More than one because a wanderer takes a step and then idles for several ticks,
     * so a single candidate stalls often enough to matter. See
     * {@link #measureAgainstAWanderer}.</p>
     */
    private static final int CANDIDATE_NPCS = 6;

    /** Candidates that already stalled, so a retry does not pick the same idler. */
    private final Set<Integer> attempted = new HashSet<>();

    private RpcClient rpc;
    private SharedRegion region;
    private Draw draw;
    private boolean wasEnabled;

    @BeforeAll
    void connect() {
        List<String> pipes = PipeClient.scanPipes(PipeClient.NAME_PREFIX);
        Assumptions.assumeFalse(pipes.isEmpty(),
                "SKIPPED, not passed: no BotWithUs_<pid> pipe visible — nothing was measured. "
                        + "Inject the agent into a running client.");

        // The snapshot and the pipe must be the SAME client: this test compares what
        // the snapshot says an NPC did against what the overlay drew for it, and two
        // clients would make that comparison meaningless rather than merely noisy.
        long pid = SharedRegion.parsePid(pipes.getFirst()).orElseThrow();
        region = SharedRegion.open(pid);
        rpc = new RpcClient(new PipeClient(pipes.getFirst()));
        GameAPI api = new GameAPIImpl(rpc);
        draw = api.draw();

        Assumptions.assumeTrue(hasHighlightSupport(),
                "SKIPPED, not passed: this agent has no highlight_entity — nothing was "
                        + "measured. Rebuild and reinject the agent.");

        wasEnabled = draw.isEnabled();
        if (!wasEnabled) {
            draw.setEnabled(true);
        }
        log.info("connected to pid {} for highlight tracking", pid);
    }

    /**
     * Whether the producer has the semantic highlight group at all.
     *
     * <p>Probed by making a call and reading the error, rather than by
     * {@code rpc.list_methods}: an agent that lists the method but whose handler is
     * wired to nothing would pass a catalogue check. A refusal for a <i>reason</i>
     * proves the handler ran.</p>
     */
    private boolean hasHighlightSupport() {
        try {
            rpc.callSync("highlight_entity", Map.of("ttl_ms", 1));
            return true;
        } catch (RpcException e) {
            boolean handlerRan = e.getMessage() != null
                    && e.getMessage().contains("needs npc, player or self");
            log.info("highlight_entity probe: handlerPresent={} message={}",
                    handlerRan, e.getMessage());
            return handlerRan;
        }
    }

    @AfterEach
    void clearOurOwnDrawings() {
        if (draw != null) {
            draw.clearAll();
        }
    }

    @AfterAll
    void disconnect() {
        if (draw != null && !wasEnabled) {
            draw.setEnabled(false);
        }
        if (rpc != null) {
            rpc.close();
        }
        if (region != null) {
            region.close();
        }
    }

    private GameSnapshot snapshot() {
        return new GameSnapshotImpl(region.snapshot());
    }

    // ------------------------------------------------------------------ the test

    /**
     * The headline: a tracked entity highlight's rect moves with the NPC, while a
     * highlight pinned to the tile the NPC started on does not.
     */
    @Test
    void anEntityHighlight_followsAMovingNpc() {
        String tracked = PREFIX + "entity";
        String pinned = PREFIX + "start-tile";

        Measurement measured = measureAgainstAWanderer(start -> {
            draw.npc(tracked, start).color(Colors.RED).ttl(TTL_MS).submit();
            draw.tile(pinned, start.tileX(), start.tileY(), start.plane())
                    .color(Colors.CYAN).ttl(TTL_MS).submit();
        }, tracked, pinned);

        log.info("npc {} moved {} tiles; tracked rect {} -> {}; camera reference {} (static)",
                measured.npc().serverIndex(), measured.tilesMoved(),
                measured.first().trackedRect(), measured.second().trackedRect(),
                measured.first().referenceRect());

        assertAll(
                () -> assertNotEquals(measured.first().trackedRect(),
                        measured.second().trackedRect(),
                        () -> "the tracked rect is unchanged (" + measured.first().trackedRect()
                                + ") after npc " + measured.npc().serverIndex() + " walked "
                                + measured.tilesMoved() + " tiles, while the fixed reference at "
                                + "its starting tile held still at "
                                + measured.first().referenceRect() + " — so the camera did not "
                                + "move and this highlight is marking a place rather than the "
                                + "entity."),
                // The kind the producer stored, so a tracked highlight is not quietly a rect.
                () -> assertSame(DrawKind.ENTITY, entryFor(tracked).kind(),
                        "the producer stored something other than an entity highlight"));
    }

    /**
     * The control: the command shape this change replaced does <b>not</b> follow the NPC,
     * measured exactly the same way.
     *
     * <p>A world-space rect at the NPC's tile is what {@code draw.npc(...)} sent before
     * this binding existed. It is re-projected every tick just as the highlight is — so it
     * survives a camera move, resolves cleanly, and looks correct in
     * {@code debug_draw_list} — and it still marks a <i>place</i>.</p>
     *
     * <p><b>This is the same measurement reading the other way</b>, which is what makes the
     * positive result above mean something. Without it, "the tracked rect changed" could
     * be an artifact of when the two samples were taken rather than a fact about
     * tracking.</p>
     */
    @Test
    void aFixedWorldRect_doesNotFollowTheNpc() {
        String pinnedRect = PREFIX + "old-rect";
        String reference = PREFIX + "old-reference";
        int oneTile = DrawLimits.SUBTILE_SCALE;

        Measurement measured = measureAgainstAWanderer(start -> {
            draw.rect(pinnedRect, start.tileX() * DrawLimits.SUBTILE_SCALE,
                            start.tileY() * DrawLimits.SUBTILE_SCALE, oneTile, oneTile)
                    .world().color(Colors.YELLOW).ttl(TTL_MS).submit();
            draw.tile(reference, start.tileX(), start.tileY(), start.plane())
                    .color(Colors.CYAN).ttl(TTL_MS).submit();
        }, pinnedRect, reference);

        log.info("control: npc {} moved {} tiles; pinned world rect {} -> {}",
                measured.npc().serverIndex(), measured.tilesMoved(),
                measured.first().trackedRect(), measured.second().trackedRect());

        assertEquals(measured.first().trackedRect(), measured.second().trackedRect(),
                () -> "a world rect pinned at the NPC's starting tile moved from "
                        + measured.first().trackedRect() + " to "
                        + measured.second().trackedRect() + " while the camera held still. "
                        + "It marks a fixed place, so it must not move — if it does, this "
                        + "measurement cannot tell tracking from projection noise and the "
                        + "positive test above proves nothing.");
    }

    /**
     * Run the two-sample measurement against an NPC that demonstrably walks.
     *
     * <p><b>Candidates are retried, and that is not incidental.</b> {@code isMoving()} says
     * an NPC is walking <i>now</i>; a wanderer takes a step and then stands still for
     * several ticks, so a single candidate frequently fails to cover enough ground before
     * the window closes. Retrying across candidates is what keeps this a measurement
     * rather than a coin toss — the first version of this test skipped roughly half its
     * runs on exactly that, which is a skip and therefore not a pass.</p>
     *
     * <p>Each attempt redraws under the same keys, because a key replaces: the commands
     * from an abandoned attempt are overwritten rather than accumulated.</p>
     *
     * @param drawBoth  draws the command under test and its fixed reference, in that order
     * @param trackedKey key of the command under test
     * @param pinnedKey  key of the fixed reference
     */
    private Measurement measureAgainstAWanderer(Consumer<Npc> drawBoth,
                                                String trackedKey, String referenceKey) {
        requireInTheWorld();
        String lastRejection = "no candidate was tried";
        for (int attempt = 0; attempt < CANDIDATE_NPCS; attempt++) {
            Npc start = nextWanderer();
            drawBoth.accept(start);
            Sample first = awaitBothResolved(trackedKey, referenceKey);
            OptionalInt moved = awaitMovement(start);
            if (moved.isEmpty()) {
                lastRejection = "npc " + start.serverIndex() + " stalled before covering "
                        + TILES_MOVED_FOR_A_CLEAR_READING + " tiles";
                log.info("{}; trying another candidate", lastRejection);
                attempted.add(start.serverIndex());
                continue;
            }
            Sample second = readBoth(trackedKey, referenceKey);
            if (!first.referenceRect().equals(second.referenceRect())) {
                // The camera moved, so nothing in this window is attributable: a pinned
                // rect under a moving camera changes exactly as much as a tracking one.
                // Rejecting the window is the only honest option — asserting anyway would
                // pass or fail on where the camera happened to swing.
                lastRejection = "the camera moved during npc " + start.serverIndex()
                        + "'s window (fixed reference " + first.referenceRect() + " -> "
                        + second.referenceRect() + ")";
                log.info("{}; trying another candidate", lastRejection);
                attempted.add(start.serverIndex());
                continue;
            }
            return new Measurement(start, moved.getAsInt(), first, second);
        }
        throw new TestAbortedException(
                "SKIPPED, not passed: none of " + CANDIDATE_NPCS + " candidate NPCs gave a "
                        + "usable window, so the tracking measurement never ran and nothing "
                        + "was concluded. Last reason: " + lastRejection + ". A usable window "
                        + "needs an NPC that covers " + TILES_MOVED_FOR_A_CLEAR_READING
                        + " tiles within " + (MOVEMENT_POLL_ATTEMPTS * POLL_SLEEP_MS)
                        + "ms while the camera holds still.");
    }

    /**
     * One usable measurement window: which NPC, how far it went, and the two samples.
     *
     * <p>"Usable" is the load-bearing word. It means the NPC's movement was witnessed by
     * the snapshot <b>and</b> the fixed reference rect was byte-identical across the two
     * samples, so the camera provably did not move. Only then is a change in the tracked
     * rect attributable to the entity rather than the viewpoint.</p>
     */
    private record Measurement(Npc npc, int tilesMoved, Sample first, Sample second) {
    }

    /** Both keys, once both have a resolved projection. */
    private Sample awaitBothResolved(String trackedKey, String referenceKey) {
        for (int attempt = 0; attempt < RESOLVE_POLL_ATTEMPTS; attempt++) {
            Map<String, DrawEntry> byKey = listByKey();
            DrawEntry tracked = byKey.get(trackedKey);
            DrawEntry reference = byKey.get(referenceKey);
            if (tracked != null && reference != null
                    && tracked.isResolved() && reference.isResolved()) {
                return new Sample(tracked.rect(), reference.rect());
            }
            sleep();
        }
        // A world command resolves `unavailable` pre-login, mid-teardown, or on a plane
        // the agent cannot height. None of those is a defect in this binding, and none
        // of them is a pass either.
        Map<String, DrawEntry> last = listByKey();
        throw new TestAbortedException(
                "SKIPPED, not passed: the overlay never resolved both commands, so no "
                        + "measurement was taken. Last seen: " + last.get(trackedKey)
                        + " / " + last.get(referenceKey)
                        + ". A world command resolves unavailable with no scene, no window, "
                        + "or on a plane the agent cannot height.");
    }

    private Sample readBoth(String trackedKey, String referenceKey) {
        Map<String, DrawEntry> byKey = listByKey();
        DrawEntry tracked = byKey.get(trackedKey);
        DrawEntry reference = byKey.get(referenceKey);
        assertAll(
                () -> assertNotNull(tracked, () -> "the tracked command vanished: " + byKey),
                () -> assertNotNull(reference, () -> "the reference command vanished: " + byKey),
                // A rect is zeroed when a command drops out of resolved, so an unresolved
                // second sample would compare against [0,0,0,0] and read as enormous
                // movement. That would be this test passing for the wrong reason.
                () -> assertTrue(tracked.isResolved(),
                        () -> "the tracked command stopped resolving: " + tracked),
                () -> assertTrue(reference.isResolved(),
                        () -> "the fixed reference stopped resolving: " + reference));
        return new Sample(tracked.rect(), reference.rect());
    }

    private DrawEntry entryFor(String key) {
        DrawEntry entry = listByKey().get(key);
        assertNotNull(entry, () -> "no retained command under " + key);
        return entry;
    }

    private Map<String, DrawEntry> listByKey() {
        Map<String, DrawEntry> byKey = new LinkedHashMap<>();
        for (DrawEntry entry : draw.list()) {
            byKey.put(entry.key(), entry);
        }
        return byKey;
    }

    /** The client must be in the world for anything here to resolve at all. */
    private void requireInTheWorld() {
        int state = snapshot().gameState();
        Assumptions.assumeTrue(state == GAME_STATE_IN_GAME,
                "SKIPPED, not passed: the client is not in the world (gameState=" + state
                        + "), so no NPC exists to track and nothing was measured. Log the "
                        + "client in, or let the harness session phase do it.");
    }

    /**
     * The next NPC that is walking right now and has not already been tried.
     *
     * <p>{@code isMoving()} picks the candidate; it is never trusted as evidence.
     * {@link #awaitMovement} re-reads the snapshot's own tile and reports how far the NPC
     * actually went, so the window under test is one this test observed rather than one it
     * inferred from a flag sampled earlier.</p>
     */
    private Npc nextWanderer() {
        for (int attempt = 0; attempt < MOVEMENT_POLL_ATTEMPTS; attempt++) {
            Optional<Npc> moving = snapshot().npcs().stream()
                    .filter(Npc::isMoving)
                    .filter(npc -> !attempted.contains(npc.serverIndex()))
                    .findFirst();
            if (moving.isPresent()) {
                return moving.orElseThrow();
            }
            sleep();
        }
        throw new TestAbortedException(
                "SKIPPED, not passed: no untried NPC was moving within "
                        + (MOVEMENT_POLL_ATTEMPTS * POLL_SLEEP_MS) + "ms of watching "
                        + snapshot().npcs().count() + " NPCs, so the tracking measurement never "
                        + "ran. Stand somewhere with wandering NPCs.");
    }

    /**
     * Wait until the NPC's own snapshot tile has moved far enough to read, answering how
     * far it went, or empty if it stalled.
     *
     * <p>The snapshot is the independent witness, and it is the reason this returns an
     * empty rather than failing. Without it, a tracked rect that did not move could not be
     * told apart from an NPC that stopped walking — the difference between a bug and a
     * quiet afternoon. A stall is a reason to try another NPC, never a verdict.</p>
     */
    private OptionalInt awaitMovement(Npc start) {
        for (int attempt = 0; attempt < MOVEMENT_POLL_ATTEMPTS; attempt++) {
            Optional<Npc> now = snapshot().npcs().byServerIndex(start.serverIndex());
            if (now.isEmpty()) {
                // Left the scene: no window, and no conclusion either way.
                return OptionalInt.empty();
            }
            Npc npc = now.orElseThrow();
            int moved = Math.abs(npc.tileX() - start.tileX())
                    + Math.abs(npc.tileY() - start.tileY());
            if (moved >= TILES_MOVED_FOR_A_CLEAR_READING) {
                return OptionalInt.of(moved);
            }
            sleep();
        }
        return OptionalInt.empty();
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_SLEEP_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while watching the overlay", e);
        }
    }

    /**
     * Two rects read from one {@code debug_draw_list} page, so one camera and one resolve
     * pass produced both.
     *
     * @param trackedRect the command under test
     * @param pinnedRect  the fixed reference at the NPC's starting tile
     */
    private record Sample(List<Integer> trackedRect, List<Integer> referenceRect) {
    }

    /**
     * Sanity: the local player highlight resolves without a plane and without an index.
     *
     * <p>Cheap, and it covers the one entity spelling the tracking test cannot use — the
     * local player does not wander on its own, so {@code self} has no movement window to
     * measure. This asserts only that it resolves, which is all that can be claimed
     * without moving the character.</p>
     */
    @Test
    void selfHighlight_resolvesWithoutAnIndexOrAPlane() {
        Assumptions.assumeTrue(snapshot().gameState() == GAME_STATE_IN_GAME,
                "SKIPPED, not passed: the client is not in the world, so nothing resolves "
                        + "and nothing was measured.");

        String key = draw.self(PREFIX + "self").color(Colors.GREEN).ttl(TTL_MS).submit();

        for (int attempt = 0; attempt < RESOLVE_POLL_ATTEMPTS; attempt++) {
            DrawEntry entry = listByKey().get(key);
            if (entry != null && entry.isResolved()) {
                assertAll(
                        () -> assertSame(DrawKind.ENTITY, entry.kind()),
                        () -> assertEquals(PREFIX + "self", entry.key()));
                return;
            }
            sleep();
        }
        throw new TestAbortedException(
                "SKIPPED, not passed: the self highlight never resolved, so nothing was "
                        + "measured. Last seen: " + listByKey().get(key));
    }
}
