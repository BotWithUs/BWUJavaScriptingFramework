package com.botwithus.bot.cli;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.cli.events.HostEvent;
import com.botwithus.bot.cli.log.LogBuffer;
import com.botwithus.bot.cli.log.LogCapture;
import com.botwithus.bot.core.runtime.LoadReport;
import com.botwithus.bot.core.runtime.ScriptFolder;
import com.botwithus.bot.core.runtime.ScriptLoadResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every load pass feeds the failed-load list, which lives alongside the host bus rather than on it. */
class CliContextLoadIssuesTest {

    private static final Duration BUS_WAIT = Duration.ofSeconds(5);

    @TempDir
    Path tmp;

    private CliContext ctx;

    @ScriptManifest(name = "Fixed")
    static final class Fixed implements BotScript {
        @Override public void onStart(ScriptContext context) { }
        @Override public int onLoop() { return -1; }
        @Override public void onStop() { }
    }

    @BeforeEach
    void setUp() {
        PrintStream ps = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        LogBuffer logBuffer = new LogBuffer();
        ctx = new CliContext(logBuffer, new LogCapture(logBuffer, ps, ps), tmp.resolve("groups.json"));
    }

    @Test
    void aFailedPass_isListed_andAPassThatLoadsTheJar_clearsIt() throws Exception {
        Path jar = Files.write(tmp.resolve("script.jar"), new byte[0]);
        List<HostEvent> published = new CopyOnWriteArrayList<>();
        ctx.getHostEvents().subscribe(published::add);

        ctx.recordLoadReport(new LoadReport(List.of(
                ScriptLoadResult.failure(jar, new IllegalStateException("boom"), List.of()))));

        assertEquals(1, ctx.getLoadIssues().issues(ScriptFolder.SCRIPTS).size());
        assertTrue(ctx.getHostEvents().flush(BUS_WAIT));
        assertEquals(1, published.size());
        assertInstanceOf(HostEvent.ScriptLoadFailed.class, published.getFirst(), "the host bus still hears about it");

        ctx.recordLoadReport(new LoadReport(List.of(ScriptLoadResult.success(jar, new Fixed(), List.of()))));

        assertTrue(ctx.getLoadIssues().issues().isEmpty());
    }
}
