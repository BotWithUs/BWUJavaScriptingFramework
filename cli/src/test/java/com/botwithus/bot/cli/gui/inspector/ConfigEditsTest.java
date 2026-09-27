package com.botwithus.bot.cli.gui.inspector;

import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigEditsTest {

    private static final ConfigField STOP = ConfigField.intField("stopValue", "Stop value", 90);
    private static final ConfigField DISPOSAL =
            ConfigField.choiceField("disposal", "Disposal", List.of("Bank", "Drop", "Wood box"), "Bank");
    private static final List<ConfigField> FIELDS = List.of(STOP, DISPOSAL);

    private static ScriptConfig config(String stop, String disposal) {
        return new ScriptConfig(Map.of("stopValue", stop, "disposal", disposal));
    }

    private static ScriptConfig only(String key, String value) {
        return new ScriptConfig(Map.of(key, value));
    }

    @Test
    void freshForm_isClean() {
        ConfigEdits edits = new ConfigEdits(FIELDS, config("90", "Bank"));

        assertEquals(0, edits.dirtyCount());
    }

    @Test
    void editingAField_makesOnlyThatFieldDirty() {
        ConfigEdits edits = new ConfigEdits(FIELDS, config("90", "Bank"));
        edits.intOf("stopValue").set(95);

        assertTrue(edits.isDirty(STOP));
        assertFalse(edits.isDirty(DISPOSAL));
        assertEquals(1, edits.dirtyCount());
    }

    @Test
    void sync_untouchedFieldsFollowTheScriptUisSave_editedFieldsKeepTheirEdit() {
        ConfigEdits edits = new ConfigEdits(FIELDS, config("90", "Bank"));
        edits.intOf("stopValue").set(95);

        // The script's own UI saved: stop value 80, disposal Drop.
        edits.sync(config("80", "Drop"));

        assertEquals("95", edits.pending(STOP), "the user's edit survives");
        assertEquals("Drop", edits.pending(DISPOSAL), "the untouched field follows the save");
        assertEquals(1, edits.dirtyCount());
        assertEquals(Map.of("stopValue", "95", "disposal", "Drop"), edits.toConfig().asMap(),
                "Apply must not push back the stale Bank");
    }

    @Test
    void markApplied_makesTheFormCleanWithoutWaitingForTheScript() {
        ConfigEdits edits = new ConfigEdits(FIELDS, config("90", "Bank"));
        edits.intOf("stopValue").set(95);

        edits.markApplied();

        assertEquals(0, edits.dirtyCount());
        assertEquals("95", edits.pending(STOP));
    }

    @Test
    void revert_dropsEditsBackToTheAppliedValues() {
        ConfigEdits edits = new ConfigEdits(FIELDS, config("90", "Bank"));
        edits.intOf("stopValue").set(95);
        edits.intOf("disposal").set(2);

        edits.revert();

        assertEquals(0, edits.dirtyCount());
        assertEquals("90", edits.pending(STOP));
        assertEquals("Bank", edits.pending(DISPOSAL));
    }

    @Test
    void noAppliedConfig_seedsFromFieldDefaults() {
        ConfigEdits edits = new ConfigEdits(FIELDS, null);

        assertEquals("90", edits.pending(STOP));
        assertEquals("Bank", edits.pending(DISPOSAL));
        assertEquals(0, edits.dirtyCount());
    }

    /**
     * The old floating editor's "Reset" put every field back to its declared
     * default; the inspector's Reset only drops unapplied edits. Restore
     * defaults keeps the old ability, as an edit that still needs Apply.
     */
    @Test
    void restoreDefaults_setsEveryFieldToItsDeclaredDefault_asPendingEdits() {
        ConfigEdits edits = new ConfigEdits(FIELDS, config("40", "Drop"));
        assertFalse(edits.isAtDefaults());

        edits.restoreDefaults();

        assertAll(
                () -> assertEquals("90", edits.pending(STOP)),
                () -> assertEquals("Bank", edits.pending(DISPOSAL)),
                () -> assertTrue(edits.isAtDefaults()),
                () -> assertEquals(2, edits.dirtyCount(), "not applied until Apply"),
                () -> assertEquals(Map.of("stopValue", "90", "disposal", "Bank"), edits.toConfig().asMap()));
    }

    /**
     * One case per {@link ConfigField} variant: each is seeded from the applied
     * config, falls back to its default, is edited through its own typed
     * wrapper, and round-trips to the string the config store writes. Together
     * they cover everything the retired floating editors could edit.
     */
    @Nested
    class EveryFieldType {

        private static final List<String> JITTERS = List.of("None", "Light", "Heavy");

        @Test
        void intField_editsAsAnInt() {
            ConfigField field = ConfigField.intField("breakEvery", "Break every", 90);
            ConfigEdits edits = new ConfigEdits(List.of(field), only("breakEvery", "45"));
            assertEquals(45, edits.intOf("breakEvery").get());

            edits.intOf("breakEvery").set(60);

            assertAll(
                    () -> assertTrue(edits.isDirty(field)),
                    () -> assertEquals(Map.of("breakEvery", "60"), edits.toConfig().asMap()),
                    () -> assertEquals(90, new ConfigEdits(List.of(field), null).intOf("breakEvery").get()),
                    () -> assertEquals(90, new ConfigEdits(List.of(field), only("breakEvery", "x"))
                            .intOf("breakEvery").get(), "an unparseable saved value falls back to the default"));
        }

        @Test
        void itemIdField_editsAsAnInt() {
            ConfigField field = ConfigField.itemIdField("logId", "Log item id", 1515);
            ConfigEdits edits = new ConfigEdits(List.of(field), only("logId", "1521"));
            assertEquals(1521, edits.intOf("logId").get());

            edits.intOf("logId").set(1513);

            assertAll(
                    () -> assertTrue(edits.isDirty(field)),
                    () -> assertEquals(Map.of("logId", "1513"), edits.toConfig().asMap()),
                    () -> assertEquals(1515, new ConfigEdits(List.of(field), null).intOf("logId").get()));
        }

        @Test
        void stringField_editsAsText() {
            ConfigField field = ConfigField.stringField("quietHours", "Quiet hours", "23:00-07:00");
            ConfigEdits edits = new ConfigEdits(List.of(field), only("quietHours", "22:00-06:00"));
            assertEquals("22:00-06:00", edits.stringOf("quietHours").get());

            edits.stringOf("quietHours").set("");

            assertAll(
                    () -> assertTrue(edits.isDirty(field)),
                    () -> assertEquals(Map.of("quietHours", ""), edits.toConfig().asMap()),
                    () -> assertEquals("23:00-07:00",
                            new ConfigEdits(List.of(field), null).stringOf("quietHours").get()));
        }

        @Test
        void boolField_editsAsAToggle() {
            ConfigField field = ConfigField.boolField("logOut", "Log out during breaks", true);
            ConfigEdits edits = new ConfigEdits(List.of(field), only("logOut", "false"));
            assertFalse(edits.boolOf("logOut").get());

            edits.boolOf("logOut").set(true);

            assertAll(
                    () -> assertTrue(edits.isDirty(field)),
                    () -> assertEquals(Map.of("logOut", "true"), edits.toConfig().asMap()),
                    () -> assertTrue(new ConfigEdits(List.of(field), null).boolOf("logOut").get()));
        }

        @Test
        void choiceField_editsByIndex_andAppliesTheChoiceText() {
            ConfigField field = ConfigField.choiceField("jitter", "Jitter", JITTERS, "Light");
            ConfigEdits edits = new ConfigEdits(List.of(field), only("jitter", "Heavy"));
            assertEquals(2, edits.intOf("jitter").get());

            edits.intOf("jitter").set(0);

            assertAll(
                    () -> assertTrue(edits.isDirty(field)),
                    () -> assertEquals(Map.of("jitter", "None"), edits.toConfig().asMap()),
                    () -> assertEquals(1, new ConfigEdits(List.of(field), null).intOf("jitter").get()),
                    () -> assertEquals(0, new ConfigEdits(List.of(field), only("jitter", "Gone"))
                            .intOf("jitter").get(), "a saved choice no longer offered shows the first"));
        }

        @Test
        void outOfRangeChoiceIndex_appliesTheDefaultRatherThanNothing() {
            ConfigField field = ConfigField.choiceField("jitter", "Jitter", JITTERS, "Light");
            ConfigEdits edits = new ConfigEdits(List.of(field), null);

            edits.intOf("jitter").set(JITTERS.size());

            assertEquals(Map.of("jitter", "Light"), edits.toConfig().asMap());
        }
    }
}
