package com.botwithus.bot.cli.gui.notify;

import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.Phase;
import com.botwithus.bot.api.runtime.ReconnectState;
import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.events.ClientRef;
import com.botwithus.bot.cli.events.HostEvent;
import com.botwithus.bot.cli.events.HostEvent.ClientClosed;
import com.botwithus.bot.cli.events.HostEvent.ClientForgotten;
import com.botwithus.bot.cli.events.HostEvent.CloseCause;
import com.botwithus.bot.cli.events.HostEvent.ClientResumed;
import com.botwithus.bot.cli.events.HostEvent.ConnectionLost;
import com.botwithus.bot.cli.events.HostEvent.ReconnectStateChanged;
import com.botwithus.bot.cli.events.HostEvent.ScriptCrashed;
import com.botwithus.bot.cli.events.HostEvent.ScriptLoadFailed;
import com.botwithus.bot.cli.events.HostEvent.ScriptStalled;
import com.botwithus.bot.cli.gui.notify.Notification.Action;
import com.botwithus.bot.cli.gui.notify.Notification.Kind;
import com.botwithus.bot.cli.report.ReportSubject;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.NotificationKind;
import com.botwithus.bot.cli.settings.SettingKeys;
import com.botwithus.bot.core.pipe.PipeException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Host events in, toasts out, through the real overlay and the real settings
 * file, so the Settings page's switches and duration are what is tested.
 */
class ToastFeedTest {

    private static final String PIPE = "BotWithUs_4242";
    private static final String ACCOUNT_UUID = "3f1c2a9e-0000-4000-8000-000000000001";
    private static final ClientRef OAK = new ClientRef(ClientKey.account(ACCOUNT_UUID), PIPE);
    private static final String OAK_NAME = "Oakheart";
    /** A second client, so connection toasts about the two do not replace each other. */
    private static final ClientRef FERN = new ClientRef("BotWithUs_9932");
    private static final Instant AT = MutableClock.START;
    private static final long CUSTOM_SECONDS = 12L;
    private static final long CHANGED_SECONDS = 3L;
    /** The design's pop-up lifetime, and the default until the user sets another. */
    private static final long DEFAULT_SECONDS = 6L;
    private static final long STALL_AFTER_MS = 45_000L;
    private static final long STALL_AFTER_TEN_MINUTES_MS = 600_000L;
    private static final int ATTEMPTS = 5;
    private static final long HALF_SECOND_MS = 500L;
    private static final Duration PAST_SLIDE = Duration.ofSeconds(1);

    @TempDir
    Path dir;

    private final MutableClock clock = new MutableClock();
    private final NotificationOverlay overlay = new NotificationOverlay(clock);
    private final List<ClientRef> goneClients = new CopyOnWriteArrayList<>();
    private HostSettings settings;
    private ToastFeed.Clients clients;
    private ToastFeed feed;

    @BeforeEach
    void setUp() {
        settings = HostSettings.open(dir);
        clients = new ToastFeed.Clients() {
            @Override
            public Optional<String> nameOf(ClientKey key) {
                return key.equals(OAK.key()) ? Optional.of(OAK_NAME) : Optional.empty();
            }

            @Override
            public boolean isGone(ClientRef client) {
                return goneClients.contains(client);
            }
        };
        feed = new ToastFeed(overlay, settings, clients);
    }

    @AfterEach
    void tearDown() {
        settings.close();
    }

    // ── Settings ────────────────────────────────────────────────────────────

    /** One event per Settings switch, each raising a toast of that switch's kind. */
    private static final Map<NotificationKind, HostEvent> SAMPLE = Map.of(
            NotificationKind.CLIENT_LOST, new ConnectionLost(OAK, new PipeException("eof"), AT),
            NotificationKind.CLIENT_BACK, new ReconnectStateChanged(FERN, new ReconnectState.Connected(0L), AT),
            NotificationKind.SCRIPT_CRASH, new ScriptStalled(OAK, "Divination", AT),
            NotificationKind.LOAD_FAILED, new ScriptLoadFailed(Path.of("broken.jar"),
                    new IllegalStateException("missing module-info provides"), AT));

    @ParameterizedTest
    @EnumSource(NotificationKind.class)
    void aSwitchedOffKind_raisesNoToast_andTheOthersStillDo(NotificationKind off) {
        settings.set(SettingKeys.notifyEnabled(off), false);

        for (NotificationKind kind : NotificationKind.values()) {
            NotificationOverlay own = new NotificationOverlay(clock);
            new ToastFeed(own, settings, clients).accept(SAMPLE.get(kind));
            own.update();

            List<NotificationKind> shown = own.active().stream().map(n -> n.kind().setting()).toList();
            List<NotificationKind> expected = kind == off ? List.of() : List.of(kind);
            assertEquals(expected, shown, "with " + off + " switched off, a " + kind + " event showed " + shown);
        }
    }

    @Test
    void untilTheUserSetsOne_aToastLastsSixSeconds() {
        feed.accept(new ScriptStalled(OAK, "Divination", AT));
        overlay.update();

        assertEquals(Optional.of(AT.plusSeconds(DEFAULT_SECONDS)), overlay.active().getFirst().expiresAt());
    }

