package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.cli.settings.AlertSettingKeys;
import com.botwithus.bot.core.alerts.AlertKind;
import com.botwithus.bot.core.alerts.AlertService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "Find a setting" over the Integrations section built by the live model. */
class IntegrationsFindTest {

    @TempDir
    Path dir;

    private AlertsHarness alerts;
    private List<SectionView> sections;

    @BeforeEach
    void setUp() {
        alerts = new AlertsHarness(dir.resolve("data"), true);
        sections = alerts.model(dir).view().sections();
    }

    @AfterEach
    void tearDown() {
        alerts.close();
    }

    private Optional<SectionView> integrations(String query) {
        return SettingsFind.filter(sections, query).stream()
                .filter(s -> s.section() == SettingsSection.INTEGRATIONS).findFirst();
    }

    /** What is left of the section: service ids, "grid:" with its rows, row names. */
    private List<String> shape(String query) {
        return integrations(query).orElseThrow().items().stream().flatMap(i -> switch (i) {
            case SettingsItem.ServiceCard card -> Stream.of(card.service().id());
            case SettingsItem.EventGrid grid -> grid.rows().stream().map(r -> "grid:" + r.kind().id());
            case SettingsItem.KeyRow row -> Stream.of(row.name());
            case SettingsItem.QuietHours _ -> Stream.of("quiet");
            default -> Stream.of("other");
        }).toList();
    }

    @Test
    void aServiceName_keepsItsCardAndTheGridWithItsColumn() {
        List<String> shape = shape("discord");

        assertEquals("discord", shape.getFirst());
        assertEquals(AlertKind.values().length, shape.stream().filter(s -> s.startsWith("grid:")).count());
        assertTrue(shape.stream().noneMatch(s -> s.equals("ntfy") || s.equals("slack")));
    }

    @Test
    void anAlertKind_narrowsTheGridToItsRow() {
        assertEquals(List.of("grid:" + AlertKind.SCRIPT_STALL.id()), shape("script stalls"));
    }

    @Test
    void aKeyName_findsTheCardThatEditsIt() {
        assertEquals(List.of("ntfy"), shape(AlertSettingKeys.NTFY_TOPIC.name()));
        assertEquals(List.of("discord"), shape(AlertSettingKeys.DISCORD_MENTION_HERE.name()));
        assertEquals(List.of("slack"), shape(AlertSettingKeys.enabled(AlertService.SLACK).name()));
    }

    @Test
    void aFieldLabel_findsTheCardsThatAskForIt() {
        assertEquals(List.of("slack", "discord"), shape("webhook url"));
    }

    @Test
    void quietHours_andTheirKeys_findTheirRow() {
        assertEquals(List.of("quiet", AlertSettingKeys.QUIET_MODE.name()), shape("quiet"));
        assertEquals(List.of("quiet"), shape(AlertSettingKeys.QUIET_FROM.name()));
    }

    @Test
    void theQuietHoursMode_isFoundByWhatItDoes() {
        assertEquals(List.of(AlertSettingKeys.QUIET_MODE.name()), shape("send them when quiet hours end"));
    }

    @Test
    void theSectionTitle_keepsEverything() {
        SectionView whole = sections.stream().filter(s -> s.section() == SettingsSection.INTEGRATIONS)
                .findFirst().orElseThrow();

        assertEquals(Optional.of(whole), integrations("integrations"));
    }

    @Test
    void anUnrelatedWord_dropsTheSection() {
        assertEquals(Optional.empty(), integrations("zzz-no-such-setting"));
        assertEquals(Optional.empty(), integrations("timeout"));
    }
}
