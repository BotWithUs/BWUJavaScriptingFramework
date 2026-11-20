package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.entities.WorldMapElements;
import com.botwithus.bot.api.inventory.UseOn;
import com.botwithus.bot.api.model.GeOffer;
import com.botwithus.bot.api.model.PathResult;
import com.botwithus.bot.api.model.ScreenProjection;
import com.botwithus.bot.api.model.WorldMapElement;
import com.botwithus.bot.api.model.WorldPathConfig;
import com.botwithus.bot.api.model.WorldToScreen;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.InventoryItem;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.api.snapshot.OpenInterface;
import com.botwithus.bot.api.world.Coordinate;
import com.botwithus.bot.api.world.HopRefusal;
import com.botwithus.bot.api.world.RectangularArea;
import com.botwithus.bot.api.world.World;
import com.botwithus.bot.api.world.WorldHopRequest;
import com.botwithus.bot.api.world.WorldList;
import com.botwithus.bot.api.world.Worlds;
import com.botwithus.bot.core.cache.NXTCache;
import com.botwithus.bot.core.impl.snapshot.GameSnapshotImpl;
import com.botwithus.bot.core.pipe.AgentSelection;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.shm.SharedRegion;
import com.botwithus.bot.core.test.LiveAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The client-state RPCs, cache world map elements and WorldWalker avoid areas against a
 * live client.</b>
 *
 * <p>Opt in with {@code -Dbotwithus.live.clientState=true} on top of {@code :core:harnessTest}.
 * Point {@code -Dnxtcache.dll} and {@code -Dworldwalker.dll} at builds that export the
 * world map placements and avoid areas. It walks the character a short way, uses an item,
 * and changes world once, so run it only on a disposable character. Pass
 * {@code -Dbotwithus.live.protectedAccount=<display name>} to have it refuse an account you
 * must not drive. Every check logs what it saw.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "botwithus.smoke.live", matches = "true")
@EnabledIfSystemProperty(named = "botwithus.live.clientState", matches = "true")
class LiveClientStateTest {

    private static final Logger log = LoggerFactory.getLogger(LiveClientStateTest.class);

    private static final String PROTECTED_ACCOUNT_PROPERTY = "botwithus.live.protectedAccount";
    private static final int GE_SLOTS = 8;
    private static final int LUMBRIDGE_LODESTONE_X = 3233;
    private static final int LUMBRIDGE_LODESTONE_Y = 3222;
    private static final int HOME_TELEPORT_CATEGORY = 2613;
    private static final int LODESTONE_TOLERANCE = 2;
    private static final int MAP_RADIUS = 40;
    /** The own tile must project within this fraction of the viewport of its centre. */
    private static final double CENTRE_TOLERANCE = 0.25;
    private static final int KNIFE = 946;
    private static final int LOGS = 1511;
    private static final int DETOUR_LEG = 14;
    private static final int BOX_HALF_WIDTH = 3;
    private static final long WALK_TIMEOUT_MS = 60_000;
    private static final long SHORT_TIMEOUT_MS = 8_000;
    private static final long POLL_MS = 100;
    private static final double SEGMENT_STEP = 0.25;

    private SharedRegion region;
    private RpcClient rpc;
    private NXTCache cache;
    private GameAPIImpl api;

    @BeforeAll
    void connect() throws IOException {
        AgentSelection.Attach agent = LiveAgent.attachOrFail();
        region = SharedRegion.open(agent.pid());
        rpc = new RpcClient(new PipeClient(agent.pipeName()));
        cache = NXTCache.openForHost();
        api = new GameAPIImpl(rpc, cache, () -> new GameSnapshotImpl(region.snapshot()));
        Map<String, Object> account = rpc.callSync("get_account_info", Map.of());
        String name = String.valueOf(account.get("display_name"));
        log.info("client state live: account '{}', member {}, NXTCache wme export {}", name,
                account.get("is_member"), NXTCache.supportsWorldMapElements());
        String protectedAccount = System.getProperty(PROTECTED_ACCOUNT_PROPERTY, "");
        Assumptions.assumeFalse(!protectedAccount.isBlank() && protectedAccount.equalsIgnoreCase(name),
                "SKIPPED: refusing to drive the protected account");
        assertEquals(GameSnapshot.GAME_STATE_IN_GAME, api.snapshot().gameState(),
                "the character must be in the world");
    }

