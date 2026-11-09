package com.botwithus.bot.core.runtime;

import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Requires;
import java.lang.module.ResolvedModule;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Finds a script module that can reach into the host's internals, and says so.
 *
 * <p>Every script layer resolves against the boot layer, which is how a script
 * sees {@code api}, {@code quest} and {@code skilling}. The host module,
 * {@value #HOST_MODULE}, is in that layer too, and most of its packages are
 * exported to everyone, so a script that reads it can use the host's own
 * transports and runtime. A future release will narrow what the host module
 * exposes, and a script that depends on it will then fail to load. This check
 * is how the host learns which scripts that will be before it happens. It is
 * advisory: it reports, it does not refuse.</p>
 *
 * <p>It works from the resolved {@link Configuration} alone: descriptors and the
 * readability graph, before any class is loaded. That is what lets it see a
 * script that reads the host module without naming it, through a module that
 * re-exports it. What it cannot see is which of the host's packages a script
 * actually uses; a descriptor does not record that.</p>
 *
 * <p>Only the module being loaded is inspected. A loader resolves each JAR
 * against every staged JAR, so the configuration also holds that module's
 * dependencies, and resolving one automatic module pulls in every other one.
 * Reporting those too would repeat each finding once per JAR and attach it to
 * a JAR its author did not build.</p>
 *
 * <p>Every loader that defines a script layer calls {@link #inspect} on the
 * configuration it is about to define, so the check lives here once.</p>
 */
final class HostInternalsCheck {

    /** The host module whose exports a script should not depend on. */
    static final String HOST_MODULE = "com.botwithus.bot.core";

    private static final String CONSEQUENCE = " This script reaches into host internals;"
            + " a future release will refuse to load it. Scripts should depend on"
            + " com.botwithus.bot.api, com.botwithus.bot.quest and com.botwithus.bot.skilling"
            + " (and libraries such as imgui.binding), never on " + HOST_MODULE + ".";

    private HostInternalsCheck() {}

    /** How a script module came to read {@value #HOST_MODULE}. */
    enum Route {
        /** Its descriptor says {@code requires com.botwithus.bot.core}. */
        DIRECT,
        /** It requires a module that passes the host module on ({@code requires transitive}). */
        REEXPORTED,
        /** It has no module descriptor, so it reads every module, the host's included. */
        AUTOMATIC
    }

    /**
     * The script module being loaded, which reads the host module.
     *
     * @param module the script module's name
     * @param jar    where it came from, as the user knows it
     * @param route  how it reads the host module
     * @param via    for {@link Route#REEXPORTED}, the modules the script itself requires
     *               that pass the host module on to it; else empty
     */
    record Reach(String module, String jar, Route route, List<String> via) {

        Reach {
            via = List.copyOf(via);
        }

        /** The sentence a user reads: what was found and what will change. */
        String message() {
            String how = switch (route) {
                case DIRECT -> "requires " + HOST_MODULE;
                case REEXPORTED -> "reads " + HOST_MODULE + " through its dependency on "
                        + String.join(", ", via);
                case AUTOMATIC -> "has no module-info, so it reads every module including "
                        + HOST_MODULE + " (whether it uses it cannot be told from here)";
            };
            return "Script module '" + module + "' from " + jar + " " + how + "." + CONSEQUENCE;
        }
    }

    /**
     * Reports {@code root} if it reads the host module.
     *
     * @param configuration the configuration a script layer is about to be defined from
     * @param root          the module being loaded; its dependencies are not reported
     * @param jarOf         names the jar a resolved module came from, for the message
     * @param warn          receives the message when {@code root} is reported
     * @return the finding, or empty when {@code root} does not read the host module
     */
    static Optional<Reach> inspect(Configuration configuration, String root,
                                   Function<ResolvedModule, String> jarOf, Consumer<String> warn) {
        Optional<Reach> found = configuration.findModule(root)
                .filter(HostInternalsCheck::readsHost)
                .map(module -> reachOf(module, jarOf.apply(module)));
        found.ifPresent(reach -> warn.accept(reach.message()));
        return found;
    }

    /**
     * Names a module of a layer defined over staged copies by the jar the user
     * built, never the staged copy, which means nothing to them.
     */
    static Function<ResolvedModule, String> stagedJarOf(ScriptJarStaging.Staged staged) {
        return module -> module.reference().location()
                .map(Path::of)
                .map(staged::sourceOf)
                .map(Path::toString)
                .orElse("an unknown location");
    }

    private static boolean readsHost(ResolvedModule module) {
        return module.reads().stream().anyMatch(read -> read.name().equals(HOST_MODULE));
    }

    private static Reach reachOf(ResolvedModule module, String jar) {
        ModuleDescriptor descriptor = module.reference().descriptor();
        if (descriptor.isAutomatic()) {
            return new Reach(module.name(), jar, Route.AUTOMATIC, List.of());
        }
        if (descriptor.requires().stream().anyMatch(requires -> requires.name().equals(HOST_MODULE))) {
            return new Reach(module.name(), jar, Route.DIRECT, List.of());
        }
        Map<ResolvedModule, Boolean> passesOn = new HashMap<>();
        List<String> via = descriptor.requires().stream()
                .map(Requires::name)
                .filter(name -> readsOneThatPassesHostOn(module, name, passesOn))
                .sorted()
                .toList();
        return new Reach(module.name(), jar, Route.REEXPORTED, via);
    }

    /**
     * Whether a module that reads {@code module} reads the host module because
     * of it: {@code module} requires the host transitively, directly or through
     * a chain of transitive requires. The resolver refuses cycles, so this ends.
     *
     * <p>{@code memo} holds the answer for every module already decided in this
     * call. Without it a graph where modules share dependencies is walked once
     * per path rather than once per module, which grows exponentially with its
     * depth; with it each module is decided once.</p>
     */
    private static boolean passesHostOn(ResolvedModule module, Map<ResolvedModule, Boolean> memo) {
        Boolean known = memo.get(module);
        if (known != null) {
            return known;
        }
        boolean passes = module.reference().descriptor().requires().stream()
                .filter(requires -> requires.modifiers().contains(Requires.Modifier.TRANSITIVE))
                .anyMatch(requires -> requires.name().equals(HOST_MODULE)
                        || readsOneThatPassesHostOn(module, requires.name(), memo));
        memo.put(module, passes);
        return passes;
    }

    /** Whether {@code module} reads a module called {@code name} that passes the host module on. */
    private static boolean readsOneThatPassesHostOn(ResolvedModule module, String name,
                                                    Map<ResolvedModule, Boolean> memo) {
        return module.reads().stream()
                .filter(read -> read.name().equals(name))
                .anyMatch(read -> passesHostOn(read, memo));
    }
}
