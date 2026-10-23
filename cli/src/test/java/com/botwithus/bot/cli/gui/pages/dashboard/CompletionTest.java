package com.botwithus.bot.cli.gui.pages.dashboard;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tab at the console prompt: the behaviour the old console panel had, kept. */
class CompletionTest {

    private static final List<String> NAMES = List.of("reload", "rl", "screenshot", "scripts", "stream");

    @Test
    void oneMatch_completesToIt_withASpaceToTypeArgumentsAfter() {
        assertEquals("reload ", Completion.complete("rel", NAMES).text());
    }

    @Test
    void severalMatches_completeToTheirCommonPrefix_andAreListed() {
        Completion.Result result = Completion.complete("s", NAMES);

        assertEquals("s", result.text());
        assertEquals(List.of("screenshot", "scripts", "stream"), result.matches());
        assertEquals("scr", Completion.complete("sc", NAMES).text());
    }

    @Test
    void noMatch_orNothingTyped_leavesThePromptAlone() {
        assertEquals("zzz", Completion.complete("zzz", NAMES).text());
        assertEquals("", Completion.complete("", NAMES).text());
    }
}
