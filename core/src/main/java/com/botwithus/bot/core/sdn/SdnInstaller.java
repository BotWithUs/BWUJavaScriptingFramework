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
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Asks the launcher to deliver a set of catalogue scripts, then loads them.
 *
 * <p>The host names the scripts it wants and the launcher does the fetching, so
 * the credential stays in the one process that already holds it. What comes back
 * is loaded through the same path the host already uses for courier-delivered
 * scripts; the caller registers the result with a runtime, at which point the
 * scripts behave exactly like any locally installed one.
 *
 * <p>Installs are serialised process-wide. The launcher reads one request file per
 * host process, so a second install running beside the first would overwrite its
 * request, and the first one's cleanup would delete the second's. Every installer
 * on the same directory therefore shares one {@link SdnRendezvous#exchangeLock}.
 */
public final class SdnInstaller {

    private static final Logger log = LoggerFactory.getLogger(SdnInstaller.class);

    /** Package-private so {@code SdnInstallerTest} can name the lock and the file. */
    static final String REQUEST_SUFFIX = ".instreq";

    private final Path directory;
    private final BooleanSupplier deliveryEnabled;
    private final Supplier<List<BotScript>> awaitDelivery;

    public SdnInstaller() {
        this(SdnRendezvous.directory());
    }

    public SdnInstaller(Path directory) {
        this(directory, SdnDiskBundleSource::isEnabled, SDNScriptLoader::loadSdnScriptsFromDisk);
    }

    /**
     * Package-private for {@code SdnInstallerTest}: the real delivery needs the
     * patched JVM and a running launcher, so the test supplies both.
     *
     * @param deliveryEnabled whether the launcher started this host with delivery on
     * @param awaitDelivery   publishes the key, waits for the courier and loads the delivery
     */
    SdnInstaller(Path directory, BooleanSupplier deliveryEnabled,
                 Supplier<List<BotScript>> awaitDelivery) {
        this.directory = directory;
        this.deliveryEnabled = deliveryEnabled;
        this.awaitDelivery = awaitDelivery;
    }

    /**
     * Requests {@code scriptIds} from the launcher and loads whatever it delivers.
     *
     * <p>Blocking: it waits for any install already running in this process, then
     * for the courier, so call it off the render thread. Waiting in line does not
     * count against the courier's timeout, which starts only once this call has
     * the rendezvous to itself.
     */
    public SdnInstallResult install(List<String> scriptIds) {
        if (scriptIds.isEmpty()) {
            return new SdnInstallResult.NothingSelected();
        }
        if (!deliveryEnabled.getAsBoolean()) {
            return new SdnInstallResult.DeliveryDisabled();
        }
        ReentrantLock exchange = SdnRendezvous.exchangeLock(directory, REQUEST_SUFFIX);
        exchange.lock();
        try {
            return exchange(scriptIds);
        } finally {
            exchange.unlock();
        }
    }

    private SdnInstallResult exchange(List<String> scriptIds) {
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

    private SdnInstallResult loadDelivered() {
        List<BotScript> scripts = awaitDelivery.get();
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
