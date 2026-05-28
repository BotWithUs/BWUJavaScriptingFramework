package com.botwithus.bot.core.loader;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

class BwuClientResolveTest {

    /**
     * Regression test for the DLL-planting fix: {@code resolve()} must never
     * load {@code bwu.dll} from the ambient working directory. The loader runs
     * native code in-process and performs auth + injection, so a planted DLL
     * in whatever folder the app was launched from must be ignored.
     */
    @Test
    void resolveDoesNotLoadFromCurrentWorkingDirectory() throws IOException {
        Path planted = Path.of("bwu.dll").toAbsolutePath().normalize();
        boolean created = false;
        if (!Files.exists(planted)) {
            Files.writeString(planted, "not a real dll");
            created = true;
        }
        try {
            Path resolved = BwuClient.resolve(BwuClientResolveTest.class);
            Path resolvedNorm = resolved == null ? null : resolved.toAbsolutePath().normalize();
            assertNotEquals(planted, resolvedNorm,
                    "resolve() must not select bwu.dll from the working directory");
        } finally {
            if (created) {
                Files.deleteIfExists(planted);
            }
        }
    }
}
