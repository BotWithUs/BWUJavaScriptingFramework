package com.botwithus.bot.cli.gui.preview;

import com.botwithus.bot.api.runtime.ReconnectState;
import com.botwithus.bot.cli.GameState;
import com.botwithus.bot.cli.GameStatus;
import com.botwithus.bot.cli.clients.ClientLifecycle;
import com.botwithus.bot.cli.clients.ClientRecord;
import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.events.ClientRef;
import com.botwithus.bot.cli.events.HostEvent;
import com.botwithus.bot.cli.events.HostEvent.ClientIdentified;
import com.botwithus.bot.cli.events.HostEvent.ClientOpened;
import com.botwithus.bot.cli.events.HostEvent.ConnectionLost;
import com.botwithus.bot.cli.events.HostEvent.ReconnectStateChanged;
import com.botwithus.bot.cli.events.HostEvent.ScriptStarted;
import com.botwithus.bot.cli.gui.pages.connections.ConnectionDetail;
import com.botwithus.bot.cli.gui.pages.connections.ConnectionRow;
import com.botwithus.bot.cli.gui.pages.connections.ConnectionRows;
import com.botwithus.bot.cli.gui.pages.connections.ConnectionText;
import com.botwithus.bot.cli.gui.pages.connections.ConnectionsModel;
import com.botwithus.bot.cli.gui.pages.connections.ConnectionsView;
import com.botwithus.bot.cli.gui.pages.connections.FoundPipe;
import com.botwithus.bot.cli.gui.pages.connections.LinkStats;
import com.botwithus.bot.cli.gui.pages.connections.ScriptChip;
import com.botwithus.bot.cli.gui.pages.connections.Timeline;
import com.botwithus.bot.cli.gui.pages.connections.Tone;
import com.botwithus.bot.core.rpc.ReconnectPolicy;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * DEV ONLY. The Connections page's fixture: the design's sample host, seven
 * clients connected (one reconnecting), two pipes found and one closed client,
 * run through the real {@link ConnectionRows} and {@link Timeline}. Scenario
 * hooks switch it to the scanning, no-pipes and auto-connect-off states.
 * Actions do nothing but move the console routing, which the legend shows.
 */
public final class FixtureConnectionsModel implements ConnectionsModel {

    static final String OAKHEART = "3f9a1c2e-58b0-4d7a-9e21-6c0f4b7d2a18";
    static final String FERNMOSS = "b71d09e4-2c61-4f3e-8a57-1d9e0c4b6f33";
    static final String HOLLOWMERE = "e4410b7a-9d25-4c6b-a1f8-73e02b5d9c46";
    static final String BRACKENRIDGE = "0a6d2f58-c3e1-47b9-9f04-5e8b17a2d6c9";
    static final String MIRELOCK_PIPE = "BotWithUs_19544";
    private static final String QUILLON = "5c20aa91-7e44-4b18-b3c0-2f8d61e9a705";
    private static final String TAMSIN = "90ce3f15-4b87-4e2a-8d6c-1a5f09b3e724";
    private static final String KESTREL = "81c5e0b2-6d4f-4a37-b9e8-3f0a2c7d5e16";
    private static final String ASHGROVE = "c93f5a27-81d0-4e6b-9c42-b7e15f0a38d1";
    private static final String OAK_PIPE = "BotWithUs_14208";
    private static final String FERN_PIPE = "BotWithUs_9932";
    static final String QUILL_PIPE = "BotWithUs_11820";
    private static final String HOLLOW_PIPE = "BotWithUs_10344";
    private static final String TAMSIN_PIPE = "BotWithUs_15002";
    private static final String KESTREL_PIPE = "BotWithUs_18104";
    private static final String ASH_PIPE = "BotWithUs_17012";
    private static final String UNKNOWN_PIPE = "BotWithUs_19230";
    private static final String WOODCUTTING = "Woodcutting";
    private static final int ATTEMPT = 4;
    private static final long NEXT_TRY_S = 8L;
    private static final long DOWN_S = 42L;
    private static final long CLOSED_MIN = 3L;
    private static final long SCAN_EVERY_MS = 2_000L;

    /** One connected client's fixture numbers. */
    private record Live(String uuid, String pipe, String name, GameStatus game, Duration up, long calls,
                        long errors, double rpcMs) { }

    private final Instant now = Instant.now();
    private final List<ClientRecord> clients = new ArrayList<>();
    private final List<FoundPipe> found = new ArrayList<>();
    private final Map<String, LinkStats> stats = new HashMap<>();
    private final Map<ClientKey, Instant> droppedAt = new HashMap<>();
    private final Map<String, List<HostEvent>> history = new HashMap<>();
    private final Map<String, List<ScriptChip>> scripts = new HashMap<>();
    private final Map<String, List<String>> groups = new HashMap<>();
    private boolean isAutoConnect = true;
    private boolean isScanning;
    private Optional<String> consoleTarget = Optional.of(OAK_PIPE);
    private Optional<String> outputFilter = Optional.empty();

