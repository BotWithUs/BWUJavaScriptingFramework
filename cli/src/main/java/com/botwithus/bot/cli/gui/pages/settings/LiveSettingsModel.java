package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.alerts.Integrations;
import com.botwithus.bot.cli.alerts.SecretChange;
import com.botwithus.bot.cli.gui.nav.SecondLine;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.core.alerts.AlertService;
import com.botwithus.bot.core.config.ScriptProfileStore;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.core.runtime.ScriptRuntime;
import com.botwithus.bot.core.secrets.Secret;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The Settings page over the running host: its {@link HostSettings}, the saved
 * account profiles and the open connections. Reading is cheap enough for every
 * frame; the account list is read from disk at most every
 * {@link #ACCOUNTS_REFRESH} (and at once after a change made here), and opening
 * files and writing the export run on {@code background}.
 */
public final class LiveSettingsModel implements SettingsModel {

    /** Opens a file or folder the way double-clicking it would. */
    @FunctionalInterface
    public interface Opener {
        void open(Path path) throws IOException;
    }

    /**
     * Where things are on this PC.
     *
     * @param dataFolder    {@code ~/.botwithus}
     * @param scriptsFolder the folder scripts load from
     * @param exportFolder  where an export zip is written
     * @param home          the user's home, for showing paths short
     * @param workingDir    where the host runs from, for showing paths short
     */
    public record Places(Path dataFolder, Path scriptsFolder, Path exportFolder, Path home, Path workingDir) {}

    /**
     * What the page reads from the host.
     *
     * @param settings     the host's settings
     * @param profiles     the saved account profiles, when the host has a store for them
     * @param connections  the open connections, read when metrics are reset
     * @param integrations the alert services, once the host has started its alerts
     */
    public record Host(HostSettings settings, Optional<ScriptProfileStore> profiles,
                       Supplier<List<Connection>> connections, Supplier<Optional<Integrations>> integrations) {

        /** A host without alerts: the Integrations section says they are not running. */
        public Host(HostSettings settings, Optional<ScriptProfileStore> profiles,
                    Supplier<List<Connection>> connections) {
            this(settings, profiles, connections, Optional::empty);
        }
    }

    static final Duration ACCOUNTS_REFRESH = Duration.ofSeconds(2);
    static final String ALERTS_NOT_RUNNING = "Alerts are not running, so nothing was saved";

    private static final Logger log = LoggerFactory.getLogger(LiveSettingsModel.class);
    private static final DateTimeFormatter EXPORT_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String EXPORT_PREFIX = "botwithus-settings-";
    private static final String EXPORT_SUFFIX = ".zip";
    private static final String DOWNLOADS = "Downloads";

    private final Host host;
    private final HostSettings settings;
    private final SettingEditor editor;
    private final Places places;
    private final Opener opener;
    private final Executor background;
    private final Clock clock;
    private final IntSupplier windowsPercent;
    // Written by background work, read by the render thread.
    private volatile Optional<ActionNote> note = Optional.empty();
    // Render thread only.
    private List<AccountRow> accounts = List.of();
    private Instant accountsReadAt = Instant.MIN;

    public LiveSettingsModel(Host host, Places places, Opener opener, Executor background, Clock clock,
                             IntSupplier windowsPercent) {
        this.host = host;
        this.settings = host.settings();
        this.editor = new SettingEditor(settings);
        this.places = places;
        this.opener = opener;
        this.background = background;
        this.clock = clock;
        this.windowsPercent = windowsPercent;
    }

    @Override
    public SettingsView view() {
        SettingsSheet.Inputs inputs = new SettingsSheet.Inputs(accounts(), folder(places.dataFolder()),
                folder(places.scriptsFolder()), file(settings.file()), windowsPercent.getAsInt(), note,
                host.integrations().get());
        return SettingsSheet.build(settings, settings.status(), inputs);
    }

    @Override
    public SaveLine save() {
        return SaveLine.of(settings.status());
    }

    @Override
    public EditResult edit(String name, String shownText) {
        return editor.editRow(name, shownText);
    }

    @Override
    public EditResult editRaw(String name, String text) {
        return editor.editRaw(name, text);
    }

    @Override
    public void setAutoStart(String accountUuid, boolean isOn) {
        profiles().ifPresent(store -> store.setAutoStart(accountUuid, isOn));
        accountsReadAt = Instant.MIN;
    }

    @Override
    public void forgetScripts(String accountUuid) {
        profiles().ifPresent(store -> store.setAccountScripts(accountUuid, List.of()));
        accountsReadAt = Instant.MIN;
    }

    @Override
    public void open(SettingsPlace place) {
        switch (place) {
            case DATA_FOLDER -> background.execute(() -> openFolder(places.dataFolder()));
            case SCRIPTS_FOLDER -> background.execute(() -> openFolder(places.scriptsFolder()));
            case CONFIG_FILE -> openConfigFile();
        }
    }

    /** {@code ~/Downloads} when there is one, else the home folder. */
    public static Path exportFolderIn(Path home) {
        Path downloads = home.resolve(DOWNLOADS);
        return Files.isDirectory(downloads) ? downloads : home;
    }

    /** Opens {@code path} with Windows: a folder in Explorer, a file in its default program. */
    public static void openOnDesktop(Path path) throws IOException {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            throw new IOException("this system cannot open files from the app");
        }
        Desktop.getDesktop().open(path.toFile());
    }

    @Override
    public void run(SettingsAction action) {
        switch (action) {
            case RESET_METRICS -> note = Optional.of(resetMetrics(host.connections().get()));
            case EXPORT_SETTINGS -> background.execute(this::exportNow);
        }
    }

    @Override
    public Optional<Secret> readSecret(AlertService service) {
        return host.integrations().get().flatMap(live -> live.readSecret(service));
    }

    @Override
    public SecretChange saveSecret(AlertService service, String text) {
        return host.integrations().get().map(live -> live.saveSecret(service, text))
                .orElseGet(() -> new SecretChange.Refused(ALERTS_NOT_RUNNING));
    }

    /** The card follows the send through the back end's status; the result is not needed here. */
    @Override
    public void sendTest(AlertService service) {
        host.integrations().get().ifPresent(live -> live.sendTest(service));
    }

    /**
     * Clears RPC timing and every script's loop timing on each connection.
     * Package-private so a test can check it against real connections.
     */
    static ActionNote resetMetrics(List<Connection> connections) {
        for (Connection conn : connections) {
            RpcClient rpc = conn.getRpc();
            if (rpc != null) {
                rpc.getMetrics().reset();
            }
            ScriptRuntime runtime = conn.getRuntime();
            if (runtime != null) {
                runtime.getRunners().forEach(LiveSettingsModel::resetLoops);
            }
        }
        int n = connections.size();
        return ActionNote.done(n == 0 ? "No clients are connected, so there was nothing to reset."
                : "Metrics reset on " + n + (n == 1 ? " client." : " clients."));
    }

    private static void resetLoops(ScriptRunner runner) {
        runner.getProfiler().reset();
    }

    private void exportNow() {
        String stamp = LocalDateTime.ofInstant(clock.instant(), clock.getZone()).format(EXPORT_STAMP);
        Path zip = places.exportFolder().resolve(EXPORT_PREFIX + stamp + EXPORT_SUFFIX);
        try {
            settings.flush();
            List<Path> packed = SettingsExport.write(places.dataFolder(), zip);
            note = Optional.of(ActionNote.done("Saved " + packed.size() + (packed.size() == 1 ? " file" : " files")
                    + " to " + file(zip) + "."));
        } catch (IOException | RuntimeException e) {
            log.warn("Settings export to {} failed: {}", zip, e.toString());
            note = Optional.of(ActionNote.failed("Could not export: " + e.getMessage()));
        }
    }

    private void openConfigFile() {
        Path file = settings.file();
        if (!Files.exists(file)) {
            note = Optional.of(ActionNote.done("Every setting is at its default, so " + file(file)
                    + " has not been written yet."));
            return;
        }
        background.execute(() -> openNow(file));
    }

    private void openFolder(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            note = Optional.of(ActionNote.failed("Could not create " + folder(dir) + ": " + e.getMessage()));
            return;
        }
        openNow(dir);
    }

    private void openNow(Path path) {
        try {
            opener.open(path);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not open {}: {}", path, e.toString());
            note = Optional.of(ActionNote.failed("Could not open " + file(path) + ": " + e.getMessage()));
        }
    }

    private List<AccountRow> accounts() {
        Instant now = clock.instant();
        if (Duration.between(accountsReadAt, now).compareTo(ACCOUNTS_REFRESH) >= 0) {
            accounts = readAccounts();
            accountsReadAt = now;
        }
        return accounts;
    }

    private List<AccountRow> readAccounts() {
        Optional<ScriptProfileStore> store = profiles();
        if (store.isEmpty()) {
            return List.of();
        }
        List<AccountRow> rows = new ArrayList<>();
        for (Map.Entry<String, ScriptProfileStore.ProfileSummary> e : store.get().listAccountProfiles().entrySet()) {
            ScriptProfileStore.ProfileSummary p = e.getValue();
            rows.add(new AccountRow(e.getKey(), p.displayName(), p.scripts(), p.autoStart()));
        }
        return rows;
    }

    private Optional<ScriptProfileStore> profiles() {
        return host.profiles();
    }

    private String folder(Path dir) {
        return SecondLine.FolderPath.of(dir, places.workingDir(), places.home()).text();
    }

    private String file(Path path) {
        Path parent = path.toAbsolutePath().getParent();
        return parent == null ? path.toString() : folder(parent) + path.getFileName();
    }
}
