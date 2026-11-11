package com.botwithus.bot.core.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.module.ModuleReference;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StagedModulesTest {

    private static final String LABEL = "scripts";

    @TempDir
    Path stagingRoot;

    @TempDir
    Path scriptsDir;

    @TempDir
    Path work;

    @Test
    void probe_aModuleNamedLikeAHostModule_isRejectedAndTheOthersStillLoad() throws Exception {
        Path namedLikeHost = TestModules.hostStandIn(scriptsDir, work);
        Path bystander = TestModules.bystander(scriptsDir, work);
        List<Path> sources = List.of(namedLikeHost, bystander);
        ScriptJarStaging.Staged staged = new ScriptJarStaging(LABEL, stagingRoot).stage(sources, scriptsDir);

        StagedModules.Probe probe = StagedModules.probe(staged, sources, HostInternalsCheck.HOST_MODULE::equals);

        Throwable why = probe.rejected().get(namedLikeHost);
        assertAll(
                () -> assertEquals(List.of(TestModules.BYSTANDER),
                        probe.modules().stream().map(ModuleReference::descriptor).map(d -> d.name()).toList()),
                () -> assertTrue(why != null && why.getMessage().contains(HostInternalsCheck.HOST_MODULE)
                        && why.getMessage().contains("has the name of a module the host provides"),
                        () -> "rejections: " + probe.rejected()),
                () -> assertTrue(probe.finder().find(HostInternalsCheck.HOST_MODULE).isEmpty(),
                        "a rejected module must not be offered to resolution"));
    }

    @Test
    void isHostModule_isFalseForAModuleTheBootLayerLacksOrThatIsNotTheHosts() {
        assertAll(
                () -> assertFalse(StagedModules.isHostModule(StagedModules.HOST_MODULE_PREFIX + "absent")),
                () -> assertFalse(StagedModules.isHostModule("java.sql"),
                        "a platform module is the boot layer's, not the host's"));
    }
}
