package com.botwithus.bot.core.runtime;

import java.util.Objects;

/**
 * Who a script is, for decisions the host remembers about it across restarts, such as
 * whether it may use the microphone.
 *
 * <p>The {@link #key} is the script's module name and its class's fully qualified name,
 * {@code <module>/<class>}, or {@code unnamed/<class>} for a script outside a named module.
 * It survives a rebuild and an update of the script, which keep both names, and it does not
 * depend on where the JAR was copied to or which client the script runs on. It is declared
 * by the script's own JAR, so it identifies a script, not who wrote it: two JARs that
 * declare the same module and class are the same script to the host.</p>
 *
 * @param key         {@code <module>/<class>}; stable across runs
 * @param displayName the name the user sees for the script
 */
public record ScriptIdentity(String key, String displayName) {

    /** The module part of {@link #key} for a script outside a named module. */
    static final String UNNAMED_MODULE = "unnamed";

    public ScriptIdentity {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(displayName, "displayName");
        if (key.isBlank()) {
            throw new IllegalArgumentException("script key is blank");
        }
    }

    /** The identity of a script whose entry class is {@code scriptClass}, shown as {@code displayName}. */
    public static ScriptIdentity of(Class<?> scriptClass, String displayName) {
        Module module = scriptClass.getModule();
        String moduleName = module.isNamed() ? module.getName() : UNNAMED_MODULE;
        return new ScriptIdentity(moduleName + "/" + scriptClass.getName(), displayName);
    }
}
