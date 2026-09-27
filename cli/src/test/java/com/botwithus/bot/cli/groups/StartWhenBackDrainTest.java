package com.botwithus.bot.cli.groups;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.Navigation;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.core.runtime.ScriptRuntime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Drains a real queue into a real script runtime; the scripts are trivial and
 * the script context is a mock.
 */
class StartWhenBackDrainTest {

    private static final String UUID_A = "0123456789abcdef0123456789abcdef";
    private static final String PIPE = "BotWithUs_3003";
    private static final Instant AT = Instant.parse("2026-09-01T10:00:00Z");
    private static final int LOOP_MS = 50;

    @ScriptManifest(name = "Woodcutter", version = "1.0", author = "test")
    public static final class Woodcutter implements BotScript {
        @Override public void onStart(ScriptContext ctx) { }
        @Override public int onLoop() { return LOOP_MS; }
        @Override public void onStop() { }
    }

    @ScriptManifest(name = "Fletcher", version = "1.0", author = "test")
    public static final class Fletcher implements BotScript {
        @Override public void onStart(ScriptContext ctx) { }
        @Override public int onLoop() { return LOOP_MS; }
        @Override public void onStop() { }
    }

    @TempDir
    Path dir;

    private final ScriptRuntime runtime = runtime();
    private final List<String> reports = new CopyOnWriteArrayList<>();
    private final AtomicInteger loads = new AtomicInteger();
    private final List<Runnable> executed = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopScripts() {
        runtime.stopAll();
    }

    private StartWhenBackQueue queue() {
        return new StartWhenBackQueue(dir.resolve(StartWhenBackQueue.FILE_NAME));
    }

    private StartWhenBackDrain drain(StartWhenBackQueue queue, List<BotScript> installed) {
        Supplier<List<BotScript>> loader = () -> {
            loads.incrementAndGet();
            return installed;
        };
        Map<String, ScriptRuntime> live = Map.of(PIPE, runtime);
        return new StartWhenBackDrain(queue, pipe -> Optional.ofNullable(live.get(pipe)), loader,
                executed::add, reports::add);
    }

    @Test
    void aRegisteredScript_isStarted_andDequeued_withoutLoadingScripts() {
        runtime.registerScript(new Woodcutter());
        StartWhenBackQueue queue = queue();
        queue.enqueue(UUID_A, "woodcutter", AT);

        List<QueuedStart> started = drain(queue, List.of()).drain(PIPE, UUID_A);

        assertEquals(List.of(new QueuedStart(UUID_A, "woodcutter", AT)), started);
        assertTrue(runtime.findRunner("Woodcutter").isRunning());
        assertTrue(queue.all().isEmpty());
        assertEquals(0, loads.get());
    }

    @Test
    void aScriptNotRegisteredYet_isLoadedOnce_forEveryStart() {
        StartWhenBackQueue queue = queue();
        queue.enqueue(UUID_A, "Woodcutter", AT);
        queue.enqueue(UUID_A, "Fletcher", AT);

        drain(queue, List.of(new Woodcutter(), new Fletcher())).drain(PIPE, UUID_A);

        assertTrue(runtime.findRunner("Woodcutter").isRunning());
        assertTrue(runtime.findRunner("Fletcher").isRunning());
        assertEquals(1, loads.get());
        assertTrue(queue.all().isEmpty());
    }

    @Test
    void aScriptThatIsNotInstalled_staysQueued_andIsReported() {
        StartWhenBackQueue queue = queue();
        queue.enqueue(UUID_A, "Miner", AT);

        List<QueuedStart> started = drain(queue, List.of(new Woodcutter())).drain(PIPE, UUID_A);

        assertTrue(started.isEmpty());
        assertEquals(List.of(new QueuedStart(UUID_A, "Miner", AT)), queue.all());
        assertTrue(reports.stream().anyMatch(line -> line.contains("Miner") && line.contains("not installed")),
                reports::toString);
    }

    @Test
    void aClientWithNoLiveRuntime_startsNothing() {
        StartWhenBackQueue queue = queue();
        queue.enqueue(UUID_A, "Woodcutter", AT);

        assertTrue(drain(queue, List.of(new Woodcutter())).drain("BotWithUs_9", UUID_A).isEmpty());

        assertEquals(1, queue.all().size());
    }

    @Test
    void clientBack_hasTheExecutorDrain_onlyWhenSomethingIsQueued() {
        StartWhenBackQueue queue = queue();
        StartWhenBackDrain drain = drain(queue, List.of(new Woodcutter()));

        drain.clientBack(PIPE, UUID_A);
        assertTrue(executed.isEmpty(), "nothing queued, so nothing to run");

        queue.enqueue(UUID_A, "Woodcutter", AT);
        drain.clientBack(PIPE, UUID_A);
        assertEquals(1, executed.size());
        executed.getFirst().run();
        assertTrue(queue.all().isEmpty());
    }

    private static ScriptRuntime runtime() {
        ScriptContext context = mock(ScriptContext.class);
        when(context.getNavigation()).thenReturn(mock(Navigation.class));
        ScriptRuntime runtime = new ScriptRuntime(context);
        runtime.setConnectionName(PIPE);
        return runtime;
    }
}
