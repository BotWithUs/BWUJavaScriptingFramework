package com.botwithus.bot.cli.gui.preview;

import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.cli.gui.usermode.board.BoardStatus;
import com.botwithus.bot.cli.gui.usermode.board.ClientActions;
import com.botwithus.bot.cli.gui.usermode.board.ClientBoard;
import com.botwithus.bot.cli.gui.usermode.board.ClientStatus;
import com.botwithus.bot.cli.gui.usermode.board.ClientView;
import com.botwithus.bot.cli.gui.usermode.board.InspectorTarget;
import com.botwithus.bot.cli.gui.usermode.board.ScriptEntry;
import com.botwithus.bot.cli.gui.usermode.board.ScriptInfo;

import imgui.ImGui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * Fixture data for the dev preview: the prototype's sample fleet, its script
 * catalogue, and a Woodcutting config to inspect. Account names and timings are
 * made-up sample data; none of it reaches the shipped app.
 */
final class FixtureBoard implements ClientBoard {

    private static final long MS = 1_000_000L;
    private static final int LANE = 24;
    private static final long SEED = 7L;
    private static final double JITTER_LOW = 0.8;
    private static final double JITTER_SPAN = 0.4;
    private static final double SPIKE = 2.6;

    static final ScriptInfo WOODCUTTING = new ScriptInfo("Woodcutting", "BotWithUs", "2.0",
            ScriptCategory.WOODCUTTING,
            "Chops a configurable tree at a configurable spot; banks, drops, or wood-boxes the logs.", 6, true);
    static final ScriptInfo FLETCHER = new ScriptInfo("Woodcutting Fletcher", "BotWithUs", "1.0",
            ScriptCategory.WOODCUTTING, "Chops trees and drops logs when full.", 0, false);
    static final ScriptInfo DIVINATION = new ScriptInfo("Divination", "BotWithUs", "1.0",
            ScriptCategory.DIVINATION, "Harvests wisps and converts memories at the nearest divination spot.",
            0, false);
    static final ScriptInfo COOKS = new ScriptInfo("Cook's Assistant", "BotWithUs", "1.0",
            ScriptCategory.QUESTING, "Solves Cook's Assistant (quest 257) end-to-end.", 0, false);
    static final ScriptInfo GHOST = new ScriptInfo("The Restless Ghost", "BotWithUs", "0.1",
            ScriptCategory.QUESTING, "Solves The Restless Ghost (quest 27) end-to-end.", 0, false);
    static final ScriptInfo WITCH = new ScriptInfo("Witch's Potion", "BotWithUs", "0.1",
            ScriptCategory.QUESTING, "Solves Witch's Potion (miniquest 178) end-to-end.", 0, false);
    static final ScriptInfo FLAG = new ScriptInfo("Walk to Flag", "BotWithUs", "1.0",
            ScriptCategory.UTILITY, "Walks to the world-map flag (varp 2807) using world pathfinding.", 0, false);
    static final ScriptInfo PROBE = new ScriptInfo("Location Probe", "BotWithUs", "1.0",
            ScriptCategory.UTILITY, "Logs snapshot.locations() count and sample rows each tick.", 0, false);
    static final ScriptInfo EXAMPLE = new ScriptInfo("Example Script", "BotWithUs", "1.0",
            ScriptCategory.UTILITY, "A demo script showing the entity query API and Live Config.", 3, true);

    private static final List<ScriptInfo> CATALOG =
            List.of(WOODCUTTING, FLETCHER, DIVINATION, COOKS, GHOST, WITCH, FLAG, PROBE, EXAMPLE);

    private static final Map<Integer, String> ITEMS = Map.of(
            1511, "Logs", 1521, "Oak logs", 1519, "Willow logs", 1517, "Maple logs",
            1515, "Yew logs", 1513, "Magic logs");

    private static final List<ConfigField> WOODCUTTING_FIELDS = List.of(
            ConfigField.choiceField("tree", "Tree",
                    List.of("Tree", "Oak", "Willow", "Maple", "Yew", "Magic", "Acadia", "Eucalyptus"), "Yew"),
            ConfigField.choiceField("location", "Location",
                    List.of("Auto-nearest", "Draynor yews", "Varrock palace yews", "Seers' Village maples"),
                    "Auto-nearest"),
            ConfigField.choiceField("disposal", "Disposal", List.of("Bank", "Drop", "Wood box"), "Bank"),
            ConfigField.choiceField("stopMode", "Stop at", List.of("Never", "Level", "Logs", "Time (min)"), "Level"),
            ConfigField.intField("stopValue", "Stop value", 90),
            ConfigField.itemIdField("logId", "Log item id", 1515));

    private final List<ClientView> clients;
    private final BoardStatus status;
    private final ScriptConfig applied = new ScriptConfig(Map.of());
    private final ClientActions actions = new NoActions();

    private FixtureBoard(List<ClientView> clients, BoardStatus status) {
        this.clients = clients;
        this.status = status;
    }

    static FixtureBoard sixClients() {
        return new FixtureBoard(baseFleet(), status(false, "BotWithUs_14208"));
    }

