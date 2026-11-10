package com.botwithus.bot.core.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.module.ModuleDescriptor;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The host module's descriptor, as compiled: what it exports and to whom.
 *
 * <p>Scripts resolve against the boot layer this module lives in, so an export
 * that names no target would make that package usable by every script. Every
 * export must name the front-end, and only the front-end. A new package that
 * the front-end needs is exported to it in the same change; a package a script
 * needs belongs in the api module. {@link ScriptModuleBoundaryTest} shows the
 * same boundary at run time.</p>
 */
class HostModuleExportsTest {

    private static final Set<String> FRONT_END_ONLY = Set.of("com.botwithus.bot.cli");
    private static final String DESCRIPTOR = "module-info.class";

    @Test
    void everyExport_namesTheFrontEndAndNothingElse() {
        ModuleDescriptor host = hostDescriptor();
        List<Executable> checks = host.exports().stream()
                .<Executable>map(export -> () -> assertEquals(FRONT_END_ONLY, export.targets(),
                        "exports " + export.source() + " must name the front-end only"))
                .toList();
        assertFalse(checks.isEmpty(), "the host module exports nothing; the front-end could not compile");
        assertAll(checks);
    }

    @Test
    void theModule_opensNothing() {
        ModuleDescriptor host = hostDescriptor();
        assertAll(
                () -> assertFalse(host.isOpen(), "the host module must not be an open module"),
                () -> assertTrue(host.opens().isEmpty(), () -> "the host module opens " + host.opens()));
    }

    /** The compiled descriptor of the host module, found among the class path's descriptors. */
    private static ModuleDescriptor hostDescriptor() {
        try {
            return Collections.list(ClassLoader.getSystemClassLoader().getResources(DESCRIPTOR)).stream()
                    .map(HostModuleExportsTest::read)
                    .filter(descriptor -> descriptor.name().equals(HostInternalsCheck.HOST_MODULE))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no compiled descriptor for "
                            + HostInternalsCheck.HOST_MODULE + " on the test class path"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ModuleDescriptor read(URL url) {
        try (InputStream in = url.openStream()) {
            return ModuleDescriptor.read(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
