package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.cli.alerts.FakeLauncher;
import com.botwithus.bot.cli.alerts.SecretChange;
import com.botwithus.bot.core.alerts.AlertService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The secret box's commit rules, through the live model into the real
 * Integrations back end and a fake launcher. The box commits when it is left or
 * Enter is pressed ({@link SettingsWidgets.Field#isCommitted()}); these tests
 * stand in for that frame, run the hand-over, and poll as the next frame does.
 */
class SecretFieldsTest {

    private static final AlertService DISCORD = AlertService.DISCORD;
    private static final String NOT_HTTPS = "http://discord.com/api/webhooks/2222/not-real";

    @TempDir
    Path dir;

    private AlertsHarness alerts;
    private LiveSettingsModel model;
    private final SecretFields secrets = new SecretFields();

    @BeforeEach
    void setUp() {
        alerts = new AlertsHarness(dir.resolve("data"));
        model = alerts.model(dir);
    }

    @AfterEach
    void tearDown() {
        alerts.close();
    }

    private CardField.SecretBox box() {
        SettingsItem.ServiceCard card = IntegrationsSectionTest.card(IntegrationsSectionTest.items(model), DISCORD);
        return card.fields().stream().flatMap(f -> switch (f) {
            case CardField.SecretBox box -> Stream.of(box);
            default -> Stream.<CardField.SecretBox>empty();
        }).findFirst().orElseThrow();
    }

    /** The hand-over finishes and the next frame takes its outcome in. */
    private void nextFrame() {
        alerts.runLane();
        secrets.poll(DISCORD);
    }

    @Test
    void commit_handsWhatWasTypedToTheLauncher_andShowsItsHint() {
        secrets.buffer(DISCORD).set(AlertsHarness.DISCORD_HOOK);

        Optional<CompletableFuture<SecretChange>> change = secrets.commit(DISCORD, model);
        nextFrame();

        assertAll(
                () -> assertEquals(new SecretChange.Saved(), change.orElseThrow().join()),
                () -> assertEquals(Optional.of(AlertsHarness.DISCORD_HOOK), alerts.savedSecret(DISCORD)),
                () -> assertEquals("", secrets.buffer(DISCORD).get(), "the box lets go of the secret once saved"),
                () -> assertTrue(box().hasSecret()),
                () -> assertEquals("hint/…", secrets.hint(box()), "and shows the launcher's masked hint"));
    }

    @Test
    void whileTheHandOverIsOnItsWay_theBoxSaysSo_andASecondCommitWaits() {
        secrets.buffer(DISCORD).set(AlertsHarness.DISCORD_HOOK);
        secrets.commit(DISCORD, model);

        Optional<CompletableFuture<SecretChange>> again = secrets.commit(DISCORD, model);

        assertAll(
                () -> assertEquals(SecretFields.SAVING, secrets.hint(box())),
                () -> assertEquals(Optional.empty(), again));
    }

    @Test
    void commit_ofAnEmptyBox_leavesTheSavedSecretAlone() {
        alerts.saveSecret(DISCORD, AlertsHarness.DISCORD_HINT);

        Optional<CompletableFuture<SecretChange>> change = secrets.commit(DISCORD, model);
        nextFrame();

        assertAll(
                () -> assertEquals(Optional.empty(), change, "Enter in an empty masked box must not clear it"),
                () -> assertEquals(0, alerts.launcher.changes().size(), "nothing was asked of the launcher"),
                () -> assertEquals(AlertsHarness.DISCORD_HINT, secrets.hint(box())));
    }

    @Test
    void remove_clearsTheSavedSecretOnTheLauncher() {
        alerts.saveSecret(DISCORD, AlertsHarness.DISCORD_HINT);

        secrets.remove(DISCORD, model);
        nextFrame();

        assertAll(
                () -> assertTrue(alerts.launcher.changes().getLast().isClearingSecret()),
                () -> assertEquals(Optional.empty(), secrets.error(DISCORD)),
                () -> assertEquals(ServiceCopy.secretPlaceholder(DISCORD), secrets.hint(box())));
    }

    @Test
    void aRefusedSecret_staysInTheBoxWithTheLaunchersReason_andNothingIsSaved() {
        secrets.buffer(DISCORD).set(NOT_HTTPS);

        secrets.commit(DISCORD, model);
        nextFrame();

        assertAll(
                () -> assertEquals(Optional.of(FakeLauncher.HTTPS_REQUIRED), secrets.error(DISCORD)),
                () -> assertEquals(NOT_HTTPS, secrets.buffer(DISCORD).get(), "kept, so it can be fixed"),
                () -> assertEquals(Optional.empty(), alerts.savedSecret(DISCORD)));
    }
}
