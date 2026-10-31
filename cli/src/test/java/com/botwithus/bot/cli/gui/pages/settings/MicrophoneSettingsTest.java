package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.core.voice.MicrophoneDecision;
import com.botwithus.bot.core.voice.StoredConsent;
import com.botwithus.bot.core.voice.VoiceConsent;
import com.botwithus.bot.core.voice.VoiceConsentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Settings list of remembered microphone decisions, and its Reset. */
class MicrophoneSettingsTest {

    private static final int WINDOWS_PERCENT = 100;
    private static final String WOODCUTTER = "wc/com.example.Woodcutter";
    private static final String FISHER = "fish/com.example.Fisher";

    @TempDir
    Path dir;

    private final VoiceConsentStore store = VoiceConsentStore.inMemory();
    private final MicrophoneDecisions decisions = MicrophoneDecisions.of(new VoiceConsent(store));

    MicrophoneSettingsTest() {
        store.save(new StoredConsent(WOODCUTTER, "Woodcutter", MicrophoneDecision.ALLOWED));
        store.save(new StoredConsent(FISHER, "Fisher", MicrophoneDecision.DENIED));
    }

    private List<SectionView> sections() {
        try (HostSettings settings = HostSettings.open(dir)) {
            SettingsSheet.Inputs inputs = new SettingsSheet.Inputs(List.of(), "~/.botwithus/", "scripts/",
                    "~/.botwithus/config.properties", WINDOWS_PERCENT, Optional.empty(), Optional.empty(),
                    decisions.rows());
            return SettingsSheet.build(settings, settings.status(), inputs).sections();
        }
    }

    private static List<SettingsItem> scripts(List<SectionView> sections) {
        return sections.stream().filter(s -> s.section() == SettingsSection.SCRIPTS)
                .findFirst().orElseThrow().items();
    }

    @Test
    void rows_listEveryDecision_inNameOrder() {
        assertEquals(List.of(new MicrophoneRow(FISHER, "Fisher", false),
                new MicrophoneRow(WOODCUTTER, "Woodcutter", true)), decisions.rows());
    }

    @Test
    void reset_forgetsOnlyThatScript() {
        decisions.reset(WOODCUTTER);

        assertEquals(List.of(FISHER), store.all().stream().map(StoredConsent::key).toList());
    }

    @Test
    void theScriptsSection_endsWithTheDecisions() {
        assertEquals(new SettingsItem.Microphone(decisions.rows()), scripts(sections()).getLast());
    }

    @Test
    void find_narrowsTheListToMatchingScripts() {
        List<SectionView> found = SettingsFind.filter(sections(), "fisher");

        assertEquals(List.of(new SettingsItem.Microphone(List.of(new MicrophoneRow(FISHER, "Fisher", false)))),
                scripts(found));
    }

    @Test
    void find_byMicrophone_showsTheWholeList() {
        List<SectionView> found = SettingsFind.filter(sections(), "microphone");

        assertEquals(List.of(new SettingsItem.Microphone(decisions.rows())), scripts(found));
    }
}
