package com.botwithus.bot.cli.gui.preview;

import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.gui.inspector.InspectorSource;
import com.botwithus.bot.cli.gui.inspector.InspectorSubject;
import com.botwithus.bot.cli.gui.inspector.InspectorSubject.ClientScript;
import com.botwithus.bot.cli.gui.inspector.InspectorSubject.ManagementScript;
import com.botwithus.bot.cli.gui.inspector.InspectorTarget;
import com.botwithus.bot.cli.gui.inspector.LiveInspectorSource;
import com.botwithus.bot.cli.gui.usermode.board.BoardStatus;
import com.botwithus.bot.cli.gui.usermode.board.ClientActions;
import com.botwithus.bot.cli.gui.usermode.board.ClientBoard;
import com.botwithus.bot.cli.gui.usermode.board.ClientView;
import com.botwithus.bot.cli.gui.usermode.board.PriceBadge;
import com.botwithus.bot.cli.gui.usermode.board.ScriptEntry;
import com.botwithus.bot.cli.gui.usermode.board.ScriptInfo;
import com.botwithus.bot.cli.gui.usermode.board.SubscriptionEntry;
import com.botwithus.bot.cli.gui.usermode.board.SubscriptionGroup;
import com.botwithus.bot.cli.gui.usermode.board.SubscriptionState;
import com.botwithus.bot.cli.management.Target;
import com.botwithus.bot.cli.management.TargetLabels;

import imgui.ImGui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Fixture data for the dev preview: the prototype's sample fleet, its script
 * catalogue, a Woodcutting config to inspect, and two management scripts. Account
 * names, script names and timings are made-up sample data; none of it reaches
 * the shipped app.
 */
final class FixtureBoard implements ClientBoard, InspectorSource {

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
    /** DIVINATION's index in {@link #CATALOG}, which is its picker key. */
    private static final int DIVINATION_KEY = CATALOG.indexOf(DIVINATION);
    private static final int DIVINATION_INSTALLED_BUILD = 6;
    private static final int DIVINATION_STORE_BUILD = 7;

    /** Item gamevals, as {@code GamevalIndex.gameval(ITEM, id)} answers them. */
    private static final Map<Integer, String> ITEMS = Map.of(
            1511, "LOGS", 1521, "OAK_LOGS", 1519, "WILLOW_LOGS", 1517, "MAPLE_LOGS",
            1515, "YEW_LOGS", 1513, "MAGIC_LOGS", 995, "COINS");

    /** A management script with one field of every type, and no UI of its own. */
    static final ScriptInfo BREAK_SCHEDULER = new ScriptInfo("Break Scheduler", "BotWithUs", "1.2",
            ScriptCategory.UTILITY, "Takes every client off for a break on a schedule.", 6, false);
    /** A management script that draws its own UI and declares no fields. */
    static final ScriptInfo FLEET_MONITOR = new ScriptInfo("Fleet Monitor", "BotWithUs", "0.4",
            ScriptCategory.UTILITY, "Shows every client's progress in one table.", 0, true);

    /** A management script with two fields, so the whole form and its footer fit on screen. */
    static final ScriptInfo LOGIN_WATCHER = new ScriptInfo("Login Watcher", "BotWithUs", "1.0",
            ScriptCategory.UTILITY, "Logs a client back in when it drops to the lobby.", 2, false);

    /** Break Scheduler manages Woodcutters and two client scripts; see {@link FixtureManagementSettings}. */
    private static final String BREAK_SCHEDULER_CONTEXT = LiveInspectorSource.managementContext(
            List.of("Woodcutters", "Fernmoss · Divination", "Kestrel Moor · Walk to Flag"));
    private static final String WHOLE_HOST_CONTEXT = LiveInspectorSource.managementContext(
            List.of(TargetLabels.WHOLE_HOST));

    private static final List<ConfigField> LOGIN_FIELDS = List.of(
            ConfigField.boolField("relog", "Log back in", true),
            ConfigField.intField("delay", "Wait before logging in (s)", 30));

