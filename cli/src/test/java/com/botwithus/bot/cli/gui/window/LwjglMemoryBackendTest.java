package com.botwithus.bot.cli.gui.window;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards {@link LwjglMemoryBackend}: with the FFM backend selected, LWJGL memory
 * operations make no {@code sun.misc.Unsafe} call at all.
 *
 * <p>Each case runs {@link LwjglMemoryProbe} in a JVM of its own with
 * {@code --sun-misc-unsafe-memory-access=debug}, which makes the JDK report
 * every Unsafe memory call, and reads that process's output. The observable is
 * the JDK's own report, not which backend class LWJGL says it picked.</p>
 *
 * <p>{@code deny} would not work as the guard. Under {@code deny} every Unsafe
 * backend fails to construct, so LWJGL 3.4 falls through to FFM on its own, and
 * a missing selection passes unnoticed. That was observed before this test took
 * its present shape.</p>
 *
 * <p>The unselected case is the positive control. It proves the probe and the
 * JDK flag can see an Unsafe call, so a pass in the selected case means
 * something. If a future LWJGL makes FFM its default, the control fails. That
 * is the moment to re-check whether {@link LwjglMemoryBackend} is still needed.</p>
 */
class LwjglMemoryBackendTest {

    private static final long TIMEOUT_SECONDS = 60;
    private static final int EXIT_OK = 0;
    /** The start of every line {@code --sun-misc-unsafe-memory-access=debug} prints. */
    private static final String UNSAFE_REPORT = "WARNING: sun.misc.Unsafe::";

    private record Run(int exitCode, String output) {

        boolean has(String line) {
            return output.lines().anyMatch(line::equals);
        }

        long unsafeCalls() {
            return output.lines().filter(line -> line.startsWith(UNSAFE_REPORT)).count();
        }
    }

    @Test
    void selected_lwjglMemoryWorks_withNoUnsafeCall() throws Exception {
        Run run = fork(LwjglMemoryProbe.SELECTED);

        assertEquals(EXIT_OK, run.exitCode(), run.output());
        assertTrue(run.has(LwjglMemoryProbe.RESULT_OK), run.output());
        assertEquals(0, run.unsafeCalls(), run.output());
    }

    @Test
    void unselected_lwjglDefaultCallsUnsafe_soTheProbeCanSeeIt() throws Exception {
        Run run = fork(LwjglMemoryProbe.UNSELECTED);

        assertEquals(EXIT_OK, run.exitCode(), run.output());
        assertTrue(run.has(LwjglMemoryProbe.RESULT_OK), run.output());
        assertTrue(run.unsafeCalls() > 0, "the control saw no Unsafe call:\n" + run.output());
    }

    private static Run fork(String mode) throws IOException, InterruptedException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = List.of(java.toString(),
                "--sun-misc-unsafe-memory-access=debug",
                "--enable-native-access=ALL-UNNAMED",
                "-cp", System.getProperty("java.class.path"),
                LwjglMemoryProbe.class.getName(), mode);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getOutputStream().close();
        StringBuilder output = new StringBuilder();
        Thread reader = Thread.ofVirtual().start(() -> drain(process.getInputStream(), output));
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail(mode + " probe did not finish in " + TIMEOUT_SECONDS + " s");
        }
        reader.join();
        Run run = new Run(process.exitValue(), output.toString());
        // Kept in the test report as the evidence for each case, pass or fail.
        System.out.println("== probe " + mode + " -> exit " + run.exitCode() + ", unsafe calls "
                + run.unsafeCalls() + System.lineSeparator() + run.output());
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
