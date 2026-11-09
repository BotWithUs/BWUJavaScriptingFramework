package com.botwithus.bot.core.runtime;

import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ResolvedModule;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Finds the script modules that can reach into the host's internals, and says so.
 *
 * <p>Every script layer resolves against the boot layer, which is how a script
 * sees {@code api}, {@code quest} and {@code skilling}. The host module,
 * {@value #HOST_MODULE}, is in that layer too, and most of its packages are
 * exported to everyone, so a script that reads it can use the host's own
 * transports and runtime. A future release will narrow those exports, and a
 * script that uses them will then fail to load. This check is how the host
 * learns which scripts that will be before it happens.</p>
 *
 * <p>It works from the resolved {@link Configuration} alone: descriptors and the
 * readability graph, before any class is loaded. That is what lets it see a
 * script that reads the host module without naming it, through a module that
 * re-exports it. What it cannot see is which of the host's packages a script
 * actually uses; a descriptor does not record that.</p>
 *
 * <p>Every loader that defines a script layer calls {@link #inspect} on the
 * configuration it is about to define, so the check lives here once.</p>
 */
final class HostInternalsCheck {

    /** The host module whose exports a script should not depend on. */
    static final String HOST_MODULE = "com.botwithus.bot.core";

    private static final String CONSEQUENCE = " This script reaches into host internals;"
            + " a future release will refuse to load it. Scripts should depend only on"
            + " com.botwithus.bot.api, com.botwithus.bot.quest and com.botwithus.bot.skilling.";

    private HostInternalsCheck() {}

    /** How a script module came to read {@value #HOST_MODULE}. */
    enum Route {
        /** Its descriptor says {@code requires com.botwithus.bot.core}. */
        DIRECT,
        /** It requires a module that re-exports the host module ({@code requires transitive}). */
        REEXPORTED,
        /** It has no module descriptor, so it reads every module, the host's included. */
        AUTOMATIC
    }

    /**
     * One script module that reads the host module.
     *
     * @param module the script module's name
     * @param jar    where it came from, as the user knows it
     * @param route  how it reads the host module
     * @param via    for {@link Route#REEXPORTED}, the modules that re-export it; else empty
     */
    record Reach(String module, String jar, Route route, List<String> via) {

        Reach {
            via = List.copyOf(via);
        }

        /** The sentence a user reads: what was found and what will change. */
        String message() {
            String how = switch (route) {
                case DIRECT -> "requires " + HOST_MODULE;
                case REEXPORTED -> "reads " + HOST_MODULE + " through " + String.join(", ", via);
                case AUTOMATIC -> "has no module-info, so it reads every module including "
                        + HOST_MODULE + " (whether it uses it cannot be told from here)";
            };
            return "Script module '" + module + "' from " + jar + " " + how + "." + CONSEQUENCE;
        }
    }

    /**
     * Reports every module {@code configuration} defines that reads the host module.
     *
     * <p>Only the configuration's own modules are inspected, never its parents',
     * so the host's own modules are never reported.</p>
     *
     * @param configuration the configuration a script layer is about to be defined from
     * @param jarOf         names the jar a resolved module came from, for the message
     * @param warn          receives one message per module found
     * @return what was found, in the order it was reported
     */
    static List<Reach> inspect(Configuration configuration, Function<ResolvedModule, String> jarOf,
                               Consumer<String> warn) {
        List<Reach> found = configuration.modules().stream()
                .filter(HostInternalsCheck::readsHost)
                .map(module -> reachOf(module, jarOf.apply(module)))
                .sorted(Comparator.comparing(Reach::module))
                .toList();
        found.forEach(reach -> warn.accept(reach.message()));
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
        if (requiresHost(descriptor, false)) {
            return new Reach(module.name(), jar, Route.DIRECT, List.of());
        }
        List<String> via = module.reads().stream()
                .filter(read -> requiresHost(read.reference().descriptor(), true))
                .map(ResolvedModule::name)
                .sorted()
                .toList();
        return new Reach(module.name(), jar, Route.REEXPORTED, via);
    }

    private static boolean requiresHost(ModuleDescriptor descriptor, boolean isTransitiveOnly) {
        return descriptor.requires().stream()
                .filter(requires -> requires.name().equals(HOST_MODULE))
                .anyMatch(requires -> !isTransitiveOnly
                        || requires.modifiers().contains(ModuleDescriptor.Requires.Modifier.TRANSITIVE));
    }
}
