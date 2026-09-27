package com.botwithus.bot.cli.settings;

import com.botwithus.bot.core.rpc.ReconnectPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostSettingsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final HostSettings.Pause NO_PAUSE = () -> { };

    @TempDir
    Path dir;

    /** Closed after each test so no debounced write races the temp folder's deletion. */
    private final List<HostSettings> opened = new ArrayList<>();

    @AfterEach
    void closeAll() {
        opened.forEach(HostSettings::close);
    }

    private Path file() {
        return dir.resolve(HostSettings.FILE_NAME);
    }

    private HostSettings open(RecordingStorage storage, HostSettings.Pause pause) {
        HostSettings settings = new HostSettings(storage, SettingKeys.ALL, pause, CLOCK);
        opened.add(settings);
        return settings;
    }

    private HostSettings reopen() {
        HostSettings settings = HostSettings.open(dir);
        opened.add(settings);
        return settings;
    }

    // ── Defaults ────────────────────────────────────────────────────────

    @Test
    void get_emptyFolder_everyKeyReadsItsDefault() {
        HostSettings settings = reopen();

        for (SettingKey<?> key : settings.keys()) {
            assertEquals(key.defaultValue(), settings.get(key), key.name());
            assertFalse(settings.isExplicit(key), key.name());
        }
        assertFalse(Files.exists(file()), "opening must not create the file");
        assertInstanceOf(SaveStatus.Saved.class, settings.status());
    }

    @Test
    void defaults_keepTodaysBehaviour() {
        HostSettings settings = reopen();

        assertAll(
                () -> assertTrue(settings.get(SettingKeys.AUTO_CONNECT)),
                () -> assertEquals("BotWithUs", settings.get(SettingKeys.PIPE_PREFIX)),
                () -> assertEquals(5_000L, settings.get(SettingKeys.SCAN_INTERVAL_MS)),
                () -> assertEquals(10_000L, settings.get(SettingKeys.RPC_TIMEOUT_MS)),
                () -> assertFalse(settings.get(SettingKeys.AUTO_RELOAD)),
                () -> assertEquals(0L, settings.get(SettingKeys.RECONNECT_MAX_ATTEMPTS), "0 = never give up"),
                () -> assertEquals(ReconnectPolicy.DEFAULT.initialDelayMs(),
                        settings.get(SettingKeys.RECONNECT_INITIAL_DELAY_MS)),
                () -> assertEquals(ReconnectPolicy.DEFAULT.backoffMultiplier(),
                        settings.get(SettingKeys.RECONNECT_BACKOFF)),
                () -> assertEquals(ReconnectPolicy.DEFAULT.maxDelayMs(),
                        settings.get(SettingKeys.RECONNECT_MAX_DELAY_MS)),
                () -> assertEquals(600_000L, settings.get(SettingKeys.STALL_AFTER_MS)),
                () -> assertEquals(5L, settings.get(SettingKeys.NOTIFY_DURATION_S)),
                () -> assertEquals(StartMode.NORMAL, settings.get(SettingKeys.START_MODE)),
                () -> assertEquals(TextSize.MATCH_WINDOWS, settings.get(SettingKeys.TEXT_SIZE)));
    }

    @Test
    void catalogue_namesAreUniqueAndCoverEveryNotificationKind() {
        List<String> names = SettingKeys.ALL.stream().map(SettingKey::name).toList();
        assertEquals(names.size(), names.stream().distinct().count());
        for (NotificationKind kind : NotificationKind.values()) {
            assertTrue(names.contains("notify." + kind.id() + ".enabled"), kind.name());
        }
        assertTrue(names.containsAll(List.of("autoConnect", "autoConnectPipes", "scanIntervalMs",
                "defaultTimeout", "autoReload", "reconnect.maxAttempts", "reconnect.initialDelayMs",
                "reconnect.backoff", "reconnect.maxDelayMs", "scripts.restartAfterReload",
                "scripts.stallAfterMs", "notify.durationS", "ui.startMode", "ui.textSize",
                "ui.reduceMotion", "diag.collectRpc", "diag.collectLoops")));
    }

    // ── Round trip ──────────────────────────────────────────────────────

    @Test
    void set_thenReopen_readsEveryTypeBack() {
        HostSettings settings = reopen();
        settings.set(SettingKeys.AUTO_CONNECT, false);
        settings.set(SettingKeys.PIPE_PREFIX, "Farm_A");
        settings.set(SettingKeys.SCAN_INTERVAL_MS, 2_000L);
        settings.set(SettingKeys.RECONNECT_BACKOFF, 1.5);
        settings.set(SettingKeys.START_MODE, StartMode.ADVANCED);
        settings.set(SettingKeys.TEXT_SIZE, TextSize.PERCENT_125);
        settings.set(SettingKeys.notifyEnabled(NotificationKind.CLIENT_BACK), false);
        settings.close();

        HostSettings again = reopen();
        assertAll(
                () -> assertFalse(again.get(SettingKeys.AUTO_CONNECT)),
                () -> assertEquals("Farm_A", again.get(SettingKeys.PIPE_PREFIX)),
                () -> assertEquals(2_000L, again.get(SettingKeys.SCAN_INTERVAL_MS)),
                () -> assertEquals(1.5, again.get(SettingKeys.RECONNECT_BACKOFF)),
                () -> assertEquals(StartMode.ADVANCED, again.get(SettingKeys.START_MODE)),
                () -> assertEquals(TextSize.PERCENT_125, again.get(SettingKeys.TEXT_SIZE)),
                () -> assertFalse(again.get(SettingKeys.notifyEnabled(NotificationKind.CLIENT_BACK))),
                () -> assertTrue(again.get(SettingKeys.notifyEnabled(NotificationKind.CLIENT_LOST))));
    }

    @Test
    void save_keepsEntriesNoKeyClaims() throws IOException {
        Files.writeString(file(), "theme.accent=#4ade80\nautoReload=true\n");
        HostSettings settings = reopen();
        assertEquals(Map.of("theme.accent", "#4ade80"), settings.unknownEntries());
        assertTrue(settings.get(SettingKeys.AUTO_RELOAD));

        settings.set(SettingKeys.SCAN_INTERVAL_MS, 900L);
        settings.close();

        HostSettings again = reopen();
        assertEquals("#4ade80", again.unknownEntries().get("theme.accent"));
        assertTrue(again.get(SettingKeys.AUTO_RELOAD));
        assertEquals(900L, again.get(SettingKeys.SCAN_INTERVAL_MS));
    }

    @Test
    void reset_removesTheEntrySoTheDefaultApplies() {
        HostSettings settings = reopen();
        settings.set(SettingKeys.SCAN_INTERVAL_MS, 900L);
        settings.reset(SettingKeys.SCAN_INTERVAL_MS);
        settings.close();

        HostSettings again = reopen();
        assertFalse(again.isExplicit(SettingKeys.SCAN_INTERVAL_MS));
        assertEquals(5_000L, again.get(SettingKeys.SCAN_INTERVAL_MS));
    }

    // ── Validation ──────────────────────────────────────────────────────

    @Test
    void set_outOfBounds_isRefusedWithTheKeyAndTheRule() {
        HostSettings settings = reopen();

        InvalidSettingException e = assertThrows(InvalidSettingException.class,
                () -> settings.set(SettingKeys.SCAN_INTERVAL_MS, 10L));

        assertEquals("scanIntervalMs must be a whole number from 500 to 600000, got 10", e.getMessage());
        assertEquals("scanIntervalMs", e.settingName());
        assertEquals(5_000L, settings.get(SettingKeys.SCAN_INTERVAL_MS));
        assertInstanceOf(SaveStatus.Saved.class, settings.status(), "a refused value must not start a save");
    }

    @Test
    void setText_badText_isRefusedWithTheKeyAndTheRule() {
        HostSettings settings = reopen();

        assertAll(
                () -> assertEquals("defaultTimeout must be a whole number from 100 to 600000, got 'ten'",
                        assertThrows(InvalidSettingException.class,
                                () -> settings.setText("defaultTimeout", "ten")).getMessage()),
                () -> assertEquals("autoConnect must be true or false, got 'yes'",
                        assertThrows(InvalidSettingException.class,
                                () -> settings.setText("autoConnect", "yes")).getMessage()),
                () -> assertEquals("ui.startMode must be one of NORMAL, ADVANCED, got 'fullscreen'",
                        assertThrows(InvalidSettingException.class,
                                () -> settings.setText("ui.startMode", "fullscreen")).getMessage()),
                () -> assertTrue(assertThrows(InvalidSettingException.class,
                        () -> settings.setText("autoConnectPipes", "\\\\.\\pipe\\x")).getMessage()
                        .startsWith("autoConnectPipes must be 1 to 64 letters, digits")),
                () -> assertEquals("reconnect.backoff must be a number from 1.0 to 10.0, got NaN",
                        assertThrows(InvalidSettingException.class,
                                () -> settings.setText("reconnect.backoff", "NaN")).getMessage()),
                () -> assertEquals("nope is not a known setting",
                        assertThrows(InvalidSettingException.class,
                                () -> settings.setText("nope", "1")).getMessage()));
        assertFalse(Files.exists(file()));
    }

    @Test
    void setText_acceptsAnyCaseAndSurroundingSpace_andStoresTheCanonicalForm() {
        HostSettings settings = reopen();
        settings.setText("ui.startMode", " advanced ");
        settings.setText("autoConnect", "FALSE");

        assertEquals(StartMode.ADVANCED, settings.get(SettingKeys.START_MODE));
        assertEquals("ADVANCED", settings.text(SettingKeys.START_MODE));
        assertEquals("false", settings.text(SettingKeys.AUTO_CONNECT));
    }

    @Test
    void open_invalidValueInFile_isIgnoredAndTheDefaultApplies() throws IOException {
        Files.writeString(file(), "scanIntervalMs=soon\ndefaultTimeout=1\nautoReload=true\n");

        HostSettings settings = reopen();

        assertEquals(5_000L, settings.get(SettingKeys.SCAN_INTERVAL_MS));
        assertEquals(10_000L, settings.get(SettingKeys.RPC_TIMEOUT_MS));
        assertTrue(settings.get(SettingKeys.AUTO_RELOAD), "valid neighbours still load");
    }

    @Test
    void open_unparsableFile_isMovedAsideRatherThanOverwritten() throws IOException {
        Files.writeString(file(), "autoReload=\\uZZZZ\n", StandardCharsets.ISO_8859_1);

        HostSettings settings = reopen();

        assertInstanceOf(SaveStatus.Failed.class, settings.status());
        assertFalse(settings.get(SettingKeys.AUTO_RELOAD));
        assertTrue(Files.exists(dir.resolve(HostSettings.FILE_NAME + ".unreadable")));
        assertFalse(Files.exists(file()));
    }

    // ── Listeners ───────────────────────────────────────────────────────

    @Test
    void onChange_firesWithTheNewValue_onlyForItsKey_andOnlyOnARealChange() {
        HostSettings settings = reopen();
        List<Boolean> autoConnect = new ArrayList<>();
        List<SettingChange> all = new ArrayList<>();
        settings.onChange(SettingKeys.AUTO_CONNECT, autoConnect::add);
        settings.onAnyChange(all::add);

        settings.set(SettingKeys.AUTO_CONNECT, false);
        settings.set(SettingKeys.AUTO_CONNECT, false);
        settings.set(SettingKeys.SCAN_INTERVAL_MS, 900L);
        settings.reset(SettingKeys.AUTO_CONNECT);

        assertEquals(List.of(false, true), autoConnect);
        assertEquals(List.of(
                new SettingChange(SettingKeys.AUTO_CONNECT, "true", "false"),
                new SettingChange(SettingKeys.SCAN_INTERVAL_MS, "5000", "900"),
                new SettingChange(SettingKeys.AUTO_CONNECT, "false", "true")), all);
    }

    @Test
    void onChange_seesTheNewValueThroughGet() {
        HostSettings settings = reopen();
        List<Long> seen = new ArrayList<>();
        settings.onChange(SettingKeys.SCAN_INTERVAL_MS,
                value -> seen.add(settings.get(SettingKeys.SCAN_INTERVAL_MS)));

        settings.set(SettingKeys.SCAN_INTERVAL_MS, 700L);

        assertEquals(List.of(700L), seen);
    }

    @Test
    void subscriptionClose_stopsFurtherCalls_andAFailingListenerDoesNotBlockOthers() {
        HostSettings settings = reopen();
        List<String> calls = new ArrayList<>();
        settings.onAnyChange(change -> {
            throw new IllegalStateException("boom");
        });
        Subscription sub = settings.onAnyChange(change -> calls.add(change.newValue()));

        settings.set(SettingKeys.AUTO_RELOAD, true);
        sub.close();
        settings.set(SettingKeys.AUTO_RELOAD, false);

        assertEquals(List.of("true"), calls);
        assertFalse(settings.get(SettingKeys.AUTO_RELOAD));
    }

    @Test
    void set_keyFromAnotherCatalogue_isAProgrammingError() {
        HostSettings settings = reopen();
        SettingKey<Boolean> stranger = new SettingKey<>("autoConnect", "x", "x", new SettingType.Flag(), true);

        assertThrows(IllegalArgumentException.class, () -> settings.set(stranger, false));
    }

    // ── Instant save ────────────────────────────────────────────────────

    @Test
    void manyChangesInsideTheDebounceWindow_becomeOneWrite() throws InterruptedException {
        RecordingStorage storage = new RecordingStorage(file());
        CountDownLatch release = new CountDownLatch(1);
        HostSettings settings = open(storage, release::await);

        for (long interval = 1_000L; interval <= 1_009L; interval++) {
            settings.set(SettingKeys.SCAN_INTERVAL_MS, interval);
        }
        settings.set(SettingKeys.AUTO_RELOAD, true);
        assertInstanceOf(SaveStatus.Saving.class, settings.status());
        assertEquals(0, storage.saves(), "nothing is written inside the window");

        release.countDown();
        assertTrue(storage.awaitSaveAttempt());
        settings.flush(); // waits out the debounced write's bookkeeping; writes nothing itself

        assertEquals(1, storage.saves());
        assertEquals(new SaveStatus.Saved(CLOCK.instant()), settings.status());
        HostSettings again = reopen();
        assertEquals(1_009L, again.get(SettingKeys.SCAN_INTERVAL_MS));
        assertTrue(again.get(SettingKeys.AUTO_RELOAD));
    }

    @Test
    void change_isWrittenWithoutAFlush() throws InterruptedException {
        RecordingStorage storage = new RecordingStorage(file());
        HostSettings settings = open(storage, NO_PAUSE);

        settings.set(SettingKeys.PIPE_PREFIX, "Other");
        assertTrue(storage.awaitSaveAttempt());

        assertEquals("Other", reopen().get(SettingKeys.PIPE_PREFIX));
        assertFalse(Files.exists(dir.resolve(HostSettings.FILE_NAME + ".tmp")), "temp file is moved, not left");
    }

    @Test
    void flush_writesNowWithoutWaitingForTheWindow() {
        RecordingStorage storage = new RecordingStorage(file());
        CountDownLatch never = new CountDownLatch(1);
        HostSettings settings = open(storage, never::await);

        settings.set(SettingKeys.AUTO_RELOAD, true);
        SaveStatus status = settings.flush();

        assertInstanceOf(SaveStatus.Saved.class, status);
        assertEquals(1, storage.saves());
        assertTrue(reopen().get(SettingKeys.AUTO_RELOAD));
        settings.flush();
        assertEquals(1, storage.saves(), "a clean flush writes nothing");
        never.countDown();
    }

    @Test
    void failedWrite_isReported_andTheNextFlushRetries() {
        RecordingStorage storage = new RecordingStorage(file());
        CountDownLatch never = new CountDownLatch(1);
        HostSettings settings = open(storage, never::await);
        storage.failSaves(true);

        settings.set(SettingKeys.AUTO_RELOAD, true);
        SaveStatus failed = settings.flush();

        assertEquals(new SaveStatus.Failed("Could not save settings: disk says no", CLOCK.instant()), failed);
        storage.failSaves(false);
        assertInstanceOf(SaveStatus.Saved.class, settings.flush());
        assertTrue(reopen().get(SettingKeys.AUTO_RELOAD));
        never.countDown();
    }
}
