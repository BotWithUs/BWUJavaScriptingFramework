package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.cli.alerts.SecretChange;
import com.botwithus.bot.core.alerts.AlertService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The secret box's commit rules, through the live model into the real
 * Integrations back end and an in-memory credential store. The box commits when
 * it is left or Enter is pressed ({@link SettingsWidgets.Field#isCommitted()});
 * these tests stand in for that frame.
 */
class SecretFieldsTest {

    private static final AlertService DISCORD = AlertService.DISCORD;
    private static final String OTHER_HOOK = "https://discord.com/api/webhooks/1111/also-not-real";
    private static final String NOT_HTTPS = "http://discord.com/api/webhooks/2222/not-real";

    @TempDir
    Path dir;

    private AlertsHarness alerts;
    private LiveSettingsModel model;
    private final SecretFields secrets = new SecretFields();

    @BeforeEach
    void setUp() {
        alerts = new AlertsHarness(dir.resolve("data"), true);
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

    @Test
    void commit_savesWhatWasTypedAndMasksTheBoxAgain() {
        secrets.buffer(DISCORD).set(AlertsHarness.DISCORD_HOOK);

        Optional<SecretChange> change = secrets.commit(DISCORD, model);

        assertAll(
                () -> assertEquals(Optional.of(new SecretChange.Saved()), change),
                () -> assertEquals(Optional.of(AlertsHarness.DISCORD_HOOK), alerts.savedSecret(DISCORD)),
                () -> assertEquals("", secrets.buffer(DISCORD).get(), "the box lets go of the secret once saved"),
                () -> assertEquals(SecretFields.SAVED_MASK, secrets.hint(box()), "and shows it is saved"));
    }

    @Test
    void commit_ofAnUntouchedBox_leavesTheSavedSecretAlone() {
        alerts.saveSecret(DISCORD, AlertsHarness.DISCORD_HOOK);

        Optional<SecretChange> change = secrets.commit(DISCORD, model);

        assertAll(
                () -> assertEquals(Optional.empty(), change, "Enter in an empty masked box must not clear it"),
                () -> assertEquals(Optional.of(AlertsHarness.DISCORD_HOOK), alerts.savedSecret(DISCORD)));
    }

    @Test
    void show_readsTheSavedSecretIntoTheBox_andHideDropsItAgain() {
        alerts.saveSecret(DISCORD, AlertsHarness.DISCORD_HOOK);

        secrets.toggleReveal(DISCORD, model);
        String shown = secrets.buffer(DISCORD).get();
        secrets.toggleReveal(DISCORD, model);

        assertAll(
                () -> assertEquals(AlertsHarness.DISCORD_HOOK, shown),
                () -> assertEquals("", secrets.buffer(DISCORD).get()),
                () -> assertEquals(Optional.empty(), secrets.commit(DISCORD, model), "showing is not an edit"));
    }

    @Test
    void commit_blank_clearsTheSavedSecret() {
        alerts.saveSecret(DISCORD, AlertsHarness.DISCORD_HOOK);
        secrets.toggleReveal(DISCORD, model);
        secrets.buffer(DISCORD).set("");

        Optional<SecretChange> change = secrets.commit(DISCORD, model);

        assertAll(
                () -> assertEquals(Optional.of(new SecretChange.Cleared()), change),
                () -> assertEquals(Optional.empty(), alerts.savedSecret(DISCORD)),
                () -> assertEquals(box().placeholder(), secrets.hint(box()), "the empty box shows the example again"));
    }

    @Test
    void commit_whileShown_replacesTheSecretAndKeepsItVisible() {
        alerts.saveSecret(DISCORD, AlertsHarness.DISCORD_HOOK);
        secrets.toggleReveal(DISCORD, model);
        secrets.buffer(DISCORD).set(OTHER_HOOK);

        secrets.commit(DISCORD, model);

        assertAll(
                () -> assertEquals(Optional.of(OTHER_HOOK), alerts.savedSecret(DISCORD)),
                () -> assertEquals(OTHER_HOOK, secrets.buffer(DISCORD).get()),
                () -> assertEquals(Optional.empty(), secrets.commit(DISCORD, model), "saved text is not re-sent"));
    }

    @Test
    void aRefusedSecret_staysInTheBoxWithItsReason_andNothingIsSaved() {
        secrets.buffer(DISCORD).set(NOT_HTTPS);

        secrets.commit(DISCORD, model);

        assertAll(
                () -> assertEquals(Optional.of("The webhook URL must start with https://"), secrets.error(DISCORD)),
                () -> assertEquals(NOT_HTTPS, secrets.buffer(DISCORD).get()),
                () -> assertEquals(Optional.empty(), alerts.savedSecret(DISCORD)));

        secrets.buffer(DISCORD).set(AlertsHarness.DISCORD_HOOK);
        secrets.commit(DISCORD, model);

        assertEquals(Optional.empty(), secrets.error(DISCORD), "a good value clears the reason");
    }

    @Test
    void hide_keepsANewSecretThatIsNotSavedYet() {
        secrets.buffer(DISCORD).set(OTHER_HOOK);
        secrets.toggleReveal(DISCORD, model);
        secrets.toggleReveal(DISCORD, model);

        assertEquals(OTHER_HOOK, secrets.buffer(DISCORD).get());
    }
}
