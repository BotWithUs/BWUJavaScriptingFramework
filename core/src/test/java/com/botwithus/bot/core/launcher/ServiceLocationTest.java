package com.botwithus.bot.core.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pipe name and the (provisional, A7) user-stopped flag path, real and development scopes. */
class ServiceLocationTest {

    private static final ServiceScope.ScopeIdentity IDENTITY = new ServiceScope.ScopeIdentity("S-1-5-21-1-2-3-1001", 1);

    @TempDir
    Path localAppData;

    @Test
    void realScope_pipeAndFlag() {
        ServiceLocation location = ServiceLocation.resolve(IDENTITY, new DevGate(false), env(null));
        assertEquals("bwu_svc_auto_9fec1d0680d64900", location.pipeName());
        assertEquals(Optional.of(localAppData.resolve("BotWithUs").resolve("service").resolve("stopped_by_user-1")),
                location.stoppedFlag());
    }

    @Test
    void devScope_pipeAndFlagInTheSandboxDirectory() {
        ServiceLocation location = ServiceLocation.resolve(IDENTITY, new DevGate(true), env("a6"));
        assertEquals("bwu_svc_auto_deva6", location.pipeName());
        assertEquals(Optional.of(localAppData.resolve("BotWithUs").resolve("service").resolve("dev-deva6")
                .resolve("stopped_by_user-1")), location.stoppedFlag());
    }

    @Test
    void isUserStopped_followsTheFile() throws IOException {
        ServiceLocation location = ServiceLocation.resolve(IDENTITY, new DevGate(false), env(null));
        assertFalse(location.isUserStopped());
        Path flag = location.stoppedFlag().orElseThrow();
        Files.createDirectories(flag.getParent());
        Files.createFile(flag);
        assertTrue(location.isUserStopped());
    }

    @Test
    void noLocalAppData_meansNoFlag() {
        ServiceLocation location = ServiceLocation.resolve(IDENTITY, new DevGate(false), name -> null);
        assertEquals(Optional.empty(), location.stoppedFlag());
        assertFalse(location.isUserStopped());
    }

    private UnaryOperator<String> env(String devScope) {
        return name -> switch (name) {
            case "LOCALAPPDATA" -> localAppData.toString();
            case DevGate.SCOPE_VARIABLE -> devScope;
            default -> null;
        };
    }
}