    @Test
    void theSetDuration_isEachToastsLifetime_andAChangeAppliesToTheNextToast() {
        settings.set(SettingKeys.NOTIFY_DURATION_S, CUSTOM_SECONDS);
        feed.accept(new ScriptStalled(OAK, "Divination", AT));
        settings.set(SettingKeys.NOTIFY_DURATION_S, CHANGED_SECONDS);
        feed.accept(new ScriptStalled(OAK, "Fishing", AT));
        overlay.update();

        List<Notification> shown = overlay.active();
        assertAll(
                () -> assertEquals(Optional.of(AT.plusSeconds(CUSTOM_SECONDS)), shown.get(0).expiresAt()),
                () -> assertEquals(Optional.of(AT.plusSeconds(CHANGED_SECONDS)), shown.get(1).expiresAt()));
    }

    @Test
    void errors_stayUntilClosed_whateverTheDuration() {
        settings.set(SettingKeys.NOTIFY_DURATION_S, CHANGED_SECONDS);
        feed.accept(crash());
        overlay.update();

        assertEquals(Optional.empty(), overlay.active().getFirst().expiresAt());
    }

    @Test
    void aCrash_offersAReportOfThatScriptOnThatPipe_andNothingElseDoes() {
        feed.accept(crash());
        feed.accept(new ScriptStalled(OAK, "Divination", AT));
        overlay.update();

        assertEquals(List.of(Optional.of(new ReportSubject(PIPE, "Cook's Assistant")), Optional.empty()),
                overlay.active().stream().map(Notification::report).toList());
    }

    @Test
    void aCrashOnAClientWithNoPipe_offersNoReport() {
        feed.accept(new ScriptCrashed(new ClientRef(OAK.key(), ClientRef.NO_PIPE), "Cook's Assistant",
                new LastCrash(Phase.ON_LOOP, 0L, AT, new NullPointerException()), AT));
        overlay.update();

        assertEquals(Optional.empty(), overlay.active().getFirst().report());
    }

    // ── Connection ──────────────────────────────────────────────────────────

    @Test
    void aDropAndItsRetries_areOneToast_namedByAccount() {
        feed.accept(new ConnectionLost(OAK, new PipeException("eof"), AT));
        feed.accept(reconnect(new ReconnectState.Disconnected(0L, new PipeException("eof"))));
        feed.accept(reconnect(new ReconnectState.Reconnecting(0L, 1, HALF_SECOND_MS)));
        feed.accept(reconnect(new ReconnectState.Reconnecting(0L, 2, HALF_SECOND_MS)));
        overlay.update();

        Notification only = overlay.active().getFirst();
        assertAll(
                () -> assertEquals(1, overlay.active().size()),
                () -> assertEquals(Kind.RECONNECTING, only.kind()),
                () -> assertEquals("Oakheart not responding", only.title()),
                () -> assertEquals("Retrying: attempt 2, next in 0.5 s.", only.message()));
    }

    @Test
    void aLaterRetry_withTheToastAlreadyGone_doesNotRaiseItAgain() {
        feed.accept(reconnect(new ReconnectState.Reconnecting(0L, ATTEMPTS, HALF_SECOND_MS)));
        overlay.update();

        assertTrue(overlay.active().isEmpty());
    }

    @Test
    void givingUp_offersTryAgain_andStays() {
        feed.accept(reconnect(new ReconnectState.GivingUp(0L, ATTEMPTS, new PipeException("no pipe"))));
        overlay.update();

        Notification gaveUp = overlay.active().getFirst();
        assertAll(
                () -> assertEquals(Kind.GAVE_UP, gaveUp.kind()),
                () -> assertEquals(Action.TRY_AGAIN, gaveUp.kind().action()),
                () -> assertEquals(Optional.of(OAK.key()), gaveUp.client()),
                () -> assertEquals("5 attempts failed.", gaveUp.message()),
                () -> assertEquals(Optional.empty(), gaveUp.expiresAt()));
    }

    @Test
    void stoppingRetries_isNotReportedAsGivingUp_andTakesTheRetryToastDown() {
        feed.accept(reconnect(new ReconnectState.Reconnecting(0L, 1, HALF_SECOND_MS)));
        overlay.update();

        feed.accept(reconnect(new ReconnectState.GivingUp(0L, 1,
                new CancellationException("Stopped retrying on request"))));
        overlay.update();
        clock.advance(PAST_SLIDE);
        overlay.update();

        assertTrue(overlay.active().isEmpty(), "a stop the user asked for is not an error: " + kinds());
    }

    @Test
    void givingUpBecauseTheGameExited_saysItClosed_andOffersNoRetry() {
        goneClients.add(OAK);
        feed.accept(reconnect(new ReconnectState.GivingUp(0L, 1, new PipeException("process exited"))));
        overlay.update();

        Notification closed = overlay.active().getFirst();
        assertAll(
                () -> assertEquals(Kind.CLIENT_CLOSED, closed.kind()),
                () -> assertEquals("Oakheart's game client closed", closed.title()),
                () -> assertEquals(Action.NONE, closed.kind().action()));
    }

