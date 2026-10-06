package com.botwithus.bot.cli.gui.preview;

import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.gui.pages.installed.ClientChoice;
import com.botwithus.bot.cli.gui.pages.installed.ClientRun;
import com.botwithus.bot.cli.gui.pages.installed.InstalledHeader;
import com.botwithus.bot.cli.gui.pages.installed.InstalledModel;
import com.botwithus.bot.cli.gui.pages.installed.InstalledScript;
import com.botwithus.bot.cli.gui.pages.installed.InstalledView;
import com.botwithus.bot.cli.gui.pages.installed.LoadProblem;
import com.botwithus.bot.cli.gui.pages.installed.Provenance;
import com.botwithus.bot.cli.gui.pages.installed.RunnerState;
import com.botwithus.bot.cli.gui.pages.installed.ScriptIdentity;
import com.botwithus.bot.cli.gui.pages.installed.ScriptSource;
import com.botwithus.bot.cli.gui.pages.installed.UpdateBadge;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * DEV ONLY. The Installed scripts page's fixtures: the prototype's seven
 * scripts across eleven sample clients and one JAR that failed to load, an
 * empty folder, and a failures-heavy variant. Script names, versions and the
 * two example JAR names are real; clients, states and dates are made up.
 * Actions do nothing: the preview captures states, it does not play them.
 */
final class FixtureInstalledModel implements InstalledModel {

    private static final String FOLDER = "scripts/";
    private static final String BREAK_SCHEDULER = "Break Scheduler";
    private static final String OFFLINE_ACCOUNT = "Hollowmere";
    private static final String CLOSED_ACCOUNT = "Brackenridge";
    private static final String LATE_ACCOUNT = "Sableton";
    private static final String DEV_PIPE = "BotWithUs_9001";
    private static final List<String> ACCOUNTS = List.of("Oakheart", OFFLINE_ACCOUNT, "Wrenfield", "Duskwater",
            "Fernmoss", CLOSED_ACCOUNT, "Tamsin Vale", "Ashgrove", "Quillon", "Kestrel Moor", LATE_ACCOUNT);
    private static final String NPE_IN_LOOP = "Crashed · NullPointerException in onLoop()";

    static final String WOODCUTTING = "Woodcutting";
    static final String DIVINATION = "Divination";
    static final String EXAMPLE = "Example Script";
    static final Path FAILED_JAR = Path.of(FOLDER, "woodcutting-1.0-SNAPSHOT.jar");

    private InstalledView view = busyView();

    @Override
    public InstalledView view() {
        return view;
    }

    /** The folder holds nothing at all. */
    void showEmpty() {
        view = new InstalledView(header(), List.of(), List.of(), clients());
    }

    /** Adds a Store script not loaded since a restart and a shadowed duplicate to the busy set. */
    void showFailures() {
        List<InstalledScript> rows = new ArrayList<>(busyRows());
        rows.add(herbloreNotLoaded());
        List<LoadProblem> problems = List.of(failedJar(), new LoadProblem(LoadProblem.Kind.OLDER_DUPLICATE,
                Path.of(FOLDER, "divination-0.9.jar"),
                "Older copy of Divination: divination-1.0.jar is the one that runs", "",
                Optional.of("Delete it from scripts/ to clear this.")));
        view = new InstalledView(header(), rows, problems, clients());
    }

    private static InstalledView busyView() {
        return new InstalledView(header(), busyRows(), List.of(failedJar()), clients());
    }

    private static InstalledHeader header() {
        return new InstalledHeader(FOLDER, Optional.of("14:03:52"), false, true, false);
    }

