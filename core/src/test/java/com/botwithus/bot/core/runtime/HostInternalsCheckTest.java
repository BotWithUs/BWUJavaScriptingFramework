package com.botwithus.bot.core.runtime;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.lang.module.ResolvedModule;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HostInternalsCheck} over fabricated descriptors. Nothing is loaded:
 * every module here is a descriptor and a location, which is all the check reads.
 */
class HostInternalsCheckTest {

    private static final String HOST = HostInternalsCheck.HOST_MODULE;
    private static final String API = "com.botwithus.bot.api";
    private static final String QUEST = "com.botwithus.bot.quest";
    private static final String SKILLING = "com.botwithus.bot.skilling";
    private static final String CLI = "com.botwithus.bot.cli";
    private static final String SCRIPT = "probe.script";
    private static final String HOP1 = "probe.hop1";
    private static final String HOP2 = "probe.hop2";
    /** Deep enough that walking every path takes minutes; deciding each module once takes milliseconds. */
    private static final int LADDER_RUNGS = 28;
    /** Generous for a loaded machine, and still far below the cost of walking every path. */
    private static final Duration LADDER_BUDGET = Duration.ofSeconds(2);
    private static final Set<ModuleDescriptor.Requires.Modifier> TRANSITIVE =
            Set.of(ModuleDescriptor.Requires.Modifier.TRANSITIVE);
    private static final Function<ResolvedModule, String> JAR_OF = module -> module.name() + ".jar";

    /** The host's own modules: api, quest and skilling re-export api; cli re-exports the host. */
    private static final List<ModuleDescriptor> HOST_MODULES = List.of(
            ModuleDescriptor.newModule(API).build(),
            ModuleDescriptor.newModule(QUEST).requires(Set.of(ModuleDescriptor.Requires.Modifier.TRANSITIVE), API)
                    .build(),
            ModuleDescriptor.newModule(SKILLING)
                    .requires(Set.of(ModuleDescriptor.Requires.Modifier.TRANSITIVE), API).build(),
            ModuleDescriptor.newModule(HOST).requires(Set.of(ModuleDescriptor.Requires.Modifier.TRANSITIVE), API)
                    .build(),
            ModuleDescriptor.newModule(CLI).requires(Set.of(ModuleDescriptor.Requires.Modifier.TRANSITIVE), HOST)
                    .build());

    @Test
    void inspect_aModuleThatRequiresTheHost_isReportedWithItsModuleAndJar() {
        List<String> warned = new ArrayList<>();

        List<HostInternalsCheck.Reach> found = inspect(script(HOST), warned);

        assertEquals(1, found.size());
        HostInternalsCheck.Reach reach = found.getFirst();
        assertAll(
                () -> assertEquals(SCRIPT, reach.module()),
                () -> assertEquals(SCRIPT + ".jar", reach.jar()),
                () -> assertEquals(HostInternalsCheck.Route.DIRECT, reach.route()),
                () -> assertEquals(List.of(reach.message()), warned, "one warning, and it is the message"),
                () -> assertTrue(reach.message().contains("'" + SCRIPT + "'"), reach.message()),
                () -> assertTrue(reach.message().contains(SCRIPT + ".jar"), reach.message()),
                () -> assertTrue(reach.message().contains("a future release will refuse to load it"),
                        reach.message()));
    }

    @Test
    void inspect_aModuleThatRequiresOnlyApiQuestAndSkilling_isNotReported() {
        List<String> warned = new ArrayList<>();

        List<HostInternalsCheck.Reach> found = inspect(script(API, QUEST, SKILLING), warned);

        assertAll(
                () -> assertTrue(found.isEmpty(), found.toString()),
                () -> assertTrue(warned.isEmpty(), warned.toString()));
    }

    /** cli says {@code requires transitive} the host, so requiring cli reads the host without naming it. */
    @Test
    void inspect_aModuleThatReachesTheHostThroughAReexport_isReportedWithTheModuleItCameThrough() {
        List<HostInternalsCheck.Reach> found = inspect(script(CLI), new ArrayList<>());

        assertEquals(1, found.size());
        assertAll(
                () -> assertEquals(HostInternalsCheck.Route.REEXPORTED, found.getFirst().route()),
                () -> assertEquals(List.of(CLI), found.getFirst().via()),
                () -> assertTrue(found.getFirst().message().contains("through its dependency on " + CLI),
                        found.getFirst().message()));
    }

    /** Two hops: the message names the module the script required, not the one deep in the chain. */
    @Test
    void inspect_aReexportTwoHopsDeep_namesTheModuleTheScriptItselfRequired() {
        ModuleDescriptor hop1 = ModuleDescriptor.newModule(HOP1).requires(TRANSITIVE, HOST).build();
        ModuleDescriptor hop2 = ModuleDescriptor.newModule(HOP2).requires(TRANSITIVE, HOP1).build();
        Configuration child = resolve(hostConfiguration(), List.of(script(HOP2), hop1, hop2), SCRIPT);

        Optional<HostInternalsCheck.Reach> found = HostInternalsCheck.inspect(child, SCRIPT, JAR_OF, message -> { });

        assertEquals(Optional.of(List.of(HOP2)), found.map(HostInternalsCheck.Reach::via));
    }

