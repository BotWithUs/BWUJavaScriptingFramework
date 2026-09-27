package com.botwithus.bot.cli.groups;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.core.runtime.ScriptRuntime;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Starts what the {@link StartWhenBackQueue} holds for a client once it is back.
 *
 * <p>The host calls {@link #clientBack} every time it reads the account of a
 * client, so a queued start happens when the client is identified on a new
 * pipe, when its pipe recovers from a drop, and, for a script that was not
 * installed at those moments, on a later read once it is.</p>
 *
 * <p>A start is dequeued once the script has been started on the client, or was
 * already running there. A script that is not installed stays queued.</p>
 */
public final class StartWhenBackDrain {

    private static final String PREFIX = "[StartWhenBack] ";

    private final StartWhenBackQueue queue;
    private final Function<String, Optional<ScriptRuntime>> runtimeOnPipe;
    private final Supplier<List<BotScript>> loadScripts;
    private final Executor executor;
    private final Consumer<String> report;

    /**
     * @param runtimeOnPipe the script runtime of the live connection on a pipe
     * @param loadScripts   loads the installed scripts; called only when a
     *                      queued script is not registered on the client yet
     * @param executor      runs each drain; it may block while scripts load
     * @param report        told, in words for the user, what a drain did
     */
    public StartWhenBackDrain(StartWhenBackQueue queue, Function<String, Optional<ScriptRuntime>> runtimeOnPipe,
                              Supplier<List<BotScript>> loadScripts, Executor executor, Consumer<String> report) {
        this.queue = queue;
        this.runtimeOnPipe = runtimeOnPipe;
        this.loadScripts = loadScripts;
        this.executor = executor;
        this.report = report;
    }

    /**
     * The client on {@code pipe} was read and is on account {@code accountUuid}.
     * Drains what is queued for it on the executor. Returns at once, and does
     * nothing when nothing is queued for the account.
     */
    public void clientBack(String pipe, String accountUuid) {
        if (queue.forAccount(accountUuid).isEmpty()) {
            return;
        }
        executor.execute(() -> drain(pipe, accountUuid));
    }

    /**
     * Starts every script queued for {@code accountUuid} on the client on
     * {@code pipe}, now, on this thread.
     *
     * @return the starts that were made and dequeued
     */
    public List<QueuedStart> drain(String pipe, String accountUuid) {
        List<QueuedStart> pending = queue.forAccount(accountUuid);
        Optional<ScriptRuntime> runtime = pending.isEmpty() ? Optional.empty() : runtimeOnPipe.apply(pipe);
        if (runtime.isEmpty()) {
            return List.of();
        }
        Supplier<List<BotScript>> scripts = once(loadScripts);
        List<QueuedStart> started = new ArrayList<>();
        for (QueuedStart entry : pending) {
            Optional<ScriptRunner> runner = findOrRegister(runtime.get(), entry.script(), scripts);
            if (runner.isEmpty()) {
                report.accept(PREFIX + entry.script() + " is not installed; still waiting to start it on "
                        + pipe + ".");
                continue;
            }
            runner.get().start();
            queue.dequeue(accountUuid, entry.script());
            started.add(entry);
            report.accept(PREFIX + "Started " + runner.get().getScriptName() + " on " + pipe + ".");
        }
        return started;
    }

    private static Optional<ScriptRunner> findOrRegister(ScriptRuntime runtime, String script,
                                                         Supplier<List<BotScript>> scripts) {
        ScriptRunner runner = runtime.findRunner(script);
        if (runner != null) {
            return Optional.of(runner);
        }
        scripts.get().forEach(runtime::registerScript);
        return Optional.ofNullable(runtime.findRunner(script));
    }

    /** {@code source}, asked at most once however often the result is. */
    private static Supplier<List<BotScript>> once(Supplier<List<BotScript>> source) {
        List<List<BotScript>> loaded = new ArrayList<>(1);
        return () -> {
            if (loaded.isEmpty()) {
                loaded.add(source.get());
            }
            return loaded.getFirst();
        };
    }
}
