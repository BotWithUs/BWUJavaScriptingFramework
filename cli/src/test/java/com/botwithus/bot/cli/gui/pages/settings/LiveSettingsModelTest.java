package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.SettingKeys;
import com.botwithus.bot.core.config.ScriptProfileStore;
import com.botwithus.bot.core.impl.ScriptManagerImpl;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.core.runtime.ScriptRuntime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** The live model over a real host context, settings file and profile store in a temp folder. */
class LiveSettingsModelTest {

    private static final String UUID = "3f9a1c2e-7d41-4b8e-9a51-0c2f6b1d8e30";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-26T14:05:00Z"), ZoneOffset.UTC);
    private static final int WINDOWS_PERCENT = 125;
    private static final long CALL_NANOS = 1_000_000L;
    private static final long LOOP_NANOS = 5_000_000L;

    @ScriptManifest(name = "Woodcutting", version = "1.0", author = "test")
    private static final class Idle implements BotScript {
        @Override public void onStart(ScriptContext ctx) { }
        @Override public int onLoop() { return -1; }
        @Override public void onStop() { }
    }

    @TempDir
    Path home;

    private Path data;
    private HostSettings settings;
    private ScriptProfileStore profiles;
    private final List<Path> opened = new ArrayList<>();
    private final List<RpcClient> clients = new ArrayList<>();
    private LiveSettingsModel model;

    @BeforeEach
    void setUp() throws IOException {
        data = Files.createDirectories(home.resolve(".botwithus"));
        settings = HostSettings.open(data);
        profiles = new ScriptProfileStore(data);
        LiveSettingsModel.Host host = new LiveSettingsModel.Host(settings, Optional.of(profiles), List::of);
        LiveSettingsModel.Places places = new LiveSettingsModel.Places(data, home.resolve("scripts"),
                Files.createDirectories(home.resolve("Downloads")), home, home.resolve("elsewhere"));
        model = new LiveSettingsModel(host, places, opened::add, Runnable::run, CLOCK, () -> WINDOWS_PERCENT);
    }

    @AfterEach
    void tearDown() {
        clients.forEach(RpcClient::close);
        settings.close();
    }

    private List<AccountRow> accounts() {
        return model.view().sections().stream().flatMap(s -> s.items().stream())
                .flatMap(item -> switch (item) {
                    case SettingsItem.Accounts a -> a.rows().stream();
                    default -> Stream.<AccountRow>empty();
                }).toList();
    }

    @Test
    void forgettingAnAccountsScriptsKeepsTheAccountListed() {
        profiles.setDisplayName(UUID, "Oakheart");
        profiles.setAccountScripts(UUID, List.of("Woodcutting"));
        assertEquals(List.of(new AccountRow(UUID, "Oakheart", List.of("Woodcutting"), true)), accounts());

        model.forgetScripts(UUID);
        model.setAutoStart(UUID, false);

        assertEquals(List.of(new AccountRow(UUID, "Oakheart", List.of(), false)), accounts());
        assertEquals(List.of(), profiles.getAccountScripts(UUID));
    }

    @Test
    void resetMetricsClearsRpcAndLoopTimingOnEveryConnection() {
        RpcClient rpc = new RpcClient(mock(PipeClient.class));
        clients.add(rpc);
        ScriptRuntime runtime = new ScriptRuntime(mock(ScriptContext.class));
        ScriptRunner runner = runtime.registerScript(new Idle());
        Connection conn = new Connection("BotWithUs_1", mock(PipeClient.class), rpc, runtime,
                new ScriptManagerImpl(runtime));
        rpc.getMetrics().recordCall("ping", CALL_NANOS, false);
        runner.getProfiler().recordLoop(LOOP_NANOS);

        ActionNote note = LiveSettingsModel.resetMetrics(List.of(conn));

        assertAll(
                () -> assertTrue(rpc.getMetrics().snapshot().isEmpty()),
                () -> assertEquals(0L, runner.getProfiler().getLoopCount()),
                () -> assertEquals(ActionNote.done("Metrics reset on 1 client."), note));
    }

    @Test
    void exportWritesAStampedZipToTheExportFolderAndSaysWhere() throws IOException {
        Files.writeString(data.resolve("groups.json"), "[]");

        model.run(SettingsAction.EXPORT_SETTINGS);

        Path zip = home.resolve("Downloads").resolve("botwithus-settings-20260926-140500.zip");
        assertTrue(Files.isRegularFile(zip));
        assertEquals(Optional.of(ActionNote.done(
                "Saved 1 file to ~/Downloads/botwithus-settings-20260926-140500.zip.")), model.view().note());
    }

    @Test
    void openHandsTheRightPathToTheOpenerAndTheHeaderNamesTheFile() {
        settings.set(SettingKeys.AUTO_CONNECT, false);
        settings.flush();
        model.open(SettingsPlace.CONFIG_FILE);
        model.open(SettingsPlace.DATA_FOLDER);

        assertEquals(List.of(data.resolve(HostSettings.FILE_NAME), data), opened);
        assertEquals("~/.botwithus/config.properties", model.view().configFile());
    }

    @Test
    void openingASettingsFileThatWasNeverWrittenSaysSoInsteadOfFailing() {
        model.open(SettingsPlace.CONFIG_FILE);

        assertEquals(List.of(), opened);
        ActionNote note = model.view().note().orElseThrow();
        assertFalse(note.isError());
        assertTrue(note.text().contains("has not been written yet"), note.text());
    }

    @Test
    void aFailedOpenIsReportedAsAnErrorNote() {
        LiveSettingsModel.Host bare = new LiveSettingsModel.Host(settings, Optional.empty(), List::of);
        LiveSettingsModel failing = new LiveSettingsModel(bare, new LiveSettingsModel.Places(
                data, home, home, home, home), path -> {
                    throw new IOException("no application is associated");
                }, Runnable::run, CLOCK, () -> WINDOWS_PERCENT);

        failing.open(SettingsPlace.SCRIPTS_FOLDER);

        ActionNote note = failing.view().note().orElseThrow();
        assertTrue(note.isError());
        assertFalse(note.text().isBlank());
    }
}
