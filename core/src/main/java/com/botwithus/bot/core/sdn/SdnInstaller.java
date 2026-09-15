package com.botwithus.bot.core.sdn;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.core.crypto.SdnDiskBundleSource;
import com.botwithus.bot.core.runtime.SDNScriptLoader;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Asks the launcher to deliver a set of catalogue scripts, then loads them.
 *
 * <p>The host names the scripts it wants and the launcher does the fetching, so
 * the credential stays in the one process that already holds it. What comes back
 * is loaded through the same path the host already uses for courier-delivered
 * scripts; the caller registers the result with a runtime, at which point the
 * scripts behave exactly like any locally installed one.
 */
public final class SdnInstaller {

    private static final Logger log = LoggerFactory.getLogger(SdnInstaller.class);

    private static final String REQUEST_SUFFIX = ".instreq";

    private final Path directory;

    public SdnInstaller() {
        this(SdnRendezvous.directory());
    }

    public SdnInstaller(Path directory) {
        this.directory = directory;
    }

    /**
     * Requests {@code scriptIds} from the launcher and loads whatever it delivers.
     *
     * <p>Blocking: it waits for the courier, so call it off the render thread.
     */
    public SdnInstallResult install(List<String> scriptIds) {
        if (scriptIds.isEmpty()) {
            return new SdnInstallResult.NothingSelected();
        }
        if (!SdnDiskBundleSource.isEnabled()) {
            return new SdnInstallResult.DeliveryDisabled();
        }
        Path request = directory.resolve(SdnRendezvous.currentPid() + REQUEST_SUFFIX);
        try {
            Files.createDirectories(directory);
            // Written before the key is published: the courier needs to know what
            // to fetch, and publishing the key is what starts it waiting.
            SdnRendezvous.atomicWrite(request, requestBody(scriptIds));
            log.info("SDN: requested {} script(s) from the courier", scriptIds.size());
            return loadDelivered();
        } catch (IOException e) {
            return new SdnInstallResult.Failed(e.getMessage());
        } finally {
            SdnRendezvous.deleteQuietly(request);
        }
    }

    private static SdnInstallResult loadDelivered() {
        List<BotScript> scripts = SDNScriptLoader.loadSdnScriptsFromDisk();
        if (scripts.isEmpty()) {
            return new SdnInstallResult.CourierUnavailable();
        }
        return new SdnInstallResult.Installed(scripts);
    }

    static byte[] requestBody(List<String> scriptIds) {
        JsonArray ids = new JsonArray();
        for (String id : scriptIds) {
            ids.add(id);
        }
        JsonObject body = new JsonObject();
        body.addProperty("pid", SdnRendezvous.currentPid());
        body.addProperty("requestedAtEpochMs", System.currentTimeMillis());
        body.add("scriptIds", ids);
        return body.toString().getBytes(StandardCharsets.UTF_8);
    }
}