    /**
     * The sample accounts, each on its own made-up account key, then a closed
     * development client the host only ever knew by its pipe.
     */
    private static List<ClientChoice> clients() {
        List<ClientChoice> clients = new ArrayList<>(ACCOUNTS.stream()
                .map(a -> new ClientChoice(idOf(a), a, !a.equals(OFFLINE_ACCOUNT) && !a.equals(CLOSED_ACCOUNT),
                        a.equals(OFFLINE_ACCOUNT) ? "reconnecting" : "client closed",
                        ClientKey.account("fixture-account-" + ACCOUNTS.indexOf(a))))
                .toList());
        clients.add(new ClientChoice(DEV_PIPE, DEV_PIPE, false, "client closed", ClientKey.pipe(DEV_PIPE)));
        return clients;
    }

    private static String idOf(String account) {
        return "BotWithUs_" + (ACCOUNTS.indexOf(account) + 1) * 1117;
    }

    private static List<InstalledScript> busyRows() {
        return List.of(woodcutting(), divination(), cooks(), ghost(), walkToFlag(), probe(), example());
    }

    private static InstalledScript woodcutting() {
        ScriptIdentity id = new ScriptIdentity(WOODCUTTING, "2.0",
                "Chops a configurable tree at a configurable spot; banks, drops, or wood-boxes the logs.",
                "BotWithUs", ScriptCategory.WOODCUTTING, 6, true);
        UpdateBadge update = new UpdateBadge("v2.1 in Store", "v2.1 is in the Store",
                "You have v2.0 (build 5). Update it from the Script Store.");
        return row(id, store(Optional.of(update)), List.of(BREAK_SCHEDULER),
                run("Oakheart", RunnerState.RUNNING, "Running · 41:07 · 142 ms", true),
                run(OFFLINE_ACCOUNT, RunnerState.OFFLINE, "Waiting · reconnecting", true),
                run("Wrenfield", RunnerState.RUNNING, "Running · 2:02:44 · 118 ms", true),
                run("Duskwater", RunnerState.STOPPED, "Stopped", true));
    }

    private static InstalledScript divination() {
        ScriptIdentity id = new ScriptIdentity(DIVINATION, "1.0",
                "Harvests wisps and converts memories at the nearest divination spot.", "BotWithUs",
                ScriptCategory.DIVINATION, 0, false);
        return row(id, store(Optional.empty()), List.of(BREAK_SCHEDULER),
                run("Fernmoss", RunnerState.STALLED, "Stalled · inside onLoop()", false),
                run(CLOSED_ACCOUNT, RunnerState.OFFLINE, "Waiting · client closed", false));
    }

    private static InstalledScript cooks() {
        ScriptIdentity id = new ScriptIdentity("Cook's Assistant", "1.0",
                "Solves Cook's Assistant (quest 257) end-to-end.", "BotWithUs", ScriptCategory.QUESTING, 0, false);
        return row(id, store(Optional.empty()), List.of(),
                run("Tamsin Vale", RunnerState.CRASHED, NPE_IN_LOOP, false));
    }

    private static InstalledScript ghost() {
        ScriptIdentity id = new ScriptIdentity("The Restless Ghost", "0.1",
                "Solves The Restless Ghost (quest 27) end-to-end.", "", ScriptCategory.QUESTING, 0, false);
        return row(id, local("quests-dev.jar", "today 13:58"), List.of(),
                run("Ashgrove", RunnerState.RUNNING, "Running · 12:40 · 211 ms", false));
    }

    private static InstalledScript walkToFlag() {
        ScriptIdentity id = new ScriptIdentity("Walk to Flag", "1.0",
                "Walks to the world-map flag (varp 2807) using world pathfinding.", "BotWithUs",
                ScriptCategory.UTILITY, 0, false);
        return row(id, store(Optional.empty()), List.of(BREAK_SCHEDULER),
                run("Kestrel Moor", RunnerState.RUNNING, "Running · 0:48 · 74 ms", false));
    }

