package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.cli.settings.HostSettings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "Find a setting" over the real page built from real settings. */
class SettingsFindTest {

    private static final int WINDOWS_PERCENT = 100;
    private static final List<AccountRow> ACCOUNTS = List.of(
            new AccountRow("3f9a1c2e-0000", "Oakheart", List.of("Woodcutting"), true),
            new AccountRow("b71d09e4-0000", "Fernmoss", List.of("Divination"), false));

    @TempDir
    Path dir;

    private HostSettings settings;
    private List<SectionView> sections;

    @BeforeEach
    void setUp() {
        settings = HostSettings.open(dir);
        SettingsSheet.Inputs inputs = new SettingsSheet.Inputs(ACCOUNTS, "~/.botwithus/", "scripts/",
                "~/.botwithus/config.properties", WINDOWS_PERCENT, Optional.empty());
        sections = SettingsSheet.build(settings, settings.status(), inputs).sections();
    }

    @AfterEach
    void tearDown() {
        settings.close();
    }

    private static List<SettingsSection> sectionsOf(List<SectionView> shown) {
        return shown.stream().map(SectionView::section).toList();
    }

    private static List<String> names(SectionView section) {
        return section.items().stream().flatMap(item -> switch (item) {
            case SettingsItem.KeyRow row -> Stream.of(row.name());
            case SettingsItem.RawKeys keys -> keys.rows().stream().map(RawKeyRow::name);
            case SettingsItem.Accounts accounts -> accounts.rows().stream().map(AccountRow::title);
            case SettingsItem.WaitPreview _ -> Stream.of("preview");
            case SettingsItem.PlaceRow place -> Stream.of(place.label());
            case SettingsItem.ActionRow action -> Stream.of(action.label());
            case SettingsItem.ServiceCard card -> Stream.of(card.service().label());
            case SettingsItem.EventGrid grid -> grid.rows().stream().map(GridRow::label);
            case SettingsItem.QuietHours quiet -> Stream.of(quiet.label());
            case SettingsItem.Notice notice -> Stream.of(notice.text());
        }).toList();
    }

    @Test
    void aBlankQueryShowsEverything() {
        assertSame(sections, SettingsFind.filter(sections, "   "));
    }

    @Test
    void aQueryKeepsOnlyMatchingRowsAndNarrowsTheKeyTable() {
        List<SectionView> shown = SettingsFind.filter(sections, "Timeout");

        assertEquals(List.of(SettingsSection.CONNECTING, SettingsSection.ALL_KEYS), sectionsOf(shown));
        assertEquals(List.of("defaultTimeout"), names(shown.get(0)));
        assertEquals(List.of("defaultTimeout"), names(shown.get(1)));
    }

    @Test
    void aSectionTitleMatchKeepsTheWholeSection() {
        List<SectionView> shown = SettingsFind.filter(sections, "reconnecting");

        assertEquals(List.of(SettingsSection.RECONNECTING), sectionsOf(shown));
        assertEquals(sections.get(SettingsSection.RECONNECTING.ordinal()), shown.getFirst());
    }

    @Test
    void theAccountsTableIsNarrowedToMatchingAccounts() {
        List<SectionView> shown = SettingsFind.filter(sections, "fernmoss");

        assertEquals(List.of(SettingsSection.ACCOUNTS), sectionsOf(shown));
        assertEquals(List.of("Fernmoss"), names(shown.getFirst()));
    }

    @Test
    void aKeyNameFindsItsRowEvenWhenTheLabelDiffers() {
        List<SectionView> shown = SettingsFind.filter(sections, "stallafter");

        assertEquals(List.of("scripts.stallAfterMs"), names(shown.getFirst()));
    }

    @Test
    void nothingMatchingLeavesNoSections() {
        assertTrue(SettingsFind.filter(sections, "zzz-no-such-setting").isEmpty());
    }
}
