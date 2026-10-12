package com.botwithus.bot.cli.gui.scriptui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Each {@link Breaker} in a JVM of its own, with and without the guard. The
 * process exit code is the observable, because the failure being guarded
 * against is imgui-java calling {@code System.exit(1)}; an in-process test
 * could not survive its own negative case.
 *
 * <p>A failure here means: a breaker that kills the process with the guard on,
 * a fatal breaker that does <em>not</em> kill it without the guard (the test
 * would not be reaching the assertion it claims to), a hang past the timeout,
 * or a guarded run that did not stop the broken UI and keep the good one drawing.</p>
 */
class ScriptUiGuardForkTest {

    private static final long TIMEOUT_SECONDS = 60;
    private static final int EXIT_OK = 0;
    /** What imgui-java's {@code ImAssertCallback.imAssert} passes to {@code System.exit}. */
    private static final int EXIT_IMGUI_ASSERT = 1;
    /** {@code STATUS_HEAP_CORRUPTION}, 0xC0000374, as Windows reports it for a process. */
    private static final int EXIT_HEAP_CORRUPTION = 0xC0000374;

    private record Run(int exitCode, String output) {

        boolean has(String line) {
            return output.lines().anyMatch(line::equals);
        }

        boolean contains(String text) {
            return output.contains(text);
        }
    }

    @ParameterizedTest
    @EnumSource(Breaker.class)
    void guarded_breakerIsContained_andTheGoodUiKeepsDrawing(Breaker breaker) throws Exception {
        Run run = fork(breaker, BreakerMain.GUARDED);

        assertEquals(EXIT_OK, run.exitCode(), run.output());
        assertTrue(run.has("RESULT frames=" + BreakerMain.FRAMES), run.output());
        assertTrue(run.has("RESULT good_frames=" + BreakerMain.FRAMES), run.output());
        assertTrue(run.has("RESULT good_stopped=false"), run.output());
        if (breaker.isFatalToday()) {
            assertTrue(run.has("RESULT stopped=true"), run.output());
            assertTrue(run.has("RESULT failures=1"), run.output());
            assertTrue(run.contains("RESULT assertion="), run.output());
            assertTrue(run.contains("[script-ui client/test/breaker #1]"), run.output());
        } else {
            assertTrue(run.has("RESULT stopped=false"), run.output());
            assertTrue(run.has("RESULT failures=0"), run.output());
            // Breaker.ui() is a new object every frame; it must still be one sandbox, not one per frame.
            assertTrue(run.contains("[script-ui client/test/breaker #1]"), run.output());
        }
        assertTrue(run.contains("[script-ui client/test/good #1]"), run.output());
    }

    @ParameterizedTest
    @EnumSource(Breaker.class)
    void unguarded_fatalBreakerKillsTheProcess_asOnMaster(Breaker breaker) throws Exception {
        Run run = fork(breaker, BreakerMain.UNGUARDED);

        if (breaker.isFatalToday()) {
            assertEquals(EXIT_IMGUI_ASSERT, run.exitCode(), run.output());
            assertTrue(run.contains("Dear ImGui Assertion Failed"), run.output());
            assertFalse(run.contains("RESULT frames="), run.output());
        } else {
            assertEquals(EXIT_OK, run.exitCode(), run.output());
            assertTrue(run.has("RESULT frames=" + BreakerMain.FRAMES), run.output());
        }
    }

    /**
     * Why {@code HostIni} exists, pinned: the live host crashed this way before
     * it did. If imgui-java ever stops freeing through a process-wide flag, this
     * fails, and {@code HostIni} can be reconsidered.
     */
    @Test
    void anIniPathSetThroughImGuiIo_makesTheFirstSandboxCorruptTheHeap() throws Exception {
        Run run = fork(Breaker.TEXT_WRAP_LEAK, BreakerMain.INI_VIA_IO);

        assertEquals(EXIT_HEAP_CORRUPTION, run.exitCode(), run.output());
        assertFalse(run.contains("RESULT frames="), run.output());
    }

    private static Run fork(Breaker breaker, String mode) throws IOException, InterruptedException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = List.of(java.toString(), "--enable-native-access=ALL-UNNAMED",
                "-cp", System.getProperty("java.class.path"), BreakerMain.class.getName(), breaker.name(), mode);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getOutputStream().close();
        StringBuilder output = new StringBuilder();
        Thread reader = Thread.ofVirtual().start(() -> drain(process.getInputStream(), output));
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail(breaker + " " + mode + " did not finish in " + TIMEOUT_SECONDS + " s");
        }
        reader.join();
        Run run = new Run(process.exitValue(), output.toString());
        // Kept in the test report as the evidence for each case, pass or fail.
        System.out.println("== " + breaker + " " + mode + " -> exit " + run.exitCode()
                + System.lineSeparator() + run.output());
        return run;
    }

    private static void drain(InputStream in, StringBuilder into) {
        try {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            synchronized (into) {
                into.append(text);
            }
        } catch (IOException e) {
            synchronized (into) {
                into.append("<<output lost: ").append(e).append(">>");
            }
        }
    }
}