    private static final List<ConfigField> BREAK_FIELDS = List.of(
            ConfigField.intField("breakEvery", "Break every (min)", 90),
            ConfigField.intField("breakLength", "Break length (min)", 15),
            ConfigField.boolField("logOut", "Log out during breaks", true),
            ConfigField.choiceField("jitter", "Jitter", List.of("None", "Light", "Heavy"), "Light"),
            ConfigField.stringField("quietHours", "Quiet hours", "23:00-07:00"),
            ConfigField.itemIdField("bankItem", "Keep in bank", 995));

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
    private final SubscriptionGroup subscriptions;

    private FixtureBoard(List<ClientView> clients, BoardStatus status) {
        this(clients, status, new SubscriptionGroup.Hidden());
    }

    private FixtureBoard(List<ClientView> clients, BoardStatus status, SubscriptionGroup subscriptions) {
        this.clients = clients;
        this.status = status;
        this.subscriptions = subscriptions;
    }

    /** The same fleet with a different "Your subscriptions" group. */
    FixtureBoard withSubscriptions(SubscriptionGroup group) {
        return new FixtureBoard(clients, status, group);
    }

    /**
     * The every-state fleet and four subscriptions: paid and free, one installed (which also
     * has a local copy, so the local Divination row folds into it), one installing
     * and one whose install failed.
     */
    static FixtureBoard subscribed() {
        return subscribedWithDivination(new SubscriptionState.Installed(), OptionalInt.of(DIVINATION_KEY));
    }

    /**
     * {@link #subscribed()}, but Divination is a Store copy a build behind the Store.
     * A Store copy has no local row to fold in: a local copy is never badged.
     */
    static FixtureBoard subscribedWithUpdate() {
        return subscribedWithDivination(new SubscriptionState.UpdateAvailable(DIVINATION_INSTALLED_BUILD,
                DIVINATION_STORE_BUILD), OptionalInt.empty());
    }

    private static FixtureBoard subscribedWithDivination(SubscriptionState divination, OptionalInt localKey) {
        String failure = "The launcher did not deliver the script. Check it is still running, then try again.";
        return everyState().withSubscriptions(new SubscriptionGroup.Listed(List.of(
                subscription("41", "Arch-Glacor Helper", "Veyra", "1.3",
                        "Handles the mechanics and loots the chest.", true, new SubscriptionState.NotInstalled(),
                        OptionalInt.empty()),
                subscription("7", "Divination", "BotWithUs", "1.0",
                        "Harvests wisps and converts memories.", false, divination, localKey),
                subscription("58", "Herblore Pro", "mortar", "2.1",
                        "Cleans herbs and mixes potions at any bank.", true, new SubscriptionState.Installing(),
                        OptionalInt.empty()),
                subscription("63", "Runecrafting Abyss", "Quill", "0.9",
                        "Crafts runes through the Abyss with pouch repair.", false,
                        new SubscriptionState.Failed(failure), OptionalInt.empty())),
                false));
    }

    private static SubscriptionEntry subscription(String id, String name, String author, String version,
                                                  String summary, boolean paid, SubscriptionState state,
                                                  OptionalInt localKey) {
        return new SubscriptionEntry(id, name, author, version, summary,
                paid ? PriceBadge.PAID : PriceBadge.FREE, state, localKey);
    }

    /** Seven clients, one in each client state and each script state of the prototype. */
    static FixtureBoard everyState() {
        return new FixtureBoard(FixtureFleet.everyState(), status(FixtureFleet.OAKHEART_PIPE));
    }

    /** The prototype's thirteen clients: enough for the filter box, two scripts on one card, a cut-off script. */
    static FixtureBoard thirteenClients() {
        return new FixtureBoard(FixtureFleet.thirteen(), status(FixtureFleet.OAKHEART_PIPE));
    }

    /** A development client with no account UUID to resume by. */
    static FixtureBoard devClient() {
        return new FixtureBoard(FixtureFleet.devClient(), status(FixtureFleet.OAKHEART_PIPE));
    }

