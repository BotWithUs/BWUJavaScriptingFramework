package com.botwithus.bot.core.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Every JAR in the resolved scripts directory runs as fully-trusted code with
 * no signature check, so the set of places the loader will look must stay
 * small and predictable.
 */
class ScriptsDirResolutionTest {

    private static final String SCRIPTS_DIR_PROPERTY = "botwithus.scripts.dir";

    @Test
    @DisplayName("the -D override wins")
    void overrideWins() {
        String previous = System.getProperty(SCRIPTS_DIR_PROPERTY);
        System.setProperty(SCRIPTS_DIR_PROPERTY, "C:/some/explicit/dir");
        try {
            assertEquals(Path.of("C:/some/explicit/dir"), LocalScriptLoader.resolveScriptsDir());
        } finally {
            restore(previous);
        }
    }

    @Test
    @DisplayName("a scripts/ dir in a PARENT of the working directory is never used")
    void doesNotWalkUpToParents() {
        String previous = System.getProperty(SCRIPTS_DIR_PROPERTY);
        System.clearProperty(SCRIPTS_DIR_PROPERTY);
        try {
            Path resolved = LocalScriptLoader.resolveScriptsDir().toAbsolutePath().normalize();
            Path cwd = Path.of("").toAbsolutePath().normalize();
            Path userFallback = Path.of(System.getProperty("user.home"), ".botwithus", "scripts")
                    .toAbsolutePath().normalize();

            // Only two outcomes are legitimate: scripts/ directly under the
            // working directory, or the per-user fallback. Anything else means
            // the parent walk came back — that walk let anyone able to create a
            // directory in an ancestor of the CWD replace the whole script set.
            boolean acceptable = resolved.equals(cwd.resolve("scripts"))
                    || resolved.equals(userFallback);
            assertEquals(true, acceptable,
                    "resolved to an unexpected location: " + resolved);

            for (Path ancestor = cwd.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
                assertNotEquals(ancestor.resolve("scripts"), resolved,
                        "scripts dir must never resolve into a parent of the working directory");
            }
        } finally {
            restore(previous);
        }
    }

    private static void restore(String previous) {
        if (previous == null) {
            System.clearProperty(SCRIPTS_DIR_PROPERTY);
        } else {
            System.setProperty(SCRIPTS_DIR_PROPERTY, previous);
        }
    }
}
