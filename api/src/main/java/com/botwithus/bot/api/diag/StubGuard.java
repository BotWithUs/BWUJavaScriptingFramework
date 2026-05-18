package com.botwithus.bot.api.diag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Warns once per method-name when a producer-side stub is invoked.
 *
 * <p>Producer-backed API surface (entity queries that depend on producer-side
 * iteration the C++ side hasn't shipped yet) typically returns an empty list
 * without telling the caller. {@code StubGuard} closes that gap: the first
 * time {@link #warnOnce(String)} is called for a given method name the
 * configured sink fires; subsequent calls for the same name are suppressed.
 *
 * <p>The {@code warned} set is an instance field (per-{@code StubGuard}
 * rather than process-global) — callers that want independent throttling
 * just hold independent instances.
 */
public final class StubGuard {

    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private final Consumer<String> sink;

    /**
     * Default sink — logs at WARN to the {@code StubGuard} logger. Useful for
     * production wiring where the warning should appear in normal log output.
     */
    public StubGuard() {
        this(defaultSink());
    }

    /**
     * Custom sink — receives the method name on first call only. Useful for
     * tests (collect into a {@link java.util.List}) or for integration with
     * an event bus.
     */
    public StubGuard(Consumer<String> sink) {
        if (sink == null) {
            throw new IllegalArgumentException("sink");
        }
        this.sink = sink;
    }

    /**
     * Fires the sink the first time {@code methodName} is observed; suppresses
     * subsequent calls for the same name. Thread-safe.
     */
    public void warnOnce(String methodName) {
        if (warned.add(methodName)) {
            sink.accept(methodName);
        }
    }

    private static Consumer<String> defaultSink() {
        Logger log = LoggerFactory.getLogger(StubGuard.class);
        return name -> log.warn("Unimplemented producer call: {}", name);
    }
}
