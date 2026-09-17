package com.botwithus.bot.core.sdn;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SdnInstallerTest {

    @TempDir
    Path dir;

    @Test
    void install_withNothingSelected_saysSoRatherThanCallingTheCourier() {
        SdnInstallResult result = new SdnInstaller(dir).install(List.of());

        assertInstanceOf(SdnInstallResult.NothingSelected.class, result);
    }

    /**
     * Delivery is off unless the launcher started this host with it on, which is
     * the case in a plain unit-test JVM. That is the state a user hits when they
     * run the host standalone, so it gets its own answer rather than a timeout.
     */
    @Test
    void install_whenDeliveryIsNotEnabled_saysDeliveryDisabled() {
        SdnInstallResult result = new SdnInstaller(dir).install(List.of("7"));

        assertInstanceOf(SdnInstallResult.DeliveryDisabled.class, result);
    }

    @Test
    void requestBody_namesEveryRequestedScript() {
        byte[] body = SdnInstaller.requestBody(List.of("7", "12"));

        JsonObject parsed = JsonParser.parseString(new String(body, StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonArray ids = parsed.getAsJsonArray("scriptIds");
        assertEquals(2, ids.size());
        assertEquals("7", ids.get(0).getAsString());
        assertEquals("12", ids.get(1).getAsString());
    }

    @Test
    void requestBody_carriesThePidTheCourierMustAnswer() {
        byte[] body = SdnInstaller.requestBody(List.of("7"));

        JsonObject parsed = JsonParser.parseString(new String(body, StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals(SdnRendezvous.currentPid(), parsed.get("pid").getAsLong());
        assertTrue(parsed.get("requestedAtEpochMs").getAsLong() > 0);
    }
}