    /** One still of a game client restarting and coming back on a new pipe. */
    static FixtureBoard restart(FixtureFleet.RestartStep step) {
        return new FixtureBoard(FixtureFleet.restart(step), status(FixtureFleet.OAKHEART_PIPE));
    }

    static FixtureBoard waiting() {
        return new FixtureBoard(List.of(), status(null));
    }

    private static BoardStatus status(String active) {
        return new BoardStatus(false, 0, active, true, "\\\\.\\pipe\\BotWithUs_*", null, false);
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
    public SubscriptionGroup subscriptions(ClientKey client) {
        return subscriptions;
    }

    @Override
    public Optional<InspectorTarget> resolve(InspectorSubject subject) {
        return switch (subject) {
            case ClientScript s -> clients.stream()
                    .filter(c -> c.pipe().filter(s.clientId()::equals).isPresent())
                    .findFirst()
                    .flatMap(c -> c.script(s.scriptName()).map(row -> clientTarget(s, c, row.script())));
            case ManagementScript s -> managementTarget(s);
        };
    }

    private InspectorTarget clientTarget(ClientScript subject, ClientView c, ScriptInfo script) {
        boolean woodcutting = script == WOODCUTTING;
        return new InspectorTarget(subject, "on " + c.account().orElse(subject.clientId()) + " · " + subject.clientId(),
                script,
                woodcutting ? WOODCUTTING_FIELDS : List.of(),
                () -> applied, cfg -> { },
                woodcutting ? FixtureBoard::sampleScriptUi : null,
                id -> Optional.ofNullable(ITEMS.get(id)),
                () -> false);
    }

    private Optional<InspectorTarget> managementTarget(ManagementScript subject) {
        if (subject.scriptName().equals(BREAK_SCHEDULER.name())) {
            FixtureManagementSettings targets = new FixtureManagementSettings(BREAK_FIELDS);
            Optional<Target> picked = targets.picked(subject.settingsFor());
            ScriptConfig current = targets.current(picked);
            return Optional.of(new InspectorTarget(subject.withSettingsFor(picked),
                    BREAK_SCHEDULER_CONTEXT, BREAK_SCHEDULER, BREAK_FIELDS, () -> current, cfg -> { },
                    null, id -> Optional.ofNullable(ITEMS.get(id)), () -> false, targets.picker(picked)));
        }
        if (subject.scriptName().equals(LOGIN_WATCHER.name())) {
            return Optional.of(new InspectorTarget(subject, WHOLE_HOST_CONTEXT, LOGIN_WATCHER,
                    LOGIN_FIELDS, () -> applied, cfg -> { }, null, id -> Optional.empty(), () -> false));
        }
        if (subject.scriptName().equals(FLEET_MONITOR.name())) {
            return Optional.of(new InspectorTarget(subject, WHOLE_HOST_CONTEXT, FLEET_MONITOR,
                    List.of(), () -> applied, cfg -> { }, FixtureBoard::sampleFleetUi, id -> Optional.empty(),
                    () -> false));
        }
        return Optional.empty();
    }

    /** Stands in for a management script's own ImGui. */
    private static void sampleFleetUi() {
        ImGui.text("Clients:  7 connected, 5 running");
        ImGui.text("Oakheart     Woodcutting   84 lvl");
        ImGui.text("Fernmoss     Divination    71 lvl");
        ImGui.text("Kestrel Moor Walk to Flag  --");
        ImGui.button("Pause all");
        ImGui.sameLine();
        ImGui.button("Resume all");
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
        @Override public void startScript(ClientKey client, ScriptEntry script) { }
        @Override public void startSubscription(ClientKey client, String scriptId) { }
        @Override public void stopScript(ClientKey client, String scriptName) { }
        @Override public void runScript(ClientKey client, String scriptName) { }
        @Override public void reconnect(ClientKey client) { }
        @Override public void retryNow(ClientKey client) { }
        @Override public void stopRetrying(ClientKey client) { }
        @Override public void forget(ClientKey client) { }
        @Override public void viewLog(ClientKey client) { }
        @Override public void setResumeAfterRestart(ClientKey client, boolean isOn) { }
    }
}
