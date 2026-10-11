package com.botwithus.bot.core.sdn;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.core.crypto.SdnDiskBundleSource;
import com.botwithus.bot.core.runtime.SDNScriptLoader;
import com.botwithus.bot.core.runtime.SdnDelivery;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <p>Each script that arrives is paired with the catalogue entry it was requested
 * for <em>by the jar it came from</em>: the launcher delivers one jar per requested
 * id, in the order requested, and all or nothing. The pairing is returned with the
 * result and recorded in the {@link InstalledScriptsLedger}, so the host can tell
 * it is a Store script, and which build, after it is loaded. Neither a class name
 * nor a manifest name is used: a catalogue entry shared by both agents names the
 * agent-v1 class, so the v2 build delivered here never matches it.
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
    private final Supplier<SdnDelivery> awaitDelivery;
    private final InstalledScriptsLedger ledger;

    /** The shared rendezvous directory, recording installs in the user's ledger. */
    public SdnInstaller() {
        this(SdnRendezvous.directory(), InstalledScriptsLedger.inUserHome());
    }

    public SdnInstaller(Path directory, InstalledScriptsLedger ledger) {
        this(directory, SdnDiskBundleSource::isEnabled, SDNScriptLoader::loadSdnDeliveryFromDisk, ledger);
    }

    /**
     * An installer over a delivery source of the caller's choosing. The real
     * delivery needs the patched JVM and a running launcher, so tests of the
     * install screens supply both through here and still get the real pairing
     * and ledger.
     *
     * @param deliveryEnabled whether the launcher started this host with delivery on
     * @param awaitDelivery   publishes the key, waits for the courier and loads the
     *                        delivery, one jar per requested entry in request order
     */
    public SdnInstaller(Path directory, BooleanSupplier deliveryEnabled,
                 Supplier<SdnDelivery> awaitDelivery, InstalledScriptsLedger ledger) {
        this.directory = directory;
        this.deliveryEnabled = deliveryEnabled;
        this.awaitDelivery = awaitDelivery;
        this.ledger = ledger;
    }

    /**
     * Whether the launcher started this host with delivery on. When it did not,
     * {@link #install} answers {@link SdnInstallResult.DeliveryDisabled} without
     * asking anyone, so a page can say so before the user picks anything.
     */
    public boolean isDeliveryEnabled() {
        return deliveryEnabled.getAsBoolean();
    }

    /** The ledger this installer records into, for readers that show what the Store installed. */
    public InstalledScriptsLedger ledger() {
        return ledger;
    }

    /**
     * Requests {@code entries} from the launcher and loads whatever it delivers.
     *
     * <p>Blocking: it waits for any install already running in this process, then
     * for the courier, so call it off the render thread. Waiting in line does not
     * count against the courier's timeout, which starts only once this call has
     * the rendezvous to itself.
     */
    public SdnInstallResult install(List<SdnCatalogueEntry> entries) {
        if (entries.isEmpty()) {
            return new SdnInstallResult.NothingSelected();
        }
        if (!deliveryEnabled.getAsBoolean()) {
            return new SdnInstallResult.DeliveryDisabled();
        }
        ReentrantLock exchange = SdnRendezvous.exchangeLock(directory, REQUEST_SUFFIX);
        exchange.lock();
        try {
            return exchange(entries);
        } finally {
            exchange.unlock();
        }
    }

    private SdnInstallResult exchange(List<SdnCatalogueEntry> entries) {
        List<String> scriptIds = entries.stream().map(SdnCatalogueEntry::id).toList();
        Path request = directory.resolve(SdnRendezvous.currentPid() + REQUEST_SUFFIX);
        try {
            Files.createDirectories(directory);
            // Written before the key is published: the courier needs to know what
            // to fetch, and publishing the key is what starts it waiting.
            SdnRendezvous.atomicWrite(request, requestBody(scriptIds));
            log.info("SDN: requested {} script(s) from the courier", scriptIds.size());
            return loadDelivered(entries);
        } catch (IOException e) {
            return new SdnInstallResult.Failed(e.getMessage());
        } finally {
            SdnRendezvous.deleteQuietly(request);
        }
    }

    private SdnInstallResult loadDelivered(List<SdnCatalogueEntry> entries) {
        SdnDelivery delivery = awaitDelivery.get();
        if (delivery.isEmpty()) {
            return new SdnInstallResult.CourierUnavailable();
        }
        Map<String, List<BotScript>> byEntryId = pair(entries, delivery);
        recordInstalls(entries, byEntryId);
        return new SdnInstallResult.Installed(delivery.scripts(), byEntryId);
    }

    /**
     * Records each delivered script class against the entry its jar was requested
     * as. The scripts are already loaded, so a ledger that cannot be written costs
     * only the record, never the install.
     */
    private void recordInstalls(List<SdnCatalogueEntry> entries, Map<String, List<BotScript>> byEntryId) {
        Map<String, SdnCatalogueEntry> byClass = new LinkedHashMap<>();
        for (SdnCatalogueEntry entry : entries) {
            for (BotScript script : byEntryId.getOrDefault(entry.id(), List.of())) {
                byClass.put(script.getClass().getName(), entry);
            }
        }
        try {
            ledger.record(byClass);
        } catch (IOException e) {
            log.warn("SDN: installed {} script(s), but could not record them in {}: {}",
                    byClass.size(), ledger.file(), e.getMessage());
        }
    }

    /**
     * Pairs the jar at position {@code i} with requested entry {@code i}. When the
     * delivery does not carry one jar per request, positions mean nothing and no
     * script is paired: crediting one to a guess would show, and start, the wrong
     * Store script.
     */
    static Map<String, List<BotScript>> pair(List<SdnCatalogueEntry> entries, SdnDelivery delivery) {
        if (delivery.jarCount() != entries.size()) {
            log.warn("SDN: requested {} script(s) but the launcher delivered {} jar(s), so no "
                    + "delivered script can be told apart; none is recorded as installed",
                    entries.size(), delivery.jarCount());
            return Map.of();
        }
        Map<String, List<BotScript>> paired = new LinkedHashMap<>();
        for (SdnDelivery.Jar jar : delivery.jars()) {
            paired.put(entries.get(jar.position()).id(), jar.scripts());
        }
        return paired;
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
