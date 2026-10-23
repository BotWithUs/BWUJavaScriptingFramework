package com.botwithus.bot.cli;

import com.botwithus.bot.cli.alerts.Integrations;
import com.botwithus.bot.cli.alerts.ServiceStatus;
import com.botwithus.bot.cli.alerts.ServiceView;
import com.botwithus.bot.cli.events.ClientRef;
import com.botwithus.bot.cli.events.HostEvent;
import com.botwithus.bot.cli.settings.AlertSettingKeys;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.core.alerts.AlertService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The alert wiring in {@link CliContext}: started once, stopped at shutdown, and
 * fed by the host event bus. No request leaves the machine: the only service
 * switched on has no topic, so the send stops at "not set up".
 */
class CliContextAlertsTest {

    private static final long WAIT_MS = 5_000;
    private static final long POLL_MS = 20;

    @TempDir
    Path dir;

    private CliContext ctx;
    private HostSettings settings;

    @BeforeEach
    void open() {
        ctx = TestContexts.inDir(dir, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        settings = HostSettings.open(dir.resolve("settings"));
    }

    @AfterEach
    void close() {
        ctx.stopAlerts();
        settings.close();
    }

    @Test
    void startAlerts_withoutSettings_isRefused() {
        assertThrows(IllegalStateException.class, ctx::startAlerts);
    }

    @Test
    void startAlerts_isIdempotent_andStopClearsIt() {
        ctx.setSettings(settings);
        assertEquals(Optional.empty(), ctx.getIntegrations());

        Integrations first = ctx.startAlerts();

        assertSame(first, ctx.startAlerts());
        assertSame(first, ctx.getIntegrations().orElseThrow());
        ctx.stopAlerts();
        assertEquals(Optional.empty(), ctx.getIntegrations());
    }

    @Test
    void aHostEvent_reachesTheDispatcherOffTheBusThread() throws InterruptedException {
        ctx.setSettings(settings);
        settings.set(AlertSettingKeys.enabled(AlertService.NTFY), true);
        settings.set(AlertSettingKeys.BURST_SECONDS, 0L);
        Integrations integrations = ctx.startAlerts();

        ctx.getHostEvents().publish(new HostEvent.ConnectionLost(new ClientRef("BotWithUs_1"), null, Instant.now()));

        ServiceView view = awaitStatus(integrations, ServiceStatus.FAILED);
        assertEquals(Optional.of("Not set up · Add the topic first"),
                view.lastLine().map(line -> line.substring(0, line.lastIndexOf(" ("))));
    }

    private static ServiceView awaitStatus(Integrations integrations, ServiceStatus wanted)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + WAIT_MS;
        ServiceView view = integrations.service(AlertService.NTFY);
        while (view.status() != wanted && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS);
            view = integrations.service(AlertService.NTFY);
        }
        assertEquals(wanted, view.status());
        return view;
    }
}