    @Test
    void theSameAccountOnANewPipe_isBack() {
        feed.accept(new ClientResumed(OAK, Optional.of("BotWithUs_1000"), AT));
        overlay.update();

        assertEquals(List.of(Kind.CLIENT_RESUMED), kinds());
        assertEquals("Oakheart is back", overlay.active().getFirst().title());
    }

    @Test
    void aClientRememberedFromTheLastRun_isNotBackWhenTheHostStarts() {
        feed.accept(new ClientResumed(OAK, Optional.empty(), AT));
        overlay.update();

        assertTrue(overlay.active().isEmpty());
    }

    @Test
    void forgettingAClient_takesItsConnectionToastDown() {
        feed.accept(reconnect(new ReconnectState.GivingUp(0L, ATTEMPTS, new PipeException("no pipe"))));
        overlay.update();

        feed.accept(new ClientForgotten(OAK, AT));
        overlay.update();
        clock.advance(PAST_SLIDE);
        overlay.update();

        assertTrue(overlay.active().isEmpty());
    }

    @Test
    void disconnectingAClient_takesItsRetryToastDown_butALostConnectionDoesNot() {
        feed.accept(reconnect(new ReconnectState.Reconnecting(0L, 1, HALF_SECOND_MS)));
        feed.accept(new ConnectionLost(FERN, new PipeException("eof"), AT));
        overlay.update();

        feed.accept(new ClientClosed(OAK, CloseCause.DISCONNECTED, AT));
        feed.accept(new ClientClosed(FERN, CloseCause.CONNECTION_LOST, AT));
        overlay.update();
        clock.advance(PAST_SLIDE);
        overlay.update();

        assertEquals(List.of(Optional.of(FERN.key())), overlay.active().stream().map(Notification::client).toList());
    }

    @Test
    void switchingOffClientBack_stillTakesTheOutdatedRetryToastDown() {
        settings.set(SettingKeys.notifyEnabled(NotificationKind.CLIENT_BACK), false);
        feed.accept(reconnect(new ReconnectState.Reconnecting(0L, 1, HALF_SECOND_MS)));
        overlay.update();

        feed.accept(reconnect(new ReconnectState.Connected(0L)));
        overlay.update();
        clock.advance(PAST_SLIDE);
        overlay.update();

        assertTrue(overlay.active().isEmpty(), "shown: " + kinds());
    }

    // ── Scripts ─────────────────────────────────────────────────────────────

    @Test
    void aStall_isNamed_andUsesTheStallThreshold() {
        settings.set(SettingKeys.STALL_AFTER_MS, STALL_AFTER_MS);
        feed.accept(new ScriptStalled(OAK, "Divination", AT));
        overlay.update();

        Notification stalled = overlay.active().getFirst();
        assertAll(
                () -> assertEquals(Kind.SCRIPT_STALLED, stalled.kind()),
                () -> assertEquals("Divination stalled on Oakheart", stalled.title()),
                () -> assertEquals("Still inside one loop after 45 s.", stalled.message()),
                () -> assertEquals(Action.VIEW_LOG, stalled.kind().action()));
    }

    @Test
    void aStallThresholdOfWholeMinutes_isWordedInMinutes() {
        settings.set(SettingKeys.STALL_AFTER_MS, STALL_AFTER_TEN_MINUTES_MS);
        feed.accept(new ScriptStalled(OAK, "Divination", AT));
        overlay.update();

        assertEquals("Still inside one loop after 10 min.", overlay.active().getFirst().message());
    }

    @Test
    void aCrash_namesTheClientAndTheHook() {
        feed.accept(crash());
        overlay.update();

        Notification crashed = overlay.active().getFirst();
        assertAll(
                () -> assertEquals("Cook's Assistant crashed", crashed.title()),
                () -> assertEquals("Oakheart · NullPointerException in onLoop()", crashed.message()),
                () -> assertEquals(Optional.of(OAK.key()), crashed.client()));
    }

    @Test
    void aLoadFailure_belongsToNoClient() {
        feed.accept(SAMPLE.get(NotificationKind.LOAD_FAILED));
        overlay.update();

        Notification failed = overlay.active().getFirst();
        assertAll(
                () -> assertEquals("broken.jar · missing module-info provides", failed.message()),
                () -> assertEquals(Optional.empty(), failed.client()),
                () -> assertEquals(Action.DETAILS, failed.kind().action()));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private List<Kind> kinds() {
        return overlay.active().stream().map(Notification::kind).toList();
    }

    private static ReconnectStateChanged reconnect(ReconnectState state) {
        return new ReconnectStateChanged(OAK, state, AT);
    }

    private static ScriptCrashed crash() {
        return new ScriptCrashed(OAK, "Cook's Assistant",
                new LastCrash(Phase.ON_LOOP, 0L, AT, new NullPointerException()), AT);
    }
}