    static FixtureBoard twelveClients() {
        List<ClientView> all = new ArrayList<>(baseFleet());
        Random rng = new Random(SEED + 1);
        all.add(running("Wrenfield", 16640, 58, FLETCHER, 118, rng, -1));
        all.add(running("Ashgrove", 17012, 84, GHOST, 211, rng, 19));
        all.add(new ClientView("BotWithUs_17388", "Mirelock", 102, new ClientStatus.Reconnecting(2, 5, 4000)));
        all.add(new ClientView("BotWithUs_17720", "Sableton", 44, new ClientStatus.Idle()));
        all.add(running("Kestrel Moor", 18104, 2, FLAG, 74, rng, -1));
        all.add(running("Duskwater", 18466, 117, WOODCUTTING, 156, rng, -1));
        return new FixtureBoard(List.copyOf(all), status(false, "BotWithUs_14208"));
    }

    static FixtureBoard waiting() {
        return new FixtureBoard(List.of(), status(false, null));
    }

    static FixtureBoard offline() {
        return new FixtureBoard(List.of(), status(true, null));
    }

    private static BoardStatus status(boolean offline, String active) {
        return new BoardStatus(offline, offline ? 5 : 0, active, true, "\\\\.\\pipe\\BotWithUs_*", null, false);
    }

    private static List<ClientView> baseFleet() {
        Random rng = new Random(SEED);
        return List.of(
                running("Oakheart", 14208, 84, WOODCUTTING, 142, rng, -1),
                running("Fernmoss", 9932, 2, DIVINATION, 96, rng, -1),
                new ClientView("BotWithUs_11820", "Quillon", 117, new ClientStatus.Idle()),
                new ClientView("BotWithUs_7716", "BotWithUs_7716", 44, new ClientStatus.Loading()),
                new ClientView("BotWithUs_10344", "Hollowmere", 84, new ClientStatus.Lost(42_000, WOODCUTTING)),
                new ClientView("BotWithUs_15002", "Tamsin Vale", 31,
                        new ClientStatus.Crashed(COOKS, "NullPointerException in onLoop()")));
    }

    /** A running client whose last 24 loops jitter ±20% round {@code avgMs}; one spike at {@code spikeAt}. */
    private static ClientView running(String account, int pid, int world, ScriptInfo script, double avgMs,
                                      Random rng, int spikeAt) {
        long[] lane = new long[LANE];
        for (int i = 0; i < LANE; i++) {
            lane[i] = Math.round(avgMs * (JITTER_LOW + rng.nextDouble() * JITTER_SPAN) * MS);
        }
        if (spikeAt >= 0) {
            lane[spikeAt] = Math.round(avgMs * SPIKE * MS);
        }
        return new ClientView("BotWithUs_" + pid, account, world, new ClientStatus.Running(script, avgMs, lane));
    }

    @Override
    public List<ClientView> clients() {
        return clients;
    }

    @Override
    public BoardStatus status() {
        return status;
    }

    @Override
    public List<ScriptEntry> catalog() {
        List<ScriptEntry> entries = new ArrayList<>();
        for (int i = 0; i < CATALOG.size(); i++) {
            entries.add(new ScriptEntry(i, CATALOG.get(i)));
        }
        return entries;
    }

    @Override
    public Optional<InspectorTarget> inspect(String clientId) {
        return clients.stream()
                .filter(c -> c.id().equals(clientId) && c.status().isRunning())
                .findFirst()
                .map(this::target);
    }

    private InspectorTarget target(ClientView c) {
        boolean woodcutting = c.scriptOrNull() == WOODCUTTING;
        return new InspectorTarget(c.id(), c.account(), c.scriptOrNull(),
                woodcutting ? WOODCUTTING_FIELDS : List.of(),
                () -> applied, cfg -> { },
                woodcutting ? FixtureBoard::sampleScriptUi : null,
                id -> Optional.ofNullable(ITEMS.get(id)),
                () -> false);
    }

    /** Stands in for a script's own ImGui: plain widgets in the default font, unstyled by the host. */
    private static void sampleScriptUi() {
        ImGui.text("Tree:     Yew @ Auto-nearest");
        ImGui.text("Disposal: Bank");
        ImGui.text("Level:    78");
        ImGui.text("Runtime:  00:41:07");
        ImGui.text("Disposal:");
        ImGui.sameLine();
        ImGui.button("Bank");
        ImGui.sameLine();
        ImGui.button("Drop");
        ImGui.sameLine();
        ImGui.button("Wood box");
        ImGui.button("Stop");
    }

    @Override
    public ClientActions actions() {
        return actions;
    }

    /** The preview only draws; every action is a no-op. */
    private static final class NoActions implements ClientActions {
        @Override public void startScript(String clientId, ScriptEntry script) { }
        @Override public void stopScript(String clientId) { }
        @Override public void restartScript(String clientId) { }
        @Override public void reconnect(String clientId) { }
        @Override public void cancelReconnect(String clientId) { }
        @Override public void viewLog(String clientId) { }
        @Override public void retryHost() { }
    }
}
