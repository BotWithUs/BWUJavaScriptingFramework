package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.cli.alerts.SecretChange;
import com.botwithus.bot.cli.alerts.ServiceStatus;
import com.botwithus.bot.cli.settings.AlertSettingKeys;
import com.botwithus.bot.core.alerts.AlertKind;
import com.botwithus.bot.core.alerts.AlertService;
import com.botwithus.bot.core.alerts.SendResult;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Integrations section as the live model builds it over the real alert back end. */
class IntegrationsSectionTest {

    private static final Duration ELAPSED = Duration.ofMillis(180);

    @TempDir
    Path dir;

    private AlertsHarness alerts;
    private LiveSettingsModel model;

    @BeforeEach
    void setUp() {
        alerts = new AlertsHarness(dir.resolve("data"), true);
        model = alerts.model(dir);
    }

    @AfterEach
    void tearDown() {
        alerts.close();
    }

    static List<SettingsItem> items(SettingsModel model) {
        return model.view().sections().stream().filter(s -> s.section() == SettingsSection.INTEGRATIONS)
                .findFirst().orElseThrow().items();
    }

    static SettingsItem.ServiceCard card(List<SettingsItem> items, AlertService service) {
        return cards(items).filter(c -> c.service() == service).findFirst().orElseThrow();
    }

    static Stream<SettingsItem.ServiceCard> cards(List<SettingsItem> items) {
        return items.stream().flatMap(i -> switch (i) {
            case SettingsItem.ServiceCard card -> Stream.of(card);
            default -> Stream.empty();
        });
    }

    static SettingsItem.EventGrid grid(List<SettingsItem> items) {
        return items.stream().flatMap(i -> switch (i) {
            case SettingsItem.EventGrid grid -> Stream.of(grid);
            default -> Stream.<SettingsItem.EventGrid>empty();
        }).findFirst().orElseThrow();
    }

    private static List<SettingsItem.Notice> notices(List<SettingsItem> items) {
        return items.stream().flatMap(i -> switch (i) {
            case SettingsItem.Notice notice -> Stream.of(notice);
            default -> Stream.<SettingsItem.Notice>empty();
        }).toList();
    }

    private SettingsItem.ServiceCard card(AlertService service) {
        return card(items(model), service);
    }

