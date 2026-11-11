package com.botwithus.bot.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Opens;
import java.lang.module.ModuleDescriptor.Requires;
import java.net.URL;
import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The front-end module's descriptor, as compiled.
 *
 * <p>The front-end is the top of the host's module graph: nothing requires it,
 * so it exports nothing, and it passes no module on. Scripts resolve against
 * the boot layer it lives in, so an export here, or a transitive require of
 * the host module, would be script API by accident.</p>
 */
class FrontEndModuleExportsTest {

    private static final String FRONT_END = "com.botwithus.bot.cli";
    private static final String DESCRIPTOR = "module-info.class";
    private static final String LOG_PACKAGE = "com.botwithus.bot.cli.log";
    private static final Set<String> LOGBACK = Set.of("ch.qos.logback.core");

    @Test
    void theFrontEnd_exportsNothing() {
        ModuleDescriptor frontEnd = frontEndDescriptor();
        assertTrue(frontEnd.exports().isEmpty(), () -> "the front-end exports " + frontEnd.exports());
    }

    @Test
    void theFrontEnd_requiresNothingTransitively() {
        ModuleDescriptor frontEnd = frontEndDescriptor();
        assertTrue(frontEnd.requires().stream()
                        .noneMatch(requires -> requires.modifiers().contains(Requires.Modifier.TRANSITIVE)),
                () -> "the front-end passes on " + frontEnd.requires());
    }

    @Test
    void theFrontEnd_opensOnlyItsLogAppenderToLogback() {
        ModuleDescriptor frontEnd = frontEndDescriptor();
        Opens only = frontEnd.opens().stream().findFirst().orElseThrow();
        assertAll(
                () -> assertEquals(1, frontEnd.opens().size(), () -> "the front-end opens " + frontEnd.opens()),
                () -> assertEquals(LOG_PACKAGE, only.source()),
                () -> assertEquals(LOGBACK, only.targets()));
    }

    private static ModuleDescriptor frontEndDescriptor() {
        try {
            return Collections.list(ClassLoader.getSystemClassLoader().getResources(DESCRIPTOR)).stream()
                    .map(FrontEndModuleExportsTest::read)
                    .filter(descriptor -> descriptor.name().equals(FRONT_END))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no compiled descriptor for " + FRONT_END
                            + " on the test class path"));
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
