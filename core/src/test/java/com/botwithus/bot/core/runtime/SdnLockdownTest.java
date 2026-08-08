package com.botwithus.bot.core.runtime;

import com.botwithus.bot.core.crypto.SdnLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Process lockdown is now fail-closed: on a JVM that can arm it, a failure to
 * do so aborts the load rather than running scripts with unsigned-DLL loading
 * silently left open.
 *
 * <p>The failure path itself needs the custom JDK to exercise, so what is
 * asserted here is the other half of the contract — that a stock JDK, where
 * there is no lockdown to arm, is still a clean no-op. That is the case a
 * fail-closed change is most likely to break.</p>
 */
class SdnLockdownTest {

    @Test
    @DisplayName("a stock JDK reports no SDN loader")
    void stockJdkHasNoSdnLoader() {
        assumeFalse(SdnLoader.isAvailable(), "running on the custom SDN JDK");
        assertFalse(SdnLoader.isAvailable());
    }

    @Test
    @DisplayName("loading scripts on a stock JDK does not abort on the lockdown gate")
    void loadingDoesNotThrowWithoutSdnLoader(@TempDir Path emptyScriptsDir) {
        assumeFalse(SdnLoader.isAvailable(), "running on the custom SDN JDK");
        // An empty scripts dir is the case that used to leave lockdown disarmed
        // for the whole session; it must still load cleanly here.
        assertDoesNotThrow(() -> SDNScriptLoader.loadScripts(emptyScriptsDir));
    }
}
