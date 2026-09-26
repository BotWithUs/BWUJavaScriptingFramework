package com.botwithus.bot.cli.gui.usermode;

import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
}