    /**
     * The configuration also holds the module's dependencies. One that reads the
     * host is reported when it is loaded itself, never against a module that
     * merely depends on it.
     */
    @Test
    void inspect_aDependencyThatReadsTheHost_isNotReportedAgainstTheModuleBeingLoaded() {
        ModuleDescriptor library = ModuleDescriptor.newModule(HOP1).requires(HOST).build();
        Configuration child = resolve(hostConfiguration(), List.of(script(HOP1), library), SCRIPT);
        List<String> warned = new ArrayList<>();

        Optional<HostInternalsCheck.Reach> found = HostInternalsCheck.inspect(child, SCRIPT, JAR_OF, warned::add);

        assertAll(
                () -> assertTrue(found.isEmpty(), found.toString()),
                () -> assertTrue(warned.isEmpty(), warned.toString()),
                () -> assertTrue(HostInternalsCheck.inspect(child, HOP1, JAR_OF, message -> { }).isPresent(),
                        "the library itself is reported when it is the module being loaded"));
    }

    /** An automatic module reads everything, the host included, without saying so. */
    @Test
    void inspect_anAutomaticModule_isReportedAsAutomatic() {
        ModuleDescriptor automatic = ModuleDescriptor.newAutomaticModule(SCRIPT).build();

        List<HostInternalsCheck.Reach> found = inspect(automatic, new ArrayList<>());

        assertEquals(1, found.size());
        assertEquals(HostInternalsCheck.Route.AUTOMATIC, found.getFirst().route());
    }

    /** The host's own modules sit in the parent configuration; cli reads the host, and is not a script. */
    @Test
    void inspect_modulesOfAParentConfiguration_areNeverReported() {
        Configuration parent = hostConfiguration();
        Configuration child = resolve(parent, List.of(script(API)), SCRIPT);

        Optional<HostInternalsCheck.Reach> found = HostInternalsCheck.inspect(child, SCRIPT, JAR_OF, message -> { });

        assertTrue(found.isEmpty(), found.toString());
    }

    /**
     * Each rung of the ladder requires both modules of the rung below
     * transitively, so the number of paths doubles per rung while the number of
     * modules grows by two. Deciding each module once keeps this in milliseconds;
     * walking every path would take minutes at this depth.
     */
    @Test
    void inspect_aDeepLadderOfSharedDependencies_isDecidedWellInsideTheBudget() {
        List<ModuleDescriptor> modules = new ArrayList<>(diamondLadder(LADDER_RUNGS));
        modules.add(script(CLI, rung(LADDER_RUNGS - 1, "a")));
        Configuration child = resolve(hostConfiguration(), modules, SCRIPT);

        Optional<HostInternalsCheck.Reach> found = assertTimeoutPreemptively(LADDER_BUDGET,
                () -> HostInternalsCheck.inspect(child, SCRIPT, JAR_OF, message -> { }));

        assertEquals(Optional.of(List.of(CLI)), found.map(HostInternalsCheck.Reach::via));
    }

    // Fixtures -------------------------------------------------------------

    /** Rungs {@code 0..rungs-1}, two modules each; none of them passes the host on. */
    private static List<ModuleDescriptor> diamondLadder(int rungs) {
        List<ModuleDescriptor> ladder = new ArrayList<>();
        for (int level = 0; level < rungs; level++) {
            for (String side : List.of("a", "b")) {
                ModuleDescriptor.Builder builder = ModuleDescriptor.newModule(rung(level, side));
                if (level > 0) {
                    builder.requires(TRANSITIVE, rung(level - 1, "a"));
                    builder.requires(TRANSITIVE, rung(level - 1, "b"));
                }
                ladder.add(builder.build());
            }
        }
        return ladder;
    }

    private static String rung(int level, String side) {
        return "probe.rung" + level + side;
    }

    private static ModuleDescriptor script(String... requires) {
        ModuleDescriptor.Builder builder = ModuleDescriptor.newModule(SCRIPT);
        for (String name : requires) {
            builder.requires(name);
        }
        return builder.build();
    }

    /** Resolves {@code script} against the host's modules, as a script layer resolves against the boot layer. */
    private static List<HostInternalsCheck.Reach> inspect(ModuleDescriptor script, List<String> warned) {
        Configuration child = resolve(hostConfiguration(), List.of(script), SCRIPT);
        return HostInternalsCheck.inspect(child, SCRIPT, JAR_OF, warned::add).stream().toList();
    }

    private static Configuration hostConfiguration() {
        return resolve(ModuleLayer.boot().configuration(), HOST_MODULES, API, QUEST, SKILLING, HOST, CLI);
    }

    private static Configuration resolve(Configuration parent, List<ModuleDescriptor> modules, String... roots) {
        return parent.resolve(finderOf(modules), ModuleFinder.of(), Set.of(roots));
    }

    private static ModuleFinder finderOf(List<ModuleDescriptor> descriptors) {
        Map<String, ModuleReference> refs = descriptors.stream().collect(Collectors.toMap(
                ModuleDescriptor::name, HostInternalsCheckTest::referenceOf));
        return new ModuleFinder() {
            @Override
            public Optional<ModuleReference> find(String name) {
                return Optional.ofNullable(refs.get(name));
            }

            @Override
            public Set<ModuleReference> findAll() {
                return Set.copyOf(refs.values());
            }
        };
    }

    private static ModuleReference referenceOf(ModuleDescriptor descriptor) {
        return new ModuleReference(descriptor, URI.create("file:///" + descriptor.name() + ".jar")) {
            @Override
            public ModuleReader open() throws IOException {
                throw new IOException("never opened: the check reads descriptors only");
            }
        };
    }
}
