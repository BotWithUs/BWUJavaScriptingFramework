package com.botwithus.bot.scripts.example;

import com.botwithus.bot.api.BotScript;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Trivial linkage / compile smoke test for the example scripts.
 *
 * <p>Verifies each bundled script class instantiates with a no-arg
 * constructor, is assignable to {@link BotScript}, and that the
 * config-field / lifecycle defaults don't blow up when called without
 * a live {@code ScriptContext}. The point is to catch wholesale API
 * drift early — a method renamed on {@link BotScript} that an example
 * script still calls would break compilation, and a missing default
 * impl on a script would break instantiation.</p>
 */
class ExampleScriptCompileTest {

    @Test
    void exampleScript_instantiates() {
        BotScript script = new ExampleScript();
        assertAll(
                () -> assertNotNull(script),
                () -> assertNotNull(script.getConfigFields()));
    }

    @Test
    void woodcuttingFletcherScript_instantiates() {
        BotScript script = new WoodcuttingFletcherScript();
        assertAll(
                () -> assertNotNull(script),
                () -> assertNotNull(script.getConfigFields()));
    }

    @Test
    void walkToFlagScript_instantiates() {
        BotScript script = new WalkToFlagScript();
        assertAll(
                () -> assertNotNull(script),
                () -> assertNotNull(script.getConfigFields()));
    }

    @Test
    void divinationScript_instantiates() {
        BotScript script = new DivinationScript();
        assertAll(
                () -> assertNotNull(script),
                () -> assertNotNull(script.getConfigFields()));
    }
}
