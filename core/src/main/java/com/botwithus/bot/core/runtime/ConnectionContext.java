package com.botwithus.bot.core.runtime;

/**
 * Thread-local holder for the connection name associated with the current thread.
 * Uses {@link InheritableThreadLocal} so virtual threads spawned from a tagged
 * parent automatically inherit the connection name.
 *
 * <p>This is a <em>request-context</em> seam, not a singleton service: the value
 * is per-thread, not shared, and the static read API is the only way to expose
 * the tag to code (such as the CLI's stdout-intercepting {@code PrintStream})
 * that cannot accept an injected handle. Producing call sites — {@code
 * ScriptRunner}, {@code ManagementScriptRunner}, {@code RpcClient} — receive a
 * {@code Consumer<String>} tagger and a {@code Runnable} cleaner through the
 * constructor, so they do not reach for this class directly.
 *
 * <p>See {@code JBotWithUsV2/CLAUDE.md} → "Java rules exceptions" for the
 * recorded waiver.
 */
// rule-exception: §Banned 5 (mutable static behind a getter). Cross-cutting
// request-context seam consumed by the CLI's stdout interception, which has
// no way to receive an injected handle. Future replacement: java.lang.ScopedValue
// when the project's baseline JDK supports it as a non-preview API.
public final class ConnectionContext {

    private static final InheritableThreadLocal<String> CURRENT = new InheritableThreadLocal<>();

    private ConnectionContext() {}

    public static void set(String connectionName) {
        CURRENT.set(connectionName);
    }

    public static String get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
