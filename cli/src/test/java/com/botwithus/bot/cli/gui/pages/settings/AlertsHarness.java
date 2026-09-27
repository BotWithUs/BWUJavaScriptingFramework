package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.cli.alerts.AlertSettings;
import com.botwithus.bot.cli.alerts.IntegrationsService;
import com.botwithus.bot.cli.alerts.LastSend;
import com.botwithus.bot.cli.alerts.NotifierSetup;
import com.botwithus.bot.cli.alerts.ServiceStatusBoard;
import com.botwithus.bot.cli.settings.AlertSettingKeys;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.core.alerts.AlertMessage;
import com.botwithus.bot.core.alerts.AlertService;
import com.botwithus.bot.core.alerts.Notifier;
import com.botwithus.bot.core.alerts.SendResult;
import com.botwithus.bot.core.secrets.InMemoryCredentialStore;
import com.botwithus.bot.core.secrets.Secret;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The real alert back end the Integrations section binds to — settings in a temp
 * folder, the real {@link IntegrationsService} and status board — over an
 * in-memory credential store, notifiers that answer what the test says, and a
 * delivery lane the test runs by hand, so a test can look at the page while a
 * send is still on its way.
 */
final class AlertsHarness implements AutoCloseable {

    private static final int WINDOWS_PERCENT = 100;
    static final Instant NOW = Instant.parse("2026-09-26T14:05:00Z");
    static final String NOT_SET_UP = "Add the webhook URL first";
    /** Obviously fake: the host never sends to it. */
    static final String DISCORD_HOOK = "https://discord.com/api/webhooks/0000/not-a-real-token";

    /** Answers every send with the same result. */
    private record FixedNotifier(AlertService service, SendResult result) implements Notifier {
        @Override
        public SendResult send(AlertMessage message) {
            return result;
        }
    }

    final HostSettings settings;
    final InMemoryCredentialStore credentials = new InMemoryCredentialStore();
    final ServiceStatusBoard board = new ServiceStatusBoard();
    final IntegrationsService integrations;
    private final Deque<Runnable> lane = new ArrayDeque<>();
    private final Map<AlertService, SendResult> answers = new EnumMap<>(AlertService.class);

    AlertsHarness(Path dir, boolean isPersistent) {
        settings = HostSettings.open(dir);
        integrations = new IntegrationsService(new AlertSettings(settings), credentials, isPersistent,
                service -> Optional.ofNullable(answers.get(service))
                        .<NotifierSetup>map(result -> new NotifierSetup.Ready(new FixedNotifier(service, result)))
                        .orElseGet(() -> new NotifierSetup.NotReady(NOT_SET_UP)),
                board, (service, send) -> lane.add(send), Clock.fixed(NOW, ZoneOffset.UTC), ZoneOffset.UTC);
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

    /** From now on a send to {@code service} is delivered. */
    void delivers(AlertService service, Duration elapsed) {
        answers.put(service, new SendResult.Delivered(NOW, elapsed, 1));
    }

    /** As if a test to {@code service} had gone out earlier with {@code result}. */
    void lastTest(AlertService service, SendResult result) {
        board.record(service, LastSend.test(result));
    }

    void saveSecret(AlertService service, String value) {
        credentials.write(service.credentialTarget(), new Secret(value));
    }

    Optional<String> savedSecret(AlertService service) {
        return credentials.read(service.credentialTarget()).map(Secret::reveal);
    }

    /** Lets every queued send go out. */
    void runLane() {
        while (!lane.isEmpty()) {
            lane.poll().run();
        }
    }

    @Override
    public void close() {
        settings.close();
    }
}
