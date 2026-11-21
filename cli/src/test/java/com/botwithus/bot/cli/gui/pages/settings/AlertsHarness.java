package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.cli.alerts.AlertSettings;
import com.botwithus.bot.cli.alerts.FakeLauncher;
import com.botwithus.bot.cli.alerts.IntegrationMigration;
import com.botwithus.bot.cli.alerts.IntegrationsService;
import com.botwithus.bot.cli.alerts.LastSend;
import com.botwithus.bot.cli.alerts.ServiceStatusBoard;
import com.botwithus.bot.cli.settings.AlertSettingKeys;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.core.alerts.AlertService;
import com.botwithus.bot.core.alerts.IntegrationState;
import com.botwithus.bot.core.alerts.SendResult;
import com.botwithus.bot.core.secrets.InMemoryCredentialStore;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * The real alert back end the Integrations section binds to — settings in a temp
 * folder, the real {@link IntegrationsService} and status board — over a fake
 * launcher, and a background lane the test runs by hand, so a test can look at
 * the page while a call to the launcher is still on its way.
 */
final class AlertsHarness implements AutoCloseable {

    private static final int WINDOWS_PERCENT = 100;
    static final Instant NOW = Instant.parse("2026-09-26T14:05:00Z");
    static final String NOT_SET_UP = "Add the webhook URL first";
    /** Obviously fake: nothing is ever sent to it. */
    static final String DISCORD_HOOK = "https://discord.com/api/webhooks/0000/not-a-real-token";
    static final String DISCORD_HINT = "discord.com/…";

    final HostSettings settings;
    final FakeLauncher launcher = new FakeLauncher(Clock.fixed(NOW, ZoneOffset.UTC));
    final ServiceStatusBoard board = new ServiceStatusBoard();
    final IntegrationsService integrations;
    private final Deque<Runnable> lane = new ArrayDeque<>();

    AlertsHarness(Path dir) {
        settings = HostSettings.open(dir);
        integrations = new IntegrationsService(new AlertSettings(settings), launcher,
                new IntegrationMigration(launcher, new InMemoryCredentialStore(), settings), board,
                (at, task) -> () -> { }, lane::add, Clock.fixed(NOW, ZoneOffset.UTC), ZoneOffset.UTC);
        launcher.addListener(integrations);
    }

    /** The model the page uses in the host, over this back end. */
    LiveSettingsModel model(Path home) {
        LiveSettingsModel.Host host = new LiveSettingsModel.Host(settings, Optional.empty(), List::of,
                () -> Optional.of(integrations));
        LiveSettingsModel.Places places = new LiveSettingsModel.Places(home.resolve(".botwithus"),
                home.resolve("scripts"), home, home, home);
        return new LiveSettingsModel(host, places, path -> { }, Runnable::run, Clock.fixed(NOW, ZoneOffset.UTC),
                () -> WINDOWS_PERCENT);
    }

    void switchOn(AlertService service) {
        settings.set(AlertSettingKeys.enabled(service), true);
    }

    /** From now on a send to {@code service} is delivered, taking {@code elapsed}. */
    void delivers(AlertService service, Duration elapsed) {
        launcher.elapsed(elapsed);
    }

    /** As if a test to {@code service} had gone out earlier with {@code result}. */
    void lastTest(AlertService service, SendResult result) {
        board.record(service, LastSend.test(result));
    }

    /** As if the user had saved a secret earlier: the launcher has one, and the card has read it. */
    void saveSecret(AlertService service, String hint) {
        launcher.setup(new IntegrationState(service, true, Optional.of(hint), false, "", ""));
        integrations.onAvailable();
        runLane();
    }

    /** The secret last handed to the launcher for {@code service}. */
    Optional<String> savedSecret(AlertService service) {
        return launcher.savedSecret(service);
    }

    /** Lets every queued call to the launcher go out, and the launcher report on what it sent. */
    void runLane() {
        drain();
        launcher.reportAll();
        drain();
    }

    private void drain() {
        while (!lane.isEmpty()) {
            lane.poll().run();
        }
    }

    @Override
    public void close() {
        settings.close();
    }
}