    @AfterAll
    void disconnect() throws Exception {
        if (rpc != null) {
            rpc.close();
        }
        if (cache != null) {
            cache.close();
        }
        if (region != null) {
            region.close();
        }
    }

    @Test
    @Order(1)
    void worldList_isPopulated_andHoldsTheCurrentWorld() {
        WorldList list = api.getWorldList();
        int current = api.getCurrentWorld();
        log.info("client state live: world list {} worlds (total {}, skipped {}, truncated {}); "
                        + "current {} = {}", list.worlds().size(), list.total(), list.skipped(),
                list.truncated(), current, list.find(current).orElse(null));
        list.worlds().stream().limit(GE_SLOTS).forEach(w -> log.info("client state live:   {}", w));

        assertTrue(list.isFetched(), "the world list is empty in the world");
        assertTrue(list.find(current).isPresent(), "the current world is not in the list");
        assertTrue(list.worlds().stream().anyMatch(World::isMembers), "no members world decoded");
        assertTrue(list.worlds().stream().anyMatch(w -> !w.isMembers()), "no free world decoded");
    }

    @Test
    @Order(2)
    void geOffers_readEightSlots() {
        List<GeOffer> offers = api.getGeOffers();
        offers.forEach(o -> log.info("client state live: GE {}", o));

        assertEquals(GE_SLOTS, offers.size());
        for (int i = 0; i < GE_SLOTS; i++) {
            assertEquals(i, offers.get(i).slot());
        }
    }

    @Test
    @Order(3)
    void worldMap_nearLumbridge_includesTheLodestone() {
        Assumptions.assumeTrue(NXTCache.supportsWorldMapElements(),
                "SKIPPED, not passed: this NXTCache.dll has no world map placement export");
        List<WorldMapElement> near = api.mapElements().query()
                .near(LUMBRIDGE_LODESTONE_X, LUMBRIDGE_LODESTONE_Y, MAP_RADIUS).sortByDistance().all();
        near.forEach(e -> log.info("client state live: wme {} '{}' / '{}' cat {} at ({},{},{}) area {}",
                e.id(), e.name(), e.tooltip(), e.category(), e.tileX(), e.tileY(), e.plane(), e.areaId()));
        Optional<WorldMapElement> lodestone = near.stream()
                .filter(e -> e.category() == HOME_TELEPORT_CATEGORY).findFirst();

        assertTrue(lodestone.isPresent(), "no HOME_TELEPORT_DESTINATION element near Lumbridge");
        WorldMapElement l = lodestone.orElseThrow();
        assertTrue(Math.max(Math.abs(l.tileX() - LUMBRIDGE_LODESTONE_X),
                Math.abs(l.tileY() - LUMBRIDGE_LODESTONE_Y)) <= LODESTONE_TOLERANCE,
                "the lodestone element is not at the lodestone: " + l);
        assertTrue(near.stream().allMatch(e -> e.areaId() == WorldMapElements.MAIN_AREA_ID));
    }

    @Test
    @Order(4)
    void worldToScreen_ownTile_isNearTheViewportCentre() {
        LocalPlayer self = api.snapshot().self();
        WorldToScreen w = api.worldToScreen(List.of(tileOf(self),
                new Coordinate(self.tileX(), self.tileY(), (self.plane() + 1) % (Coordinate.MAX_PLANE + 1))));
        log.info("client state live: own tile ({},{},{}) -> {}", self.tileX(), self.tileY(), self.plane(), w);

        WorldToScreen.Viewport vp = w.viewport().orElseThrow();
        ScreenProjection own = w.points().getFirst();
        assertTrue(own.onScreen(), "own tile is not on screen: " + own);
        assertTrue(Math.abs(own.x() - vp.centreX()) <= vp.width() * CENTRE_TOLERANCE,
                "own tile x " + own.x() + " is far from the viewport centre " + vp.centreX());
        assertTrue(Math.abs(own.y() - vp.centreY()) <= vp.height() * CENTRE_TOLERANCE,
                "own tile y " + own.y() + " is far from the viewport centre " + vp.centreY());
        assertEquals(ScreenProjection.Result.WRONG_PLANE, w.points().get(1).result());
    }