    private static InstalledScript probe() {
        ScriptIdentity id = new ScriptIdentity("Location Probe", "1.0",
                "Logs snapshot.locations() count and sample rows each tick.", "", ScriptCategory.UTILITY, 0, false);
        return row(id, local("utility-dev.jar", "today 13:58"), List.of(),
                run("Kestrel Moor", RunnerState.STOPPED, "Stopped", false));
    }

    private static InstalledScript example() {
        ScriptIdentity id = new ScriptIdentity(EXAMPLE, "1.0",
                "A demo script showing the entity query API and Live Config.", "BotWithUs",
                ScriptCategory.UTILITY, 3, false);
        return row(id, local("example-script-1.0-SNAPSHOT.jar", "today 14:03"), List.of(),
                run("Quillon", RunnerState.STOPPED, "Stopped", true));
    }

    private static InstalledScript herbloreNotLoaded() {
        ScriptIdentity id = new ScriptIdentity("Herblore", "3.0", "Cleans herbs and mixes potions at a bank.",
                "BotWithUs", ScriptCategory.HERBLORE, 0, false);
        Provenance p = new Provenance(ScriptSource.STORE, false, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of("herblore"));
        return new InstalledScript("store:com.example.herblore.Herblore", id, p, List.of(), Set.of(), List.of());
    }

    private static LoadProblem failedJar() {
        String trace = String.join("\n",
                "java.util.ServiceConfigurationError: com.botwithus.bot.api.BotScript: "
                        + "Provider com.botwithus.scripts.woodcutting.Woodcutting not found",
                "\tat java.base/java.util.ServiceLoader.fail(ServiceLoader.java:593)",
                "\tat java.base/java.util.ServiceLoader$ModuleServicesLookupIterator.hasNext(ServiceLoader.java:1087)",
                "\tat com.botwithus.bot.core.runtime.LocalScriptLoader.load(LocalScriptLoader.java)");
        return new LoadProblem(LoadProblem.Kind.FAILED, FAILED_JAR,
                "ServiceConfigurationError: BotScript provider not found", trace,
                Optional.of("An older build of Woodcutting sits next to woodcutting-2.0.jar. "
                        + "Delete it from scripts/ or fix its module-info provides line."));
    }

    private static Provenance store(Optional<UpdateBadge> update) {
        return new Provenance(ScriptSource.STORE, true, Optional.empty(), Optional.empty(), update,
                Optional.of("store-script"));
    }

    private static Provenance local(String jar, String changed) {
        return new Provenance(ScriptSource.LOCAL, true, Optional.of(Path.of(FOLDER, jar)), Optional.of(changed),
                Optional.empty(), Optional.empty());
    }

    private static ClientRun run(String account, RunnerState state, String detail, boolean hasSettings) {
        return new ClientRun(idOf(account), account, state, detail, hasSettings);
    }

    /**
     * Every client registers every script, except that a Store delivery reaches
     * only the clients connected when it was installed: Sableton connected later.
     */
    private static InstalledScript row(ScriptIdentity id, Provenance p, List<String> managedBy, ClientRun... runs) {
        Set<String> registered = new LinkedHashSet<>();
        ACCOUNTS.stream()
                .filter(a -> p.source() == ScriptSource.LOCAL || !a.equals(LATE_ACCOUNT))
                .forEach(a -> registered.add(idOf(a)));
        return new InstalledScript(id.name(), id, p, List.of(runs), registered, managedBy);
    }

    @Override
    public void setWatching(boolean isWatching) {
    }

    @Override
    public void setRestartAfterReload(boolean isRestartAfterReload) {
    }

    @Override
    public void reload() {
    }

    @Override
    public void openFolder() {
    }

    @Override
    public void startOn(String key, List<String> clientIds) {
    }

    @Override
    public void stopEverywhere(String key) {
    }

    @Override
    public void stop(String key, String clientId) {
    }

    @Override
    public void run(String key, String clientId) {
    }

    @Override
    public void openSettings(String key, String clientId) {
    }

    @Override
    public void reportProblem(String key, String clientId) {
    }
}
