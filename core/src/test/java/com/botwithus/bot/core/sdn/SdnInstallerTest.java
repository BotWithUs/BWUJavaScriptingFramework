package com.botwithus.bot.core.sdn;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;

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
import java.util.ArrayList;
import java.util.List;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class SdnInstallerTest {

    private static final long RACE_TIMEOUT_SECONDS = 30;
    private static final int RACING_INSTALLS = 2;

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
        SdnInstaller store = new SdnInstaller(dir, () -> true, courier::awaitDelivery);
        SdnInstaller picker = new SdnInstaller(dir, () -> true, courier::awaitDelivery);
        ReentrantLock rendezvous = SdnRendezvous.exchangeLock(dir, SdnInstaller.REQUEST_SUFFIX);

        FutureTask<SdnInstallResult> first = new FutureTask<>(() -> store.install(List.of("7")));
        Thread.ofPlatform().name("store-install").start(first);
        courier.firstRequestSeen.await();
        FutureTask<SdnInstallResult> second = new FutureTask<>(() -> picker.install(List.of("12")));
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
        private final BlockingQueue<CompletableFuture<List<BotScript>>> keys =
                new LinkedBlockingQueue<>();

        FakeCourier(Path request) {
            this.request = request;
        }

        /** The host's half: publish a key, then wait for the delivery. */
        List<BotScript> awaitDelivery() {
            arrivals.incrementAndGet();
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                CompletableFuture<List<BotScript>> delivery = new CompletableFuture<>();
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

        private void deliver(CompletableFuture<List<BotScript>> key, boolean isFirst)
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
            key.complete(List.of(new DeliveredScript()));
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

    /** What a delivery carries; the install path only counts it. */
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
}
