package com.botwithus.bot.cli.gui.pages.installed;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.core.runtime.JarLoadOutcome;
import com.botwithus.bot.core.runtime.ScriptLoadResult;
import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.core.sdn.InstalledSdnScript;
import com.botwithus.bot.core.sdn.SdnCatalogueEntry;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Builds the page's rows from the host as it is now: one row per script the
 * latest load pass found in the scripts folder, one per script a client runs
 * that came from somewhere else (a Store delivery), and one per Store install
 * the ledger remembers that nothing has loaded since the host restarted.
 *
 * <p>Every client registers every script it was handed, so a client appears in
 * a row only once its runner has been used; see {@link RunnerFacts#hasBeenUsed()}.</p>
 */
final class LiveRows {

    /** The key prefix of a Store script that is not loaded; loaded rows are keyed by script name. */
    static final String NOT_LOADED_PREFIX = "store:";
    private static final Comparator<JarLoadOutcome> NEWEST_FIRST = Comparator.comparing(
            (JarLoadOutcome o) -> o.lastModified().orElse(Instant.MIN), Comparator.reverseOrder());

    /**
     * What one frame's rows are built from.
     *
     * @param latestPass  every JAR of the latest load pass of the scripts folder
     * @param connections every connection, in the order the host lists them
     * @param ledger      the Store installs this host recorded, keyed by script class
     * @param catalogue   the Store catalogue's entries by id; empty when it has not answered
     * @param managedBy   the management scripts that manage a script on a connection, by name
     */
    record Inputs(List<ScriptLoadResult> latestPass, List<Connection> connections,
                  Map<String, InstalledSdnScript> ledger, Map<String, SdnCatalogueEntry> catalogue,
                  Instant now, ZoneId zone, BiFunction<Connection, String, List<String>> managedBy) {}

    private final Inputs in;

    private LiveRows(Inputs in) {
        this.in = in;
    }

    /** The rows, ordered by name. */
    static List<InstalledScript> build(Inputs in) {
        return new LiveRows(in).rows();
    }

    private List<InstalledScript> rows() {
        Map<String, ScriptLoadResult> fromJars = newestJarPerName();
        Map<String, List<Runner>> byName = runnersByName();
        Set<String> loadedClasses = new HashSet<>();
        List<InstalledScript> rows = new ArrayList<>();
        fromJars.forEach((name, jar) -> {
            BotScript script = jar.script().orElseThrow();
            loadedClasses.add(script.getClass().getName());
            rows.add(row(name, script, Optional.of(jar), byName.getOrDefault(name, List.of())));
        });
        byName.forEach((name, runners) -> {
            runners.forEach(r -> loadedClasses.add(r.runner().getScript().getClass().getName()));
            if (!fromJars.containsKey(name)) {
                rows.add(row(name, runners.getFirst().runner().getScript(), Optional.empty(), runners));
            }
        });
        rows.addAll(notLoadedStoreRows(loadedClasses));
        rows.sort(Comparator.comparing(InstalledScript::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(rows);
    }

    /** The JAR a runtime takes each name from: the newest one declaring it. */
    private Map<String, ScriptLoadResult> newestJarPerName() {
        Map<String, ScriptLoadResult> out = new LinkedHashMap<>();
        in.latestPass().stream()
                .filter(ScriptLoadResult::isSuccess)
                .sorted(NEWEST_FIRST)
                .forEach(r -> out.putIfAbsent(r.scriptName().orElseThrow(), r));
        return out;
    }

    /** One connection's runner of one script. */
    private record Runner(Connection conn, ScriptRunner runner) {}

    private Map<String, List<Runner>> runnersByName() {
        Map<String, List<Runner>> out = new LinkedHashMap<>();
        for (Connection conn : in.connections()) {
            List<ScriptRunner> all = new ArrayList<>(conn.getRuntime().getRunners());
            all.addAll(conn.getRuntime().getQuarantined());
            for (ScriptRunner runner : all) {
                out.computeIfAbsent(runner.getScriptName(), n -> new ArrayList<>()).add(new Runner(conn, runner));
            }
        }
        return out;
    }

    private InstalledScript row(String name, BotScript script, Optional<ScriptLoadResult> jar,
                                List<Runner> runners) {
        ScriptIdentity identity = identityOf(name, script);
        Provenance provenance = jar.isPresent()
                ? localJar(jar.get())
                : fromRuntime(script.getClass().getName(), identity.version());
        List<ClientRun> runs = new ArrayList<>();
        Set<String> registeredOn = new LinkedHashSet<>();
        Set<String> managedBy = new LinkedHashSet<>();
        for (Runner r : runners) {
            registeredOn.add(r.conn().getName());
            clientRun(r, identity.hasSettings()).ifPresent(runs::add);
            managedBy.addAll(in.managedBy().apply(r.conn(), name));
        }
        return new InstalledScript(name, identity, provenance, runs, registeredOn, List.copyOf(managedBy));
    }

    private Optional<ClientRun> clientRun(Runner r, boolean hasSettings) {
        RunnerFacts facts = RunnerDetails.factsOf(r.conn(), r.runner());
        if (!facts.hasBeenUsed()) {
            return Optional.empty();
        }
        RunnerState state = RunnerState.of(facts);
        String detail = RunnerDetails.detail(state, facts, r.runner(), r.conn(), in.now());
        return Optional.of(new ClientRun(r.conn().getName(), RunnerDetails.clientName(r.conn()), state, detail,
                hasSettings));
    }

    private Provenance localJar(ScriptLoadResult jar) {
        Optional<String> changed = jar.lastModified().map(t -> WhenText.changed(t, in.now(), in.zone()));
        return new Provenance(ScriptSource.LOCAL, true, Optional.of(jar.jar()), changed, Optional.empty());
    }

    /** A script a client runs that no JAR in the folder declares: a Store delivery when the ledger says so. */
    private Provenance fromRuntime(String scriptClass, String version) {
        Optional<InstalledSdnScript> installed = Optional.ofNullable(in.ledger().get(scriptClass));
        ScriptSource source = installed.isPresent() ? ScriptSource.STORE : ScriptSource.LOCAL;
        Optional<UpdateBadge> update = UpdateBadge.of(installed, entryFor(installed), version);
        return new Provenance(source, true, Optional.<Path>empty(), Optional.empty(), update);
    }

    private Optional<SdnCatalogueEntry> entryFor(Optional<InstalledSdnScript> installed) {
        return installed.map(i -> in.catalogue().get(i.catalogueId()));
    }

    /** Store installs the ledger remembers whose class nothing has loaded: the host restarted since. */
    private List<InstalledScript> notLoadedStoreRows(Set<String> loadedClasses) {
        List<InstalledScript> rows = new ArrayList<>();
        in.ledger().forEach((scriptClass, installed) -> {
            if (!loadedClasses.contains(scriptClass)) {
                rows.add(notLoaded(scriptClass, installed));
            }
        });
        return rows;
    }

    /** What the catalogue says about a Store install nothing has loaded; its class name when it says nothing. */
    private InstalledScript notLoaded(String scriptClass, InstalledSdnScript installed) {
        Optional<SdnCatalogueEntry> entry = Optional.ofNullable(in.catalogue().get(installed.catalogueId()));
        String simpleName = scriptClass.substring(scriptClass.lastIndexOf('.') + 1);
        ScriptIdentity identity = entry
                .map(e -> new ScriptIdentity(e.name(), orEmpty(e.version()), e.summary(), orEmpty(e.author()),
                        e.scriptCategory(), 0, false))
                .orElse(new ScriptIdentity(simpleName, "", "", "", ScriptCategory.UNCATEGORIZED, 0, false));
        Provenance provenance = new Provenance(ScriptSource.STORE, false, Optional.empty(), Optional.empty(),
                UpdateBadge.of(Optional.of(installed), entry, identity.version()));
        return new InstalledScript(NOT_LOADED_PREFIX + scriptClass, identity, provenance, List.of(), Set.of(),
                List.of());
    }

    private static String orEmpty(String text) {
        return text != null ? text : "";
    }

    private static ScriptIdentity identityOf(String name, BotScript script) {
        ScriptManifest m = script.getClass().getAnnotation(ScriptManifest.class);
        if (m == null) {
            return new ScriptIdentity(name, "", "", "", ScriptCategory.UNCATEGORIZED,
                    RunnerDetails.settingsCount(script), RunnerDetails.hasUi(script));
        }
        return new ScriptIdentity(name, m.version(), m.description(), m.author(), m.category(),
                RunnerDetails.settingsCount(script), RunnerDetails.hasUi(script));
    }
}
