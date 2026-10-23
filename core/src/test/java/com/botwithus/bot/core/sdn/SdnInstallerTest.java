package com.botwithus.bot.core.sdn;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.core.runtime.SdnDelivery;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class SdnInstallerTest {

    private static final long RACE_TIMEOUT_SECONDS = 30;
    private static final int RACING_INSTALLS = 2;
    private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");
    private static final int BUILD = 7;
    private static final String DELIVERED_NAME = "Delivered Woodcutter";

    @TempDir
    Path dir;

    @TempDir
    Path home;

    private InstalledScriptsLedger ledger() {
        return new InstalledScriptsLedger(home, InstantSource.fixed(NOW));
    }

    private static SdnCatalogueEntry entry(String id) {
        return entry(id, DELIVERED_NAME, DeliveredScript.class.getName(), BUILD);
    }

    private static SdnCatalogueEntry entry(String id, String name, String scriptClass, Integer build) {
        return new SdnCatalogueEntry(id, name, "author", "me", "1.0", "2", "", "", scriptClass,
                false, true, true, false, null, null, build);
    }

    /** An installer whose courier always delivers {@code delivery}, recording into {@link #ledger()}. */
    private SdnInstaller delivering(SdnDelivery delivery) {
        return new SdnInstaller(dir, () -> true, () -> delivery, ledger());
    }

    /** A delivery of one jar per script, in order, every jar defined. */
    private static SdnDelivery jars(BotScript... perJar) {
        List<SdnDelivery.Jar> jars = new ArrayList<>();
        for (int i = 0; i < perJar.length; i++) {
            jars.add(new SdnDelivery.Jar(i, List.of(perJar[i])));
        }
        return new SdnDelivery(perJar.length, jars);
    }

    private static SdnInstallResult.Installed installed(SdnInstallResult result) {
        return switch (result) {
            case SdnInstallResult.Installed installed -> installed;
            case SdnInstallResult.NothingSelected ignored -> fail("nothing selected");
            case SdnInstallResult.DeliveryDisabled ignored -> fail("delivery disabled");
            case SdnInstallResult.CourierUnavailable ignored -> fail("courier unavailable");
            case SdnInstallResult.Failed failed -> fail(failed.reason());
        };
    }

    @Test
    void install_withNothingSelected_saysSoRatherThanCallingTheCourier() {
        SdnInstallResult result = new SdnInstaller(dir, ledger()).install(List.of());

        assertInstanceOf(SdnInstallResult.NothingSelected.class, result);
    }

    /**
     * Delivery is off unless the launcher started this host with it on, which is
     * the case in a plain unit-test JVM. That is the state a user hits when they
     * run the host standalone, so it gets its own answer rather than a timeout.
     */
    @Test
    void install_whenDeliveryIsNotEnabled_saysDeliveryDisabled() {
        SdnInstallResult result = new SdnInstaller(dir, ledger()).install(List.of(entry("7")));

        assertInstanceOf(SdnInstallResult.DeliveryDisabled.class, result);
    }

    /** The Store asks before anything is picked, so it can say up front that installs cannot work. */
    @Test
    void isDeliveryEnabled_reportsWhatTheInstallWouldFind() {
        assertAll(
                () -> assertTrue(new SdnInstaller(dir, () -> true, SdnDelivery::none, ledger())
                        .isDeliveryEnabled()),
                () -> assertFalse(new SdnInstaller(dir, () -> false, SdnDelivery::none, ledger())
                        .isDeliveryEnabled()));
    }

    /**
     * The Store panel and the picker each hold an installer, and both can install
     * at once. The courier holds the first delivery until the second caller has
     * either queued for the rendezvous or got into it, so the interleaving that
     * matters is forced rather than hoped for. Serialised, the courier sees two
     * requests one after the other, each intact until it is answered. Unserialised,
     * the second request overwrites the first while the courier is still on it.
     */
    @Test
    @Timeout(RACE_TIMEOUT_SECONDS)
    void install_twoInstallersAtOnce_takeTheRendezvousOneAtATime() throws Exception {
        FakeCourier courier = new FakeCourier(dir.resolve(SdnRendezvous.currentPid()
                + SdnInstaller.REQUEST_SUFFIX));
        Thread courierThread = Thread.ofPlatform().daemon().name("fake-courier").start(courier);
        SdnInstaller store = new SdnInstaller(dir, () -> true, courier::awaitDelivery, ledger());
        SdnInstaller picker = new SdnInstaller(dir, () -> true, courier::awaitDelivery, ledger());
        ReentrantLock rendezvous = SdnRendezvous.exchangeLock(dir, SdnInstaller.REQUEST_SUFFIX);

        FutureTask<SdnInstallResult> first = new FutureTask<>(() -> store.install(List.of(entry("7"))));
        Thread.ofPlatform().name("store-install").start(first);
        courier.firstRequestSeen.await();
        FutureTask<SdnInstallResult> second = new FutureTask<>(() -> picker.install(List.of(entry("12"))));
        Thread secondThread = Thread.ofPlatform().name("picker-install").start(second);
        while (!rendezvous.hasQueuedThread(secondThread) && courier.arrivals.get() < RACING_INSTALLS) {
            Thread.onSpinWait();
        }
        courier.releaseFirst.countDown();

        assertInstanceOf(SdnInstallResult.Installed.class, first.get());
        assertInstanceOf(SdnInstallResult.Installed.class, second.get());
        courierThread.join();
        assertAll(
                () -> assertEquals(1, courier.maxInFlight.get(),
                        "two installs were in the rendezvous at once"),
                () -> assertEquals(List.of(List.of("7"), List.of("12")), courier.requestedIds,
                        "the courier did not see each request, in order"),
                () -> assertEquals(List.of(), courier.tornRequests,
                        "a request changed or vanished before the courier answered it"),
                () -> assertFalse(Files.exists(courier.request),
                        "the last install left its request behind"));
    }

    // The ledger ----------------------------------------------------------

    @Test
    void install_delivered_recordsTheScriptClassAgainstItsCatalogueEntry() {
        SdnInstallResult result = delivering(jars(new DeliveredScript())).install(List.of(entry("7")));

        assertInstanceOf(SdnInstallResult.Installed.class, result);
        // A fresh ledger over the same directory proves the record reached the disk.
        InstalledSdnScript recorded = ledger().find(DeliveredScript.class.getName())
                .orElseGet(() -> fail("the delivered script was not recorded"));
        assertAll(
                () -> assertEquals("7", recorded.catalogueId()),
                () -> assertEquals(Integer.valueOf(BUILD), recorded.installedBuild()),
                () -> assertEquals(NOW, recorded.installedAt()));
    }

    /**
     * The CraftWithUs shape: the catalogue entry is shared by both agents, so it
     * names the agent-v1 class, and the jar delivered here is the v2 build with a
     * different class and a different manifest name. The jar is what pairs them.
     */
    @Test
    void install_entryNamingAnotherAgentsClass_pairsTheDeliveryByItsJar() {
        BotScript v2Build = new DeliveredScript();
        SdnCatalogueEntry shared = entry("7", "CraftWithUs", "com.example.agentv1.CraftWithUs", BUILD);

        SdnInstallResult.Installed result = installed(delivering(jars(v2Build)).install(List.of(shared)));

        assertAll(
                () -> assertEquals(List.of(v2Build), result.scriptsFor("7")),
                () -> assertEquals(Optional.of("7"),
                        ledger().find(DeliveredScript.class.getName()).map(InstalledSdnScript::catalogueId)),
                () -> assertEquals(List.of(DeliveredScript.class.getName()),
                        List.copyOf(ledger().installsOf("7").keySet())));
    }

    @Test
    void install_entryWithoutAScriptClass_pairsTheDeliveryByItsJarWhateverItsName() {
        SdnCatalogueEntry blank = entry("7", "A name nothing is registered under", "", BUILD);

        SdnInstallResult.Installed result = installed(delivering(jars(new DeliveredScript()))
                .install(List.of(blank)));

        assertAll(
                () -> assertEquals(1, result.scriptsFor("7").size()),
                () -> assertEquals(Optional.of("7"),
                        ledger().find(DeliveredScript.class.getName()).map(InstalledSdnScript::catalogueId)));
    }

    /** Position wins over what the catalogue names: the launcher delivers jar i for request i. */
    @Test
    void install_batch_recordsEachScriptAgainstTheEntryAtItsJarsPosition() {
        SdnCatalogueEntry first = entry("7", "Names the other class", OtherScript.class.getName(), BUILD);
        SdnCatalogueEntry second = entry("12", "Names the delivered class", DeliveredScript.class.getName(), null);

        delivering(jars(new DeliveredScript(), new OtherScript())).install(List.of(first, second));

        Map<String, InstalledSdnScript> all = ledger().all();
        assertAll(
                () -> assertEquals(2, all.size()),
                () -> assertEquals("7", all.get(DeliveredScript.class.getName()).catalogueId()),
                () -> assertEquals("12", all.get(OtherScript.class.getName()).catalogueId()),
                () -> assertNull(all.get(OtherScript.class.getName()).installedBuild(),
                        "an entry with no build records no build, not zero"));
    }

    /** A jar that failed to define is missing from the list but still holds its position. */
    @Test
    void install_aJarFailedToDefine_theOthersKeepTheirPositions() {
        BotScript other = new OtherScript();
        SdnDelivery secondOnly = new SdnDelivery(2, List.of(new SdnDelivery.Jar(1, List.of(other))));

        SdnInstallResult.Installed result = installed(delivering(secondOnly)
                .install(List.of(entry("7"), entry("12", "Other", "", null))));

        assertAll(
                () -> assertEquals(List.of(), result.scriptsFor("7")),
                () -> assertEquals(List.of(other), result.scriptsFor("12")),
                () -> assertEquals(Optional.of("12"),
                        ledger().find(OtherScript.class.getName()).map(InstalledSdnScript::catalogueId)));
    }

    @Test
    void install_jarCountDisagreesWithTheRequest_pairsAndRecordsNothing() {
        SdnInstallResult.Installed result = installed(delivering(jars(new DeliveredScript()))
                .install(List.of(entry("7"), entry("12"))));

        assertAll(
                () -> assertEquals(1, result.scripts().size(), "the scripts still load"),
                () -> assertTrue(result.byEntryId().isEmpty(), "but none can be credited to an entry"),
                () -> assertTrue(ledger().all().isEmpty()));
    }

    @Test
    void install_courierDeliversNothing_recordsNothing() {
        SdnInstallResult result = delivering(SdnDelivery.none()).install(List.of(entry("7")));

        assertInstanceOf(SdnInstallResult.CourierUnavailable.class, result);
        assertFalse(Files.exists(ledger().file()));
    }

    /**
     * The scripts are loaded by the time the ledger is written, so a ledger that
     * cannot be written must not turn a working install into a failed one.
     */
    @Test
    void install_ledgerUnwritable_stillReportsInstalled() throws IOException {
        Path blocked = home.resolve("not-a-directory");
        Files.writeString(blocked, "a file where the ledger directory should be");
        InstalledScriptsLedger unwritable = new InstalledScriptsLedger(blocked, InstantSource.fixed(NOW));

        SdnInstallResult result = new SdnInstaller(dir, () -> true, () -> jars(new DeliveredScript()),
                unwritable).install(List.of(entry("7")));

        assertInstanceOf(SdnInstallResult.Installed.class, result);
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

    /**
     * Plays the launcher. The host's side of a delivery publishes a key and waits
     * ({@link #awaitDelivery}); this courier, on its own thread, takes each key in
     * turn, reads the request file the way the launcher does, and delivers. It holds
     * the first delivery until {@link #releaseFirst}, and it checks each request is
     * still there, unchanged, when it answers it.
     */
    private static final class FakeCourier implements Runnable {

        private static final String MISSING = "<no request file>";

        final Path request;
        final CountDownLatch firstRequestSeen = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        final AtomicInteger arrivals = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        final List<List<String>> requestedIds = new CopyOnWriteArrayList<>();
        final List<String> tornRequests = new CopyOnWriteArrayList<>();
        private final AtomicInteger inFlight = new AtomicInteger();
        private final BlockingQueue<CompletableFuture<SdnDelivery>> keys =
                new LinkedBlockingQueue<>();

        FakeCourier(Path request) {
            this.request = request;
        }

        /** The host's half: publish a key, then wait for the delivery. */
        SdnDelivery awaitDelivery() {
            arrivals.incrementAndGet();
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                CompletableFuture<SdnDelivery> delivery = new CompletableFuture<>();
                keys.add(delivery);
                return delivery.join();
            } finally {
                inFlight.decrementAndGet();
            }
        }

        @Override
        public void run() {
            try {
                for (int i = 0; i < RACING_INSTALLS; i++) {
                    deliver(keys.take(), i == 0);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void deliver(CompletableFuture<SdnDelivery> key, boolean isFirst)
                throws InterruptedException {
            String body = readRequest();
            requestedIds.add(idsOf(body));
            if (isFirst) {
                firstRequestSeen.countDown();
                releaseFirst.await();
            }
            String atDelivery = readRequest();
            if (!atDelivery.equals(body)) {
                tornRequests.add(body + " -> " + atDelivery);
            }
            key.complete(jars(new DeliveredScript()));
        }

        private String readRequest() {
            try {
                return Files.readString(request, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return MISSING;
            }
        }

        private static List<String> idsOf(String body) {
            if (body.equals(MISSING)) {
                return List.of();
            }
            List<String> ids = new ArrayList<>();
            JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("scriptIds")
                    .forEach(id -> ids.add(id.getAsString()));
            return ids;
        }
    }

    /** Fails if a delivery is not kept: a later client or a reload would have nothing to register. */
    @Test
    void install_shelvesTheDeliveryUnderTheEntryItWasPairedWith() {
        StoreShelf shelf = new StoreShelf();
        SdnInstaller installer = new SdnInstaller(dir, () -> true, () -> jars(new DeliveredScript()),
                SdnPipeRoute.none(), ledger(), shelf);

        installed(installer.install(List.of(entry("7"))));

        assertAll(
                () -> assertEquals(Set.of("7"), shelf.catalogueIds()),
                () -> assertEquals(Set.of(DeliveredScript.class.getName()), shelf.classes()),
                () -> assertSame(shelf, installer.shelf()));
    }

    /** What a delivery carries. */
    @ScriptManifest(name = DELIVERED_NAME)
    private static final class DeliveredScript implements BotScript {

        @Override
        public void onStart(ScriptContext ctx) {
        }

        @Override
        public int onLoop() {
            return -1;
        }

        @Override
        public void onStop() {
        }
    }

    /** A second script, for batch deliveries. */
    @ScriptManifest(name = "Other")
    private static final class OtherScript implements BotScript {

        @Override
        public void onStart(ScriptContext ctx) {
        }

        @Override
        public int onLoop() {
            return -1;
        }

        @Override
        public void onStop() {
        }
    }
}
