package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.config.ScriptConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Writable settings and their one map with the runner's config. Failure meaning: a UI apply
 * that drops a key the script stored itself, a save that writes something other than the
 * current map, or a runner whose current config is not its settings.
 */
class RunnerSettingsTest {

    @Test
    void putGetRemove_andTypedAccessors() {
        RunnerSettings settings = new RunnerSettings();

        settings.put("lastTree", "Yew");
        settings.putInt("tripCount", 41);
        settings.putBoolean("banking", true);

        assertEquals(Optional.of("Yew"), settings.get("lastTree"));
        assertEquals(41, settings.getInt("tripCount", -1));
        assertTrue(settings.getBoolean("banking", false));
        assertTrue(settings.remove("lastTree"));
        assertFalse(settings.remove("lastTree"));
        assertEquals(Map.of("tripCount", "41", "banking", "true"), settings.asMap());
        assertThrows(IllegalArgumentException.class, () -> settings.put("x", null));
    }

    @Test
    void merge_appliedFieldsWin_andTheScriptsOwnKeysSurvive() {
        RunnerSettings settings = new RunnerSettings();
        settings.replace(new ScriptConfig(Map.of("breakEvery", "60", "tripCount", "7")));

        ScriptConfig merged = settings.merge(new ScriptConfig(Map.of("breakEvery", "45")));

        assertEquals(Map.of("breakEvery", "45", "tripCount", "7"), merged.asMap());
        assertEquals(merged, settings.current());
    }

    @Test
    void save_writesTheCurrentMapThroughThePersister() {
        RunnerSettings settings = new RunnerSettings();
        List<ScriptConfig> saved = new ArrayList<>();
        settings.setPersister(saved::add);
        settings.put("tripCount", "3");

        settings.save();

        assertEquals(List.of(new ScriptConfig(Map.of("tripCount", "3"))), saved);
    }

    @Test
    void runner_currentConfigIsItsSettings_andApplyKeepsStoredKeys() {
        ScriptRunner runner = new ScriptRunner(mock(BotScript.class), mock(ScriptContext.class));
        RunnerSettings settings = new RunnerSettings();
        runner.setSettings(settings);
        settings.put("tripCount", "9");

        runner.applyConfig(new ScriptConfig(Map.of("breakEvery", "30")));

        assertEquals(Map.of("tripCount", "9", "breakEvery", "30"), runner.getCurrentConfig().asMap());
        assertEquals(runner.getCurrentConfig(), settings.current());
    }
}
