package com.botwithus.bot.core.runlog;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.diag.StubGuard;
import com.botwithus.bot.api.gameval.GamevalIndex;
import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.core.impl.EventBusImpl;
import com.botwithus.bot.core.impl.GameAPIImpl;
import com.botwithus.bot.core.impl.MessageBusImpl;
import com.botwithus.bot.core.impl.ScriptContextImpl;
import com.botwithus.bot.core.impl.snapshot.GameSnapshotImpl;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.core.runtime.ScriptRuntime;
import com.botwithus.bot.core.shm.Layout;
import com.botwithus.bot.core.shm.SharedRegion;
import com.botwithus.bot.core.shm.SharedRegionEventPump;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Run logs against a real agent: the same objects {@code CliContext} builds for
 * a connection (real pipe, real mapping, real {@code GameAPIImpl}), throwaway
 * scripts that crash, and a grep of what reached disk for the names the agent
 * reports for this client.
 *
 * <p>The grep is only worth something if it can hit, so every redacted run is
 * paired with a control: the same script through a pass-through redactor, which
 * must leave at least one name in its file. The control's file is deleted once
 * counted (unless {@value #KEEP_CONTROL_ENV} is set) and its contents are never
 * printed.</p>
 *
 * <p>Opt in with {@code -Dbotwithus.smoke.live=true}; {@code -Dbotwithus.harness.pid}
 * pins the client. Files land under {@code build/live-runlogs/}.</p>
 */
class LiveScriptRunLogSmokeTest {

    private static final String PID_PROPERTY = "botwithus.harness.pid";
    /**
     * Set to keep the control file for an outside grep to be checked against. It
     * holds real names; whoever sets this deletes it afterwards.
     */
    private static final String KEEP_CONTROL_ENV = "BWU_RUNLOG_KEEP_CONTROL";
    private static final String PIPE_PREFIX = "BotWithUs_";
    private static final int IN_WORLD = 30;
    private static final long STOP_WAIT_MS = 15_000;
    private static final int WALK_ACTION = 23;
    private static final int WALK_PARAM1 = 1;
    private static final int WALK_LOOP_DELAY_MS = 600;
    private static final int WALK_MAX_LOOPS = 20;
    private static final int MIN_NAME_LENGTH = 2;
    private static final Duration AGENT_INFO_WAIT = Duration.ofSeconds(10);

    /** Names the scripts write into their own logs; set by the test before a run. */
    private static volatile List<String> liveNames = List.of();

    @ScriptManifest(name = "Live Npe Probe", version = "0.1", author = "runlog-smoke")
    public static final class NpeProbe implements BotScript {
        private static final BotLogger log = LoggerFactory.getLogger(NpeProbe.class);
        private GameAPI api;
        private String target;

        @Override
        public void onStart(ScriptContext ctx) {
            api = ctx.getGameAPI();
            log.info("starting for {}", String.join(" / ", liveNames));
        }

        @Override
        public int onLoop() {
            api.ping();
            api.getClientCount();
            log.debug("about to fail; known as {}", String.join(" / ", liveNames));
            return target.length();
        }

        @Override
        public void onStop() {
        }
    }

    @ScriptManifest(name = "Live Overflow Probe", version = "0.1", author = "runlog-smoke")
    public static final class OverflowProbe implements BotScript {
        private GameAPI api;

        @Override
        public void onStart(ScriptContext ctx) {
            api = ctx.getGameAPI();
        }

        @Override
        public int onLoop() {
            api.ping();
            return descend(0);
        }

        private int descend(int depth) {
            return descend(depth + 1) + 1;
        }

        @Override
        public void onStop() {
        }
    }

    /**
     * Takes one walk step to a neighbouring tile, waits until the tile changes,
     * then fails. Tries east, west, north and south in turn, because any one of
     * them can be a wall wherever the character happens to stand.
     */
    @ScriptManifest(name = "Live Walk Probe", version = "0.1", author = "runlog-smoke")
    public static final class WalkProbe implements BotScript {
        private static final int[][] STEPS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        private static final int LOOPS_PER_STEP = 4;
        private GameAPI api;
        private int startX;
        private int startY;
        private int loops;

        @Override
        public void onStart(ScriptContext ctx) {
            api = ctx.getGameAPI();
            LocalPlayer self = api.snapshot().self();
            startX = self.tileX();
            startY = self.tileY();
        }

        @Override
        public int onLoop() {
            LocalPlayer self = api.snapshot().self();
            boolean hasMoved = self.tileX() != startX || self.tileY() != startY;
            if (hasMoved || loops >= WALK_MAX_LOOPS) {
                throw new IllegalStateException("walk probe done, moved=" + hasMoved);
            }
            if (loops % LOOPS_PER_STEP == 0 && loops / LOOPS_PER_STEP < STEPS.length) {
                int[] step = STEPS[loops / LOOPS_PER_STEP];
                api.queueAction(new GameAction(WALK_ACTION, WALK_PARAM1,
                        startX + step[0], startY + step[1]));
            }
            loops++;
            return WALK_LOOP_DELAY_MS;
        }

        @Override
        public void onStop() {
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "botwithus.smoke.live", matches = "true")
    void crashes_areLoggedInFull_andNoLiveNameReachesDisk() throws Exception {
        try (Live live = Live.connect()) {
            Path dir = outputDir("crashes");
            String npe = live.runOnce(new NpeProbe(), dir.resolve("redacted"), Redactor::new);
            String overflow = live.runOnce(new OverflowProbe(), dir.resolve("redacted"), Redactor::new);
            System.out.println("==== redacted NPE run log ====\n" + npe);
            System.out.println("==== redacted StackOverflowError run log ====\n" + overflow);

            assertCrash(npe, "java.lang.NullPointerException", NpeProbe.class.getName() + ".onLoop(");
            assertCrash(overflow, "java.lang.StackOverflowError", OverflowProbe.class.getName() + ".descend(");
            assertEquals(0, hits(npe + overflow, live.names()), "a live name reached a redacted log");

            String control = live.runOnce(new NpeProbe(), dir.resolve("control"),
                    names -> Redactor.passThroughForLivenessCheck());
            long controlHits = hits(control, live.names());
            if (System.getenv(KEEP_CONTROL_ENV) == null) {
                deleteTree(dir.resolve("control"));
            }
            System.out.println("liveness control: " + controlHits + " line(s) with a live name");
            assertTrue(controlHits > 0, "the grep found nothing even without the redactor");
        }
    }

    /**
     * The header says what the agent says about itself, and {@code unknown} for
     * an agent that predates {@code rpc.agent_info}. Which of the two to expect is
     * read from {@code rpc.list_methods}, never from the text of an error.
     */
    @Test
    @EnabledIfSystemProperty(named = "botwithus.smoke.live", matches = "true")
    void header_carriesTheAgentsOwnIdentity_orUnknown() throws Exception {
        try (Live live = Live.connect()) {
            boolean hasMethod = live.api().listMethods().contains(AgentInfoProbe.METHOD);
            String text = live.runOnce(new NpeProbe(), outputDir("agent-info").resolve("redacted"),
                    Redactor::new);
            List<String> identity = text.lines()
                    .filter(l -> l.startsWith("agent_build: ") || l.startsWith("game_revision: "))
                    .toList();
            System.out.println("agent lists " + AgentInfoProbe.METHOD + ": " + hasMethod);
            identity.forEach(l -> System.out.println("header " + l));
            if (hasMethod) {
                assertAll(
                        () -> assertTrue(identity.get(0).matches("agent_build: [0-9a-f]{32}"), identity.get(0)),
                        () -> assertTrue(identity.get(1).matches("game_revision: \\d+-\\d+"), identity.get(1)));
            } else {
                assertEquals(List.of("agent_build: unknown", "game_revision: unknown"), identity);
            }
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "botwithus.smoke.live", matches = "true")
    void inWorld_aWalkStep_leavesATileCrumb() throws Exception {
        try (Live live = Live.connect()) {
            Assumptions.assumeTrue(live.gameState() == IN_WORLD, "needs the client in the world");
            String text = live.runOnce(new WalkProbe(), outputDir("walk").resolve("redacted"),
                    Redactor::new);
            System.out.println("==== redacted walk run log ====\n" + text);
            List<String> tiles = text.lines().filter(l -> l.matches(".*Z tile \\d+,\\d+,\\d+$")).toList();
            assertAll(
                    () -> assertTrue(text.contains("exception: java.lang.IllegalStateException: "
                            + "walk probe done, moved=true"), "the step must have moved the player"),
                    () -> assertTrue(tiles.size() >= 2, "expected a start and a moved tile: " + tiles),
                    () -> assertTrue(text.contains(" rpc queue_action action_id=23")
                            || text.contains(" rpc queue_action "), "the walk RPC is a crumb"),
                    () -> assertEquals(0, hits(text, live.names())));
        }
    }

    private static void assertCrash(String text, String exception, String topFramePrefix) {
        assertAll(
                () -> assertTrue(text.startsWith("# bwu-run-log v1\n")),
                () -> assertTrue(text.contains("\n---\n")),
                () -> assertTrue(text.contains("\nprotocol_version: " + Layout.PROTOCOL_VERSION + "\n")),
                () -> assertTrue(text.contains("=== CRASH phase=on_loop ")),
                () -> assertTrue(text.contains("\nexception: " + exception)),
                () -> assertTrue(text.contains("\ntop_frame: " + topFramePrefix), "top_frame"),
                () -> assertTrue(text.contains("\n  " + exception), "the trace follows"),
                () -> assertTrue(text.contains(" rpc rpc.ping"), "an RPC breadcrumb"),
                () -> assertFalse(text.contains("=== BREADCRUMBS last=0 ===")),
                () -> assertTrue(text.contains("\n=== END ===\n")));
    }

    /** Lines that contain any of {@code names}, case-insensitively. */
    private static long hits(String text, List<String> names) {
        return text.lines().filter(line -> {
            String lower = line.toLowerCase(Locale.ROOT);
            return names.stream().anyMatch(n -> lower.contains(n.toLowerCase(Locale.ROOT)));
        }).count();
    }

    private static Path outputDir(String label) throws IOException {
        Path dir = Path.of("build", "live-runlogs",
                label + "-" + Instant.now().toString().replace(':', '-'));
        Files.createDirectories(dir);
        return dir;
    }

    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    /** One live connection, built the way the host builds one. */
    private record Live(String pipeName, PipeClient pipe, RpcClient rpc, SharedRegionEventPump pump,
                        GameAPIImpl api, ScriptContextImpl context, List<String> names,
                        AgentInfoProbe.Pending agent)
            implements AutoCloseable {

        static Live connect() throws IOException {
            String pipeName = resolvePipeName();
            long pid = SharedRegion.parsePid(pipeName).orElseThrow();
            PipeClient pipe = new PipeClient(pipeName);
            RpcClient rpc = new RpcClient(pipe);
            EventBusImpl bus = new EventBusImpl();
            SharedRegionEventPump pump = new SharedRegionEventPump(pid, bus::publish);
            GameAPIImpl api = new GameAPIImpl(rpc, null,
                    () -> new GameSnapshotImpl(pump.region().snapshot()),
                    new StubGuard(), bus::publish, GamevalIndex.empty());
            rpc.start();
            ScriptContextImpl context = new ScriptContextImpl(api, bus, new MessageBusImpl());
            List<String> names = namesFor(pipeName, rpc);
            System.out.println("live: " + names.size() + " name(s) known for this client");
            // Started exactly as CliContext.wireRunLogs starts it, then waited for
            // so a run cannot race it: this test is about the answer, not the race.
            AgentInfoProbe.Pending agent = AgentInfoProbe.start(
                    () -> rpc.callSync(AgentInfoProbe.METHOD, Map.of()), AgentInfoProbe.DEFAULT_DEADLINE);
            try {
                agent.awaitSettled(AGENT_INFO_WAIT);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new Live(pipeName, pipe, rpc, pump, api, context, names, agent);
        }

        int gameState() {
            return api.snapshot().gameState();
        }

        /** Runs one script to its crash with a fresh {@link RunLogs} and returns its file. */
        String runOnce(BotScript script, Path root,
                       Function<Supplier<KnownNames>, Redactor> redactors) throws Exception {
            RunLogs logs = new RunLogs(root, HostIdentity.current(RunLogs.class), Clock.systemUTC(),
                    new RunLogRetention(), redactors);
            RunLogLogback.install(logs);
            rpc.setCallObserver(logs::recordRpc);
            ScriptRuntime runtime = new ScriptRuntime(context, n -> { }, () -> { });
            runtime.setConnectionName(pipeName);
            runtime.setRunLogs(logs);
            runtime.setSlot(1);
            runtime.setAgentIdentity(agent);
            KnownNames known = KnownNames.of(names, List.of());
            runtime.setRunNames(() -> known);
            liveNames = names;
            ScriptRunner runner = runtime.registerScript(script);
            runner.start();
            assertTrue(runner.awaitStop(STOP_WAIT_MS), "script did not stop");
            runtime.stopAll();
            Path file = logs.currentOrLastLog(pipeName, runner.getScriptName()).orElseThrow();
            return Files.readString(file, StandardCharsets.UTF_8);
        }

        private static String resolvePipeName() {
            String pid = System.getProperty(PID_PROPERTY);
            if (pid != null && !pid.isBlank()) {
                return PIPE_PREFIX + pid.strip();
            }
            List<String> pipes = PipeClient.scanPipes();
            if (pipes.isEmpty()) {
                fail("No BotWithUs_<pid> pipe visible; inject the agent into a running client first");
            }
            return pipes.getFirst();
        }

        /** The connection name plus every name the agent reports for the account. */
        private static List<String> namesFor(String pipeName, RpcClient rpc) {
            List<String> names = new ArrayList<>();
            names.add(pipeName);
            Map<String, Object> info = rpc.callSync("get_account_info", Map.of());
            for (String key : List.of("account_name", "display_name", "jx_display_name")) {
                Object value = info.get(key);
                if (value != null && value.toString().strip().length() >= MIN_NAME_LENGTH) {
                    names.add(value.toString().strip());
                }
            }
            return List.copyOf(names);
        }

        @Override
        public void close() {
            rpc.close();
            pump.close();
            pipe.close();
        }
    }
}