    /** The everyday host. */
    public static FixtureConnectionsModel busy() {
        FixtureConnectionsModel model = new FixtureConnectionsModel();
        model.live(new Live(OAKHEART, OAK_PIPE, "Oakheart", inGame(84, true), hm(2, 14), 18_204L, 0L, 2.8));
        model.live(new Live(FERNMOSS, FERN_PIPE, "Fernmoss", inGame(2, true), hm(6, 2), 40_112L, 1L, 3.4));
        model.live(new Live(QUILLON, QUILL_PIPE, "Quillon", inGame(117, false), hm(0, 3), 412L, 0L, 2.1));
        model.reconnecting();
        model.live(new Live(TAMSIN, TAMSIN_PIPE, "Tamsin Vale", inGame(31, false), hm(1, 40), 9_310L, 0L, 2.6));
        model.live(new Live(KESTREL, KESTREL_PIPE, "Kestrel Moor",
                new GameStatus(GameState.LOBBY, OptionalInt.empty(), false), hm(4, 18), 2_210L, 0L, 2.2));
        model.live(new Live(ASHGROVE, ASH_PIPE, "Ashgrove", inGame(84, true), hm(0, 26), 6_044L, 4L, 4.9));
        model.found.add(new FoundPipe(MIRELOCK_PIPE, Optional.of("Mirelock"), inGame(102, true)));
        model.found.add(new FoundPipe(UNKNOWN_PIPE, Optional.empty(), GameStatus.UNKNOWN));
        model.closed();
        model.decorate();
        return model;
    }

    /** A scan from the page is running and nothing has answered yet. */
    public static FixtureConnectionsModel scanningEmpty() {
        FixtureConnectionsModel model = new FixtureConnectionsModel();
        model.isScanning = true;
        return model;
    }

    /** No client answered the last scan, and none is remembered. */
    public static FixtureConnectionsModel noPipes() {
        return new FixtureConnectionsModel();
    }

    /** Auto-connect is off, so two clients the busy host had connected are only found. */
    public static FixtureConnectionsModel autoConnectOff() {
        FixtureConnectionsModel model = busy();
        model.isAutoConnect = false;
        model.clients.removeIf(c -> c.pipe().equals(Optional.of(QUILL_PIPE)) || c.pipe().equals(Optional.of(ASH_PIPE)));
        model.found.add(0, new FoundPipe(QUILL_PIPE, Optional.of("Quillon"), inGame(117, false)));
        model.found.add(1, new FoundPipe(ASH_PIPE, Optional.of("Ashgrove"), inGame(84, true)));
        return model;
    }

    /** Filters the console output to Fernmoss, so the legend offers "Show all". */
    public FixtureConnectionsModel filteredToFernmoss() {
        outputFilter = Optional.of(FERN_PIPE);
        return this;
    }

    // ── Building the fixture ───────────────────────────────────────────────

    private void live(Live c) {
        clients.add(new ClientRecord(ClientKey.account(c.uuid()), new ClientLifecycle.Connected(),
                Optional.of(c.pipe()), Optional.of(c.name()), c.game(), c.game().world(),
                Optional.of(now.minus(c.up()))));
        stats.put(c.pipe(), new LinkStats(c.up(), c.calls(), c.errors(), c.rpcMs()));
        ClientRef ref = new ClientRef(ClientKey.account(c.uuid()), c.pipe());
        history.put(c.uuid(), List.of(new ClientOpened(ref, now.minus(c.up()).minusSeconds(1)),
                new ClientIdentified(ref, Optional.of(c.name()), now.minus(c.up()))));
    }

    private void reconnecting() {
        ClientKey key = ClientKey.account(HOLLOWMERE);
        clients.add(new ClientRecord(key, new ClientLifecycle.NotResponding(ATTEMPT,
                Optional.of(Duration.ofSeconds(NEXT_TRY_S)), OptionalInt.empty(), now), Optional.of(HOLLOW_PIPE),
                Optional.of("Hollowmere"), inGame(84, true), OptionalInt.of(84), Optional.of(now.minus(hm(1, 26)))));
        droppedAt.put(key, now.minusSeconds(DOWN_S));
        ClientRef ref = new ClientRef(key, HOLLOW_PIPE);
        Instant lost = now.minusSeconds(DOWN_S);
        history.put(HOLLOWMERE, List.of(
                new ClientIdentified(ref, Optional.of("Hollowmere"), now.minus(hm(1, 26))),
                new ScriptStarted(ref, WOODCUTTING, now.minus(hm(1, 25))),
                new ConnectionLost(ref, new IOException("Pipe closed"), lost),
                new ReconnectStateChanged(ref, new ReconnectState.Reconnecting(0L, ATTEMPT - 1, 4_000L),
                        lost.plusSeconds(26)),
                new ReconnectStateChanged(ref, new ReconnectState.Reconnecting(0L, ATTEMPT, NEXT_TRY_S * 1_000L),
                        now)));
    }

