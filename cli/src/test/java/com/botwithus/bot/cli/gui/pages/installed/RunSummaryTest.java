package com.botwithus.bot.cli.gui.pages.installed;

import com.botwithus.bot.cli.gui.pages.installed.RunSummary.Part;
import com.botwithus.bot.cli.gui.pages.installed.RunSummary.Tone;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.botwithus.bot.cli.gui.pages.installed.Rows.run;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The words beside a row's dots. */
class RunSummaryTest {

    @Test
    void countsRunningOutOfEveryClient_thenNamesTheStalled() {
        RunSummary s = RunSummary.of(List.of(run("A", RunnerState.RUNNING), run("B", RunnerState.RUNNING),
                run("C", RunnerState.STALLED), run("D", RunnerState.STOPPED)));

        assertEquals("2 of 4 running · 1 stalled", s.text());
        assertEquals(List.of(new Part("2", Tone.STRONG), new Part(" of 4 running", Tone.PLAIN),
                new Part(" · ", Tone.PLAIN), new Part("1 stalled", Tone.WARN)), s.parts());
    }

    @Test
    void crashedAndCutOff_areRed_andEachNamedOnce() {
        RunSummary s = RunSummary.of(List.of(run("A", RunnerState.CRASHED), run("B", RunnerState.CUT_OFF),
                run("C", RunnerState.CRASHED), run("D", RunnerState.OFFLINE)));

        assertEquals("0 of 4 running · 2 crashed · 1 cut off", s.text());
        assertEquals(new Part("2 crashed", Tone.DANGER), s.parts().get(3));
        assertEquals(new Part("1 cut off", Tone.DANGER), s.parts().get(5));
    }

    @Test
    void noClients_saysSo() {
        assertEquals(RunSummary.NOWHERE, RunSummary.of(List.of()).text());
    }

    @Test
    void aClean_run_hasNoTrailingParts() {
        assertEquals("1 of 1 running", RunSummary.of(List.of(run("A", RunnerState.RUNNING))).text());
    }
}