    @Test
    @Order(5)
    void walkWorldPath_withAnAvoidBox_detoursAndNeverEntersIt() {
        LocalPlayer self = api.snapshot().self();
        Coordinate start = tileOf(self);
        Detour detour = findDetour(start).orElse(null);
        Assumptions.assumeTrue(detour != null,
                "SKIPPED, not passed: no straight leg from " + start + " that a box can divert");
        log.info("client state live: avoid {} on the way {} -> {}; plain route {} (links {}), "
                        + "avoiding route {} (links {})", detour.box(), start, detour.goal(),
                tiles(detour.plain()), detour.plain().links(), tiles(detour.avoiding()),
                detour.avoiding().links());

        List<Coordinate> walked = walkSampling(detour.goal(), WorldPathConfig.DEFAULT.avoid(detour.box()));
        Coordinate end = walked.getLast();
        List<Coordinate> inside = walked.stream().filter(detour.box()::contains).toList();
        log.info("client state live: walked {} samples, end {}, inside the box {}", walked.size(), end,
                inside);

        assertEquals(List.of(), inside, "the walk entered the avoid box");
        assertTrue(end.chebyshevDistance(detour.goal()) <= 1, "the walk did not arrive: " + end);
        assertTrue(walked.stream().map(c -> c.x() + "," + c.y()).collect(Collectors.toSet()).size() > 1);
    }

    @Test
    @Order(6)
    void membersWorld_isRefusedOnAFreeAccount() {
        Assumptions.assumeFalse(api.snapshot().self().isMember(),
                "SKIPPED, not passed: the character is a member");
        World members = api.getWorldList().worlds().stream().filter(World::isMembers).findFirst().orElseThrow();

        WorldHopRequest r = api.requestWorld(members.id());
        log.info("client state live: set_world {} (members) -> {}", members.id(), r);

        assertFalse(r.started());
        assertEquals(Optional.of(HopRefusal.MEMBERS_WORLD), r.refusal());
    }

    @Test
    @Order(7)
    void hopWorld_arrives() {
        int from = api.getCurrentWorld();
        boolean isMember = api.snapshot().self().isMember();
        World target = api.getWorldList().worlds().stream()
                .filter(w -> w.id() != from && (isMember || !w.isMembers()))
                .filter(w -> w.flags() == 0)
                .filter(w -> w.activity().isBlank() || "-".equals(w.activity().trim()))
                .min(Comparator.comparingInt(World::id)).orElseThrow();
        long t0 = System.nanoTime();

        Worlds.HopResult r = api.worlds().hop(target.id());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        log.info("client state live: hop {} -> {} ({}) = {} in {} ms; now world {}, gameState {}",
                from, target.id(), target, r, ms, api.getCurrentWorld(), api.snapshot().gameState());

        assertTrue(r.isArrived(), "hop did not arrive: " + r);
        assertEquals(target.id(), api.getCurrentWorld());
        assertEquals(GameSnapshot.GAME_STATE_IN_GAME, api.snapshot().gameState());
        long t1 = System.nanoTime();
        boolean isReady = api.worlds().awaitSceneReady(SHORT_TIMEOUT_MS);
        log.info("client state live: scene ready {} {} ms after arrival", isReady,
                (System.nanoTime() - t1) / 1_000_000);
        assertTrue(isReady, "the scene never became projectable after the hop");
    }

    @Test
    @Order(8)
    void knifeOnLog_opensTheGamesDialog_andEndsTargetMode() {
        Optional<InventoryItem> knife = api.backpack().findFirst(KNIFE);
        Optional<InventoryItem> logs = api.backpack().findFirst(LOGS);
        Assumptions.assumeTrue(knife.isPresent() && logs.isPresent(),
                "SKIPPED, not passed: the backpack lacks a knife or logs");
        assertTrue(api.worlds().awaitSceneReady(SHORT_TIMEOUT_MS), "the scene is not ready");
        Set<Integer> before = openIds();

        UseOn.Result r = api.backpack().useOn(knife.orElseThrow(), logs.orElseThrow());
        boolean isOpened = waitFor(() -> !newlyOpen(before).isEmpty(), SHORT_TIMEOUT_MS);
        log.info("client state live: knife {} on logs {} -> {}; target mode now {}; opened {}",
                knife.orElseThrow(), logs.orElseThrow(), r, api.getTargetMode(), newlyOpen(before));

        assertEquals(UseOn.Result.USED, r);
        assertFalse(api.getTargetMode().active(), "target mode was left armed");
        assertTrue(isOpened, "no interface opened after the knife was used on the logs");
    }