    // ── Status ─────────────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({
            "OFF, Off, IDLE",
            "NOT_TESTED, Not tested, IDLE",
            "WORKING, Working, OK",
            "FAILED, Last send failed, ERROR",
            "SENDING, Sending test…, BUSY"})
    void statusChip_saysTheStatusInItsColour(ServiceStatus status, String label, StatusChip.Tone tone) {
        assertEquals(new StatusChip(label, tone), StatusChip.of(status));
    }

    @Test
    void aServiceThatIsOff_isOffWithNoResult() {
        SettingsItem.ServiceCard slack = card(AlertService.SLACK);

        assertAll(
                () -> assertFalse(slack.isEnabled()),
                () -> assertEquals(new StatusChip("Off", StatusChip.Tone.IDLE), slack.chip()),
                () -> assertEquals(Optional.empty(), slack.result()));
    }

    @Test
    void aServiceSwitchedOnAndNeverSentTo_isNotTested() {
        model.edit(AlertSettingKeys.enabled(AlertService.NTFY).name(), "true");

        SettingsItem.ServiceCard ntfy = card(AlertService.NTFY);

        assertAll(
                () -> assertTrue(ntfy.isEnabled()),
                () -> assertEquals(new StatusChip("Not tested", StatusChip.Tone.IDLE), ntfy.chip()),
                () -> assertEquals(Optional.empty(), ntfy.result()),
                () -> assertTrue(ntfy.canSendTest()));
    }

    @Test
    void aFailedSend_showsItsStatusAndReasonAsAnError() {
        alerts.switchOn(AlertService.DISCORD);
        alerts.lastTest(AlertService.DISCORD,
                new SendResult.Failed(AlertsHarness.NOW, "401 Unauthorized", "Unknown Webhook", 1));

        SettingsItem.ServiceCard discord = card(AlertService.DISCORD);

        assertAll(
                () -> assertEquals(new StatusChip("Last send failed", StatusChip.Tone.ERROR), discord.chip()),
                () -> assertEquals(Optional.of(new ResultLine("401 Unauthorized · Unknown Webhook (14:05)",
                        StatusChip.Tone.ERROR)), discord.result()));
    }

    @Test
    void aDeliveredSend_isWorking() {
        alerts.switchOn(AlertService.NTFY);
        alerts.lastTest(AlertService.NTFY, new SendResult.Delivered(AlertsHarness.NOW, ELAPSED, 1));

        SettingsItem.ServiceCard ntfy = card(AlertService.NTFY);

        assertAll(
                () -> assertEquals(new StatusChip("Working", StatusChip.Tone.OK), ntfy.chip()),
                () -> assertEquals(Optional.of(new ResultLine("Test delivered 14:05 · 180 ms", StatusChip.Tone.OK)),
                        ntfy.result()));
    }

    // ── Send test ──────────────────────────────────────────────────────────

    @Test
    void sendTest_showsSendingWithNoOldResult_thenTheTestsOwnResult() {
        alerts.switchOn(AlertService.DISCORD);
        alerts.lastTest(AlertService.DISCORD,
                new SendResult.Failed(AlertsHarness.NOW, "401 Unauthorized", "Unknown Webhook", 1));
        alerts.delivers(AlertService.DISCORD, ELAPSED);

        model.sendTest(AlertService.DISCORD);
        SettingsItem.ServiceCard sending = card(AlertService.DISCORD);
        alerts.runLane();
        SettingsItem.ServiceCard done = card(AlertService.DISCORD);

        assertAll(
                () -> assertEquals(new StatusChip("Sending test…", StatusChip.Tone.BUSY), sending.chip()),
                () -> assertFalse(sending.canSendTest(), "Send test is disabled while one is on its way"),
                () -> assertEquals(Optional.empty(), sending.result(), "the old failure is not shown meanwhile"),
                () -> assertEquals(new StatusChip("Working", StatusChip.Tone.OK), done.chip()),
                () -> assertTrue(done.canSendTest()),
                () -> assertEquals(Optional.of(new ResultLine("Test delivered 14:05 · 180 ms", StatusChip.Tone.OK)),
                        done.result()));
    }

    @Test
    void sendTest_toAServiceThatIsNotSetUp_saysWhatIsMissing() {
        alerts.switchOn(AlertService.SLACK);

        model.sendTest(AlertService.SLACK);
        alerts.runLane();

        assertEquals(Optional.of(new ResultLine("Not set up · " + AlertsHarness.NOT_SET_UP + " (14:05)",
                StatusChip.Tone.ERROR)), card(AlertService.SLACK).result());
    }

    // ── Event grid ─────────────────────────────────────────────────────────

    @Test
    void grid_greysTheColumnsOfServicesThatAreOff() {
        alerts.switchOn(AlertService.NTFY);

        SettingsItem.EventGrid grid = grid(items(model));

        assertEquals(List.of(new GridColumn(AlertService.NTFY, true), new GridColumn(AlertService.SLACK, false),
                new GridColumn(AlertService.DISCORD, false)), grid.columns());
        assertEquals(AlertKind.values().length, grid.rows().size());
        for (GridRow row : grid.rows()) {
            assertEquals(List.of(true, false, false), row.cells().stream().map(GridRow.Cell::isEnabled).toList(),
                    row.label());
        }
    }

    @Test
    void grid_ticksFollowTheRoutesAndAnEditChangesOne() {
        String crashToDiscord = AlertSettingKeys.route(AlertService.DISCORD, AlertKind.SCRIPT_CRASH).name();
        GridRow before = row(AlertKind.SCRIPT_CRASH);

        model.edit(crashToDiscord, "false");

        assertAll(
                () -> assertEquals(new GridRow.Cell(crashToDiscord, true, false), before.cells().get(2)),
                () -> assertFalse(alerts.settings.get(AlertSettingKeys.route(AlertService.DISCORD,
                        AlertKind.SCRIPT_CRASH))),
                () -> assertFalse(row(AlertKind.SCRIPT_CRASH).cells().get(2).isTicked()),
                () -> assertFalse(row(AlertKind.CLIENT_BACK).cells().get(0).isTicked()));
    }

    @Test
    void switchingAServiceOn_enablesItsColumn() {
        model.edit(AlertSettingKeys.enabled(AlertService.DISCORD).name(), "true");

        assertTrue(row(AlertKind.CLIENT_LOST).cells().get(2).isEnabled());
    }

    @Test
    void grid_saysWhenTheDailySummaryIsSent() {
        alerts.settings.set(AlertSettingKeys.SUMMARY_AT, "21:30");

        assertEquals("runtime per client, at 21:30", row(AlertKind.DAILY_SUMMARY).detail());
    }

    private GridRow row(AlertKind kind) {
        return grid(items(model)).rows().stream().filter(r -> r.kind() == kind).findFirst().orElseThrow();
    }

    // ── Section as a whole ─────────────────────────────────────────────────

    @Test
    void section_isCardsThenGridThenBurstsQuietHoursAndSummary() {
        List<String> shape = items(model).stream().map(i -> switch (i) {
            case SettingsItem.ServiceCard card -> card.service().id();
            case SettingsItem.EventGrid _ -> "grid";
            case SettingsItem.KeyRow row -> row.name();
            case SettingsItem.QuietHours _ -> "quiet";
            default -> "other";
        }).toList();

        assertEquals(List.of("ntfy", "slack", "discord", "grid", AlertSettingKeys.BURST_SECONDS.name(), "quiet",
                AlertSettingKeys.SUMMARY_AT.name()), shape);
    }

    @Test
    void section_comesBetweenNotificationsAndInterface() {
        List<SettingsSection> order = model.view().sections().stream().map(SectionView::section).toList();

        assertEquals(order.indexOf(SettingsSection.NOTIFICATIONS) + 1, order.indexOf(SettingsSection.INTEGRATIONS));
        assertEquals(order.indexOf(SettingsSection.INTEGRATIONS) + 1, order.indexOf(SettingsSection.INTERFACE));
    }

    @Test
    void sessionOnlySecrets_areSaidAtTheTopOfTheSection(@TempDir Path other) {
        try (AlertsHarness sessionOnly = new AlertsHarness(other.resolve("data"), false)) {
            List<SettingsItem> items = items(sessionOnly.model(other));

            assertEquals(new SettingsItem.Notice(IntegrationsSheet.SESSION_ONLY, true), items.getFirst());
        }
        assertEquals(List.of(), notices(items(model)), "no notice while secrets are kept");
    }

    @Test
    void withoutAlerts_theSectionSaysSoAndStillOffersItsSettings() {
        LiveSettingsModel.Host host = new LiveSettingsModel.Host(alerts.settings, Optional.empty(), List::of);
        LiveSettingsModel.Places places = new LiveSettingsModel.Places(dir, dir, dir, dir, dir);
        LiveSettingsModel noAlerts = new LiveSettingsModel(host, places, path -> { }, Runnable::run,
                Clock.systemUTC(), () -> 100);

        List<SettingsItem> items = items(noAlerts);

        assertAll(
                () -> assertEquals(new SettingsItem.Notice(IntegrationsSheet.NOT_RUNNING, true), items.getFirst()),
                () -> assertEquals(0L, cards(items).count()),
                () -> assertEquals(AlertKind.values().length, grid(items).rows().size()),
                () -> assertEquals(new SecretChange.Refused(LiveSettingsModel.ALERTS_NOT_RUNNING),
                        noAlerts.saveSecret(AlertService.SLACK, "https://hooks.slack.com/services/…")));
    }

    @Test
    void quietHours_areOneRowWithTheirSwitchAndTimes() {
        model.edit(AlertSettingKeys.QUIET_ENABLED.name(), "true");
        model.edit(AlertSettingKeys.QUIET_FROM.name(), "23:00");

        SettingsItem.QuietHours quiet = items(model).stream().flatMap(i -> switch (i) {
            case SettingsItem.QuietHours q -> Stream.of(q);
            default -> Stream.<SettingsItem.QuietHours>empty();
        }).findFirst().orElseThrow();

        assertAll(
                () -> assertTrue(quiet.isOn()),
                () -> assertEquals("23:00", quiet.from()),
                () -> assertEquals("08:00", quiet.to()),
                () -> assertTrue(quiet.description().startsWith("Mute everything except crashes"),
                        "quiet hours drop alerts, so the row must not promise to hold them"));
    }

    @Test
    void allConfigKeys_sayWhereEachAlertKeyIsEdited() {
        List<RawKeyRow> raw = model.view().sections().stream().flatMap(s -> s.items().stream())
                .flatMap(i -> switch (i) {
                    case SettingsItem.RawKeys keys -> keys.rows().stream();
                    default -> Stream.<RawKeyRow>empty();
                }).toList();

        assertAll(
                () -> assertEquals("Event grid · Discord", shownAs(raw,
                        AlertSettingKeys.route(AlertService.DISCORD, AlertKind.SCRIPT_CRASH).name())),
                () -> assertEquals("Slack switch", shownAs(raw, AlertSettingKeys.enabled(AlertService.SLACK).name())),
                () -> assertEquals("ntfy · Topic", shownAs(raw, AlertSettingKeys.NTFY_TOPIC.name())),
                () -> assertEquals("Quiet hours", shownAs(raw, AlertSettingKeys.QUIET_TO.name())),
                () -> assertEquals("Group bursts", shownAs(raw, AlertSettingKeys.BURST_SECONDS.name())));
    }

    private static String shownAs(List<RawKeyRow> raw, String name) {
        return raw.stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow().shownAs();
    }

    // ── Secrets stay out of settings and views ─────────────────────────────

    @Test
    void aSavedSecret_isNeverInTheSettingsFileOrThePage() throws IOException {
        alerts.switchOn(AlertService.DISCORD);

        model.saveSecret(AlertService.DISCORD, AlertsHarness.DISCORD_HOOK);
        alerts.settings.flush();
        SettingsView view = model.view();

        assertAll(
                () -> assertEquals(Optional.of(AlertsHarness.DISCORD_HOOK), alerts.savedSecret(AlertService.DISCORD)),
                () -> assertTrue(secretBox(card(AlertService.DISCORD)).hasSecret()),
                () -> assertFalse(view.toString().contains("not-a-real-token"), "the page never carries a secret"),
                () -> assertFalse(Files.readString(alerts.settings.file()).contains("not-a-real-token"),
                        "the settings file never holds a secret"));
    }

    private static CardField.SecretBox secretBox(SettingsItem.ServiceCard card) {
        return card.fields().stream().flatMap(f -> switch (f) {
            case CardField.SecretBox box -> Stream.of(box);
            default -> Stream.<CardField.SecretBox>empty();
        }).findFirst().orElseThrow();
    }
}