    private void closed() {
        clients.add(new ClientRecord(ClientKey.account(BRACKENRIDGE),
                new ClientLifecycle.Closed(now.minus(Duration.ofMinutes(CLOSED_MIN))), Optional.empty(),
                Optional.of("Brackenridge"), GameStatus.UNKNOWN, OptionalInt.of(44), Optional.empty()));
        ClientRef ref = new ClientRef(ClientKey.account(BRACKENRIDGE), "BotWithUs_8841");
        history.put(BRACKENRIDGE, List.of(
                new ClientIdentified(ref, Optional.of("Brackenridge"), now.minus(hm(3, 10))),
                new HostEvent.ClientClosed(ref, HostEvent.CloseCause.CONNECTION_LOST,
                        now.minus(Duration.ofMinutes(CLOSED_MIN)))));
    }

    /** Scripts, groups and a longer history for the clients a scenario opens. */
    private void decorate() {
        scripts.put(OAKHEART, List.of(new ScriptChip(WOODCUTTING, Tone.OK)));
        scripts.put(FERNMOSS, List.of(new ScriptChip("Divination", Tone.WARN)));
        scripts.put(HOLLOWMERE, List.of(new ScriptChip(WOODCUTTING, Tone.NEUTRAL)));
        scripts.put(TAMSIN, List.of(new ScriptChip("Cook's Assistant", Tone.ERROR)));
        scripts.put(KESTREL, List.of(new ScriptChip("Walk to Flag", Tone.NEUTRAL),
                new ScriptChip("Location Probe", Tone.NEUTRAL)));
        scripts.put(ASHGROVE, List.of(new ScriptChip("The Restless Ghost", Tone.OK)));
        scripts.put(BRACKENRIDGE, List.of(new ScriptChip("Divination", Tone.NEUTRAL)));
        groups.put(OAKHEART, List.of("Woodcutters"));
        groups.put(HOLLOWMERE, List.of("Woodcutters"));
        groups.put(TAMSIN, List.of("Questers"));
        groups.put(ASHGROVE, List.of("Questers"));
        ClientRef oak = new ClientRef(ClientKey.account(OAKHEART), OAK_PIPE);
        List<HostEvent> oakHistory = new ArrayList<>(history.get(OAKHEART));
        oakHistory.add(new ScriptStarted(oak, WOODCUTTING, now.minus(hm(2, 14)).plusSeconds(1)));
        history.put(OAKHEART, List.copyOf(oakHistory));
    }

    private static GameStatus inGame(int world, boolean isMember) {
        return new GameStatus(GameState.IN_GAME, OptionalInt.of(world), isMember);
    }

    private static Duration hm(long hours, long minutes) {
        return Duration.ofHours(hours).plusMinutes(minutes);
    }

    // ── ConnectionsModel ───────────────────────────────────────────────────

    @Override
    public ConnectionsView view() {
        Set<String> held = new HashSet<>();
        clients.forEach(c -> c.pipe().ifPresent(held::add));
        ConnectionRows.Reading reading = new ConnectionRows.Reading(clients, found, held, stats, consoleTarget,
                outputFilter, droppedAt, now);
        return new ConnectionsView(ConnectionRows.build(reading), isAutoConnect, "BotWithUs",
                Duration.ofMillis(SCAN_EVERY_MS), isScanning, Optional.empty());
    }

    @Override
    public int notResponding() {
        return view().notResponding();
    }

    @Override
    public ConnectionDetail detail(ConnectionRow row) {
        String uuid = row.accountUuid().orElse("");
        return new ConnectionDetail(row, row.accountUuid().map(u -> true),
                Timeline.of(history.getOrDefault(uuid, List.of())), scripts.getOrDefault(uuid, List.of()),
                groups.getOrDefault(uuid, List.of()), ConnectionText.policy(ReconnectPolicy.DEFAULT));
    }

    @Override public void scan() { }
    @Override public void connect(ConnectionRow row) { }
    @Override public void connectAllFound() { }
    @Override public void disconnect(ConnectionRow row) { }
    @Override public void retryNow(ConnectionRow row) { }
    @Override public void stopRetrying(ConnectionRow row) { }
    @Override public void forget(ConnectionRow row) { }
    @Override public void setResumeAfterRestart(ConnectionRow row, boolean isOn) { }
    @Override public void copyUuid(ConnectionRow row) { }

    @Override
    public void setConsoleTarget(ConnectionRow row) {
        consoleTarget = row.pipe();
    }

    @Override
    public void toggleOutputFilter(ConnectionRow row) {
        outputFilter = outputFilter.equals(row.pipe()) ? Optional.empty() : row.pipe();
    }

    @Override
    public void clearOutputFilter() {
        outputFilter = Optional.empty();
    }

    @Override
    public void setAutoConnect(boolean isOn) {
        isAutoConnect = isOn;
    }

    @Override
    public Optional<String> setPipePrefix(String prefix) {
        return Optional.empty();
    }
}