    // ---------------------------------------------------------------- detour

    /** A leg, a box across it, and the two plans: without the box, and avoiding it. */
    private record Detour(Coordinate goal, RectangularArea box, PathResult plain, PathResult avoiding) {
    }

    private Optional<Detour> findDetour(Coordinate start) {
        int[][] directions = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}};
        for (int[] d : directions) {
            Coordinate goal = start.offset(d[0] * DETOUR_LEG, d[1] * DETOUR_LEG);
            Coordinate mid = start.offset(d[0] * DETOUR_LEG / 2, d[1] * DETOUR_LEG / 2);
            RectangularArea box = d[0] == 0
                    ? new RectangularArea(mid.x() - BOX_HALF_WIDTH, mid.y() - 1, mid.x() + BOX_HALF_WIDTH,
                            mid.y() + 1, start.plane())
                    : new RectangularArea(mid.x() - 1, mid.y() - BOX_HALF_WIDTH, mid.x() + 1,
                            mid.y() + BOX_HALF_WIDTH, start.plane());
            PathResult plain = plan(start, goal, WorldPathConfig.DEFAULT);
            PathResult avoiding = plan(start, goal, WorldPathConfig.DEFAULT.avoid(box));
            log.info("client state live: candidate {} -> {} box {}: plain {} crosses {}, avoiding {} crosses {}",
                    start, goal, box, plain.found(), crosses(start, plain, box), avoiding.found(),
                    crosses(start, avoiding, box));
            if (plain.found() && avoiding.found() && crosses(start, plain, box) && !crosses(start, avoiding, box)) {
                return Optional.of(new Detour(goal, box, plain, avoiding));
            }
        }
        return Optional.empty();
    }

    private PathResult plan(Coordinate start, Coordinate goal, WorldPathConfig config) {
        return api.findWorldPath(start.x(), start.y(), goal.x(), goal.y(), start.plane(), config);
    }

    /** Whether the polyline start -> waypoints passes through {@code box}. */
    private static boolean crosses(Coordinate start, PathResult path, RectangularArea box) {
        double x = start.x();
        double y = start.y();
        for (int[] p : path.path()) {
            double dx = p[0] - x;
            double dy = p[1] - y;
            int steps = (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dy)) / SEGMENT_STEP);
            for (int i = 0; i <= steps; i++) {
                double t = steps == 0 ? 0 : (double) i / steps;
                if (box.contains((int) Math.round(x + dx * t), (int) Math.round(y + dy * t), box.plane())) {
                    return true;
                }
            }
            x = p[0];
            y = p[1];
        }
        return false;
    }

    private List<Coordinate> walkSampling(Coordinate goal, WorldPathConfig config) {
        List<Coordinate> walked = new ArrayList<>();
        api.walkWorldPathAsync(goal.x(), goal.y(), goal.plane(), true, config);
        long deadline = System.currentTimeMillis() + WALK_TIMEOUT_MS;
        String state = api.getWalkStatus().state();
        while (("walking".equals(state) || "idle".equals(state) && walked.isEmpty())
                && System.currentTimeMillis() < deadline) {
            walked.add(tileOf(api.snapshot().self()));
            sleep(POLL_MS);
            state = api.getWalkStatus().state();
        }
        walked.add(tileOf(api.snapshot().self()));
        log.info("client state live: walk to {} ended '{}'", goal, state);
        return walked;
    }

    private static String tiles(PathResult path) {
        return path.path().stream().map(p -> "(" + p[0] + "," + p[1] + ")").collect(Collectors.joining(" "));
    }

    // ---------------------------------------------------------------- helpers

    private Set<Integer> openIds() {
        return api.snapshot().openInterfaces().stream().map(OpenInterface::id).collect(Collectors.toSet());
    }

    private List<Integer> newlyOpen(Set<Integer> before) {
        return openIds().stream().filter(id -> !before.contains(id)).sorted().toList();
    }

    private static Coordinate tileOf(LocalPlayer self) {
        return new Coordinate(self.tileX(), self.tileY(), self.plane());
    }

    private static boolean waitFor(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            sleep(POLL_MS);
        }
        return condition.getAsBoolean();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
