package com.botwithus.bot.core.sdn;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.core.crypto.SdnDiskBundleSource;
import com.botwithus.bot.core.launcher.SdnBundleChannel;
import com.botwithus.bot.core.launcher.SdnBundleDownload;
import com.botwithus.bot.core.launcher.SdnBundleFetch;
import com.botwithus.bot.core.launcher.SdnRefusal;
import com.botwithus.bot.core.launcher.SdnScriptFailure;
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
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Asks the launcher to deliver a set of catalogue scripts, then loads them.
 *
 * <p>Two routes, chosen per install. When this host's launcher connection is up
 * and the launcher listed {@code sdn.bundle} in its {@code hello}, the bundle
 * comes over the automation pipe ({@link SdnBundleDownload}) and nothing is
 * written to disk; such a launcher serves no bundle by file, so files are never
 * tried. Only when the method is absent (an older launcher) or no launcher
 * answers does the install fall back to the file courier described below,
 * unchanged. A launcher that refuses ({@code sdn_not_delivered}) is reported as
 * {@link SdnInstallResult.Refused}, in the user's words for the reason it gave;
 * a launcher that gives none gets the generic text. When the launcher lists which
 * script each jar is, jars are paired by that list rather than by position.
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
    private final SdnPipeRoute pipe;
    private final StoreShelf shelf;
    /**
     * Catalogue ids whose script did not finish constructing, each with its name.
     * Its thread is still running and holds the class's initialisation, so another
     * attempt in this process would only stall again: they fail fast until restart.
     */
    private final Map<String, String> stalledIds = new ConcurrentHashMap<>();

    /** Files only: the shared rendezvous directory, recording installs in the user's ledger. */
    public SdnInstaller() {
        this(SdnPipeRoute.none());
    }

    /** The production installer: over {@code pipe} when the launcher offers it, else the shared directory. */
    public SdnInstaller(SdnPipeRoute pipe) {
        this(pipe, new StoreShelf());
    }

    /**
     * The production installer, shelving every delivery on {@code shelf} so the host
     * can hand it to clients that connect later and keep it across a reload.
     */
    public SdnInstaller(SdnPipeRoute pipe, StoreShelf shelf) {
        this(SdnRendezvous.directory(), SdnDiskBundleSource::isEnabled, SDNScriptLoader::loadSdnDeliveryFromDisk,
                pipe, InstalledScriptsLedger.inUserHome(), shelf);
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
        this(directory, deliveryEnabled, awaitDelivery, SdnPipeRoute.none(), ledger);
    }

    /**
     * As above, with the pipe route too.
     *
     * @param pipe the launcher-pipe route, preferred whenever the launcher offers it
     */
    public SdnInstaller(Path directory, BooleanSupplier deliveryEnabled, Supplier<SdnDelivery> awaitDelivery,
                        SdnPipeRoute pipe, InstalledScriptsLedger ledger) {
        this(directory, deliveryEnabled, awaitDelivery, pipe, ledger, new StoreShelf());
    }

    /**
     * As above, with the shelf deliveries are kept on.
     *
     * @param shelf holds every delivered script in memory, one per host
     */
    public SdnInstaller(Path directory, BooleanSupplier deliveryEnabled, Supplier<SdnDelivery> awaitDelivery,
                        SdnPipeRoute pipe, InstalledScriptsLedger ledger, StoreShelf shelf) {
        this.directory = directory;
        this.deliveryEnabled = deliveryEnabled;
        this.awaitDelivery = awaitDelivery;
        this.pipe = pipe;
        this.ledger = ledger;
        this.shelf = shelf;
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

    /** The shelf every delivery is kept on. */
    public StoreShelf shelf() {
        return shelf;
    }

    /**
     * Requests {@code entries} from the launcher and loads whatever it delivers.
     *
     * <p>Blocking: it waits for any install already running in this process, then
     * for the launcher, so call it off the render thread. Pipe installs wait in the
     * same line, because the launcher holds one delivery per connection and a
     * second {@code sdn.bundle} would replace the first. Waiting in line does not
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
        List<SdnCatalogueEntry> stalled = entries.stream().filter(e -> stalledIds.containsKey(e.id())).toList();
        List<SdnCatalogueEntry> rest = entries.stream().filter(e -> !stalledIds.containsKey(e.id())).toList();
        stalled.forEach(e -> log.warn("SDN: not asking the launcher for {}: it stalled while loading earlier "
                + "in this run", e.name()));
        if (rest.isEmpty()) {
            return new SdnInstallResult.Failed(stalledMessage(stalled.getFirst().name()));
        }
        SdnInstallResult result = installLocked(rest);
        return stalled.isEmpty() ? result : withStalled(result, stalled);
    }

    /**
     * Why {@code entryId} cannot be installed again in this run, when its script
     * stalled while loading earlier; empty for any other id.
     */
    public Optional<String> stalledReason(String entryId) {
        return Optional.ofNullable(stalledIds.get(entryId)).map(SdnInstaller::stalledMessage);
    }

    private SdnInstallResult installLocked(List<SdnCatalogueEntry> entries) {
        ReentrantLock exchange = SdnRendezvous.exchangeLock(directory, REQUEST_SUFFIX);
        exchange.lock();
        try {
            Optional<SdnBundleChannel> channel = pipe.offering();
            return channel.isPresent() ? overPipe(entries, channel.get()) : exchange(entries);
        } finally {
            exchange.unlock();
        }
    }

    /** The rest's result, with the entries left out because they stalled named in it when it installed. */
    private static SdnInstallResult withStalled(SdnInstallResult result, List<SdnCatalogueEntry> stalled) {
        return switch (result) {
            case SdnInstallResult.Installed installed -> installed.withRefused(stalled.stream()
                    .map(e -> new SdnInstallResult.ScriptRefusal(Optional.of(e.id()), Optional.empty(),
                            stalledMessage(e.name())))
                    .toList());
            case SdnInstallResult.NothingSelected ignored -> result;
            case SdnInstallResult.DeliveryDisabled ignored -> result;
            case SdnInstallResult.CourierUnavailable ignored -> result;
            case SdnInstallResult.Refused ignored -> result;
            case SdnInstallResult.Failed ignored -> result;
        };
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

    private SdnInstallResult overPipe(List<SdnCatalogueEntry> entries, SdnBundleChannel channel) {
        List<String> scriptIds = entries.stream().map(SdnCatalogueEntry::id).toList();
        byte[] key;
        try {
            key = pipe.publicKey().get();
        } catch (RuntimeException e) {
            log.warn("SDN: this runtime has no delivery key: {}", e.getMessage());
            return new SdnInstallResult.Failed("this runtime cannot receive Store scripts");
        }
        log.info("SDN: requested {} script(s) from the launcher over its pipe", scriptIds.size());
        return switch (SdnBundleDownload.fetch(channel, key, scriptIds)) {
            case SdnBundleFetch.Fetched fetched -> installFetched(entries, fetched);
            case SdnBundleFetch.NotDelivered notDelivered -> refused(entries, notDelivered.refusal());
            case SdnBundleFetch.Unavailable none -> new SdnInstallResult.CourierUnavailable(
                    none.isTimedOut() ? SdnInstallResult.NoAnswer.TIMED_OUT : SdnInstallResult.NoAnswer.NOT_CONNECTED);
            case SdnBundleFetch.Failed failed -> new SdnInstallResult.Failed(failed.reason());
        };
    }

    /**
     * The bytes arrived intact. Each jar is paired with the id the launcher listed for
     * it, or with its request position when an older launcher listed none, and a
     * bundle whose jar count disagrees is refused before any of its scripts is made.
     * A delivery that then loads nothing is a failure, not an absent launcher.
     */
    private SdnInstallResult installFetched(List<SdnCatalogueEntry> entries, SdnBundleFetch.Fetched fetched) {
        List<SdnCatalogueEntry> inJarOrder = fetched.delivered().map(ids -> inOrder(entries, ids)).orElse(entries);
        OptionalInt jars = SdnDiskBundleSource.jarCountOf(fetched.bundle());
        if (jars.isPresent() && jars.getAsInt() != inJarOrder.size()) {
            log.error("SDN: the bundle holds {} jar(s) but {} were listed for it; nothing from it is loaded",
                    jars.getAsInt(), inJarOrder.size());
            return new SdnInstallResult.Failed(SdnReasonText.MISMATCH_TEXT);
        }
        SdnDelivery delivery = pipe.define().apply(fetched.bundle());
        if (!delivery.stalls().isEmpty()) {
            return stalled(inJarOrder, delivery);
        }
        if (delivery.isEmpty()) {
            return new SdnInstallResult.Failed("the delivered scripts could not be loaded");
        }
        return installDelivery(inJarOrder, delivery, refusalsOf(entries, fetched.failed()));
    }

    private SdnInstallResult loadDelivered(List<SdnCatalogueEntry> entries) {
        SdnDelivery delivery = awaitDelivery.get();
        if (delivery.refusal().isPresent()) {
            return fileRouteRefused(entries, delivery.refusal().get());
        }
        if (!delivery.stalls().isEmpty()) {
            return stalled(entries, delivery);
        }
        if (delivery.isEmpty()) {
            return new SdnInstallResult.CourierUnavailable(SdnInstallResult.NoAnswer.FILE_ROUTE_SILENT);
        }
        if (delivery.jarCount() != entries.size()) {
            log.error("SDN: requested {} script(s) but the courier delivered {} jar(s); nothing from it is "
                    + "registered", entries.size(), delivery.jarCount());
            return new SdnInstallResult.Failed(SdnReasonText.MISMATCH_TEXT);
        }
        return installDelivery(entries, delivery, List.of());
    }

    private SdnInstallResult installDelivery(List<SdnCatalogueEntry> entries, SdnDelivery delivery,
                                             List<SdnInstallResult.ScriptRefusal> refused) {
        Map<String, List<BotScript>> byEntryId = pair(entries, delivery);
        log.info("SDN: paired {} of {} delivered script(s) with the jar delivered for it", byEntryId.size(),
                entries.size());
        recordInstalls(entries, byEntryId);
        shelve(entries, delivery, !byEntryId.isEmpty());
        return new SdnInstallResult.Installed(delivery.scripts(), byEntryId, refused);
    }

    /** The requested entries in the order the launcher listed their jars; the list was checked already. */
    private static List<SdnCatalogueEntry> inOrder(List<SdnCatalogueEntry> entries, List<String> ids) {
        Map<String, SdnCatalogueEntry> byId = new LinkedHashMap<>();
        entries.forEach(e -> byId.putIfAbsent(e.id(), e));
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    /** What the launcher said for each script it could not deliver, in the user's words. */
    private static List<SdnInstallResult.ScriptRefusal> refusalsOf(List<SdnCatalogueEntry> entries,
                                                                   List<SdnScriptFailure> failed) {
        return failed.stream().map(f -> new SdnInstallResult.ScriptRefusal(Optional.of(f.scriptId()),
                Optional.of(f.reason()), SdnReasonText.of(Optional.of(f.reason()), nameOf(entries, f.scriptId()),
                        f.cfRay()))).toList();
    }

    /** The launcher refused the whole request; its reason becomes the user's text. */
    private static SdnInstallResult refused(List<SdnCatalogueEntry> entries, SdnRefusal refusal) {
        List<SdnInstallResult.ScriptRefusal> each = refusalsOf(entries, refusal.failed());
        if (!each.isEmpty()) {
            return new SdnInstallResult.Refused(each);
        }
        String name = refusal.scriptId().map(id -> nameOf(entries, id)).orElseGet(() -> nameOf(entries));
        return new SdnInstallResult.Refused(List.of(new SdnInstallResult.ScriptRefusal(refusal.scriptId(),
                refusal.reason(), SdnReasonText.of(refusal.reason(), name, refusal.cfRay()))));
    }

    /** The file route's courier answered "no"; an error file naming no reason gets the generic text. */
    private static SdnInstallResult fileRouteRefused(List<SdnCatalogueEntry> entries, String reason) {
        Optional<String> code = reason.isEmpty() ? Optional.empty() : Optional.of(reason);
        return new SdnInstallResult.Refused(List.of(new SdnInstallResult.ScriptRefusal(Optional.empty(), code,
                SdnReasonText.of(code, nameOf(entries), Optional.empty()))));
    }

    /** The catalogue name for {@code id}, falling back to the id when it was not requested. */
    private static String nameOf(List<SdnCatalogueEntry> entries, String id) {
        return entries.stream().filter(e -> e.id().equals(id)).map(SdnCatalogueEntry::name).findFirst().orElse(id);
    }

    /** The one script's name when one was requested, else a phrase for them all. */
    private static String nameOf(List<SdnCatalogueEntry> entries) {
        return entries.size() == 1 ? entries.getFirst().name() : "these scripts";
    }

    /**
     * A script in the delivery never finished constructing. Nothing from the delivery
     * is installed, and the entry it was for fails fast for the rest of this run.
     * A stall is credited to an entry only when the delivery can be paired with the
     * request at all, as {@link #pair} requires; otherwise no innocent entry is marked
     * and the message names the class.
     */
    private SdnInstallResult stalled(List<SdnCatalogueEntry> entries, SdnDelivery delivery) {
        boolean isPairable = delivery.jarCount() == entries.size();
        String name = delivery.stalls().getFirst().scriptClass();
        for (SdnDelivery.Stall stall : delivery.stalls()) {
            if (isPairable && stall.position() < entries.size()) {
                SdnCatalogueEntry entry = entries.get(stall.position());
                stalledIds.put(entry.id(), entry.name());
                name = entry.name();
            }
        }
        return new SdnInstallResult.Failed(stalledMessage(name));
    }

    /** What the user is told about a script that did not finish loading. */
    static String stalledMessage(String name) {
        return name + " did not finish loading within " + SDNScriptLoader.CONSTRUCT_BUDGET.toSeconds()
                + " seconds. Restart the host to try again.";
    }

    /**
     * Keeps every delivered jar on the shelf, each under the entry it was paired
     * with. An unpaired delivery is still shelved, under no entry: its scripts were
     * delivered and are registered, so they should survive a reload too.
     */
    private void shelve(List<SdnCatalogueEntry> entries, SdnDelivery delivery, boolean isPaired) {
        for (SdnDelivery.Jar jar : delivery.jars()) {
            Optional<String> id = isPaired ? Optional.of(entries.get(jar.position()).id()) : Optional.empty();
            shelf.stock(id, jar);
        }
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
            log.info("SDN: recorded {} install(s) in {}", byClass.size(), ledger.file().getFileName());
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
