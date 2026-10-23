package com.botwithus.bot.core.launcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.msgpack.value.Value;
import org.msgpack.value.ValueFactory;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Amendment B1 through the real connection: whether the launcher offers bundles
 * is read from its {@code hello}, and a bundle crosses the pipe as the
 * fixtures say it does.
 */
class LauncherServiceSdnTest {

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final LauncherService.Timings FAST = new LauncherService.Timings(
            List.of(Duration.ofMillis(10)), Duration.ofMillis(20),
            Duration.ofMillis(100), Duration.ofSeconds(2), Duration.ofMillis(1));
    private static final int CHUNK = 4096;
    private static final int TAIL = 1234;
    private static final long DELIVERY = 1;
    private static final byte[] KEY = SdnBundleDownloadTest.bytes(LauncherProtocol.SDN_PUBLIC_KEY_BYTES);
    private static final long HTTP_FORBIDDEN = 403;
    private static final String RAY = "8d1f0c0ffee-LHR";

    private final FakeLauncherService fake = new FakeLauncherService();
    private LauncherService service;

    @AfterEach
    void close() {
        if (service != null) {
            service.close();
        }
    }

    @Test
    void aLauncherThatListsSdnBundle_offersBundles() {
        helloListing(LauncherProtocol.METHOD_SDN_BUNDLE, LauncherProtocol.METHOD_SDN_BUNDLE_CHUNK);
        start();
        assertTrue(service.offersSdnBundles());
    }

    @Test
    void anOlderLauncher_doesNotOfferBundles() {
        helloListing(LauncherProtocol.METHOD_SERVICE_STATUS, LauncherProtocol.METHOD_HOST_ACK_CLOSE);
        start();
        assertFalse(service.offersSdnBundles());
    }

    @Test
    void noLauncher_doesNotOfferBundles_evenOneThatListedThem() {
        helloListing(LauncherProtocol.METHOD_SDN_BUNDLE, LauncherProtocol.METHOD_SDN_BUNDLE_CHUNK);
        start();
        fake.goDown();
        FakeLauncherService.await("the loss", WAIT, () -> !service.isConnected());
        assertFalse(service.offersSdnBundles());
    }

    @Test
    void aBundleCrossesThePipe_inOrder_withAShortLastChunk_andNoIndexPastTheEnd() throws Exception {
        byte[] bundle = SdnBundleDownloadTest.bytes(2 * CHUNK + TAIL);
        serve(bundle);
        start();
        SdnBundleFetch.Fetched fetched = assertInstanceOf(SdnBundleFetch.Fetched.class,
                SdnBundleDownload.fetch(service, KEY, List.of("7", "12")));
        Value request = fake.received(LauncherProtocol.METHOD_SDN_BUNDLE).getFirst().body();
        assertAll(() -> assertArrayEquals(bundle, fetched.bundle()),
                () -> assertArrayEquals(KEY, WireValues.binary(request, "publicKey").orElseThrow()),
                () -> assertEquals(List.of("7", "12"), WireValues.array(request, "scriptIds").stream()
                        .map(v -> v.asStringValue().asString()).toList()),
                () -> assertEquals(List.of(0L, 1L, 2L), fake.received(LauncherProtocol.METHOD_SDN_BUNDLE_CHUNK)
                        .stream().map(r -> WireValues.integer(r.body(), "index", -1L)).toList()));
    }

    @Test
    void sdnNotDelivered_fromTheService_isNotDelivered() {
        helloListing(LauncherProtocol.METHOD_SDN_BUNDLE, LauncherProtocol.METHOD_SDN_BUNDLE_CHUNK);
        fake.handle(LauncherProtocol.METHOD_SDN_BUNDLE, body -> new FakeLauncherService.Reply.Error(
                LauncherProtocol.ERROR_SDN_NOT_DELIVERED, "Not delivered.", true, ValueFactory.emptyMap()));
        start();
        assertInstanceOf(SdnBundleFetch.NotDelivered.class, SdnBundleDownload.fetch(service, KEY, List.of("7")));
        assertEquals(List.of(), fake.received(LauncherProtocol.METHOD_SDN_BUNDLE_CHUNK));
    }

    /** The typed reason, script and support reference come through; the service's message does not. */
    @Test
    void aTypedRefusal_keepsItsReasonScriptAndReference() {
        helloListing(LauncherProtocol.METHOD_SDN_BUNDLE, LauncherProtocol.METHOD_SDN_BUNDLE_CHUNK);
        Value detail = FakeLauncherService.body().put("reason", "cloudflare_challenge").put("scriptId", "7")
                .put("httpStatus", HTTP_FORBIDDEN).put("cfRay", RAY).build();
        fake.handle(LauncherProtocol.METHOD_SDN_BUNDLE, body -> new FakeLauncherService.Reply.Error(
                LauncherProtocol.ERROR_SDN_NOT_DELIVERED, "Blocked.", true, detail));
        start();

        SdnRefusal refusal = assertInstanceOf(SdnBundleFetch.NotDelivered.class,
                SdnBundleDownload.fetch(service, KEY, List.of("7"))).refusal();

        assertEquals(new SdnRefusal(Optional.of("cloudflare_challenge"), Optional.of("7"),
                OptionalLong.of(HTTP_FORBIDDEN), Optional.of(RAY), List.of()), refusal);
    }

    @Test
    void theRequest_asksForAPartialDelivery() throws Exception {
        serve(SdnBundleDownloadTest.bytes(CHUNK));
        start();

        SdnBundleDownload.fetch(service, KEY, List.of("7"));

        Value request = fake.received(LauncherProtocol.METHOD_SDN_BUNDLE).getFirst().body();
        assertTrue(WireValues.bool(request, SdnWire.ALLOW_PARTIAL, false));
    }

    @Test
    void aPartialOffer_carriesWhichJarIsWhich_andWhatFailed() throws Exception {
        byte[] bundle = SdnBundleDownloadTest.bytes(CHUNK);
        Value failed = ValueFactory.newArray(FakeLauncherService.body().put("scriptId", "12")
                .put("reason", "not_entitled").put("httpStatus", HTTP_FORBIDDEN).build());
        serve(bundle, offer -> offer.put("delivered", ValueFactory.newArray(ValueFactory.newString("7")))
                .put("failed", failed));
        start();

        SdnBundleFetch.Fetched fetched = assertInstanceOf(SdnBundleFetch.Fetched.class,
                SdnBundleDownload.fetch(service, KEY, List.of("7", "12")));

        assertAll(() -> assertEquals(Optional.of(List.of("7")), fetched.delivered()),
                () -> assertEquals(List.of(new SdnScriptFailure("12", "not_entitled",
                        OptionalLong.of(HTTP_FORBIDDEN), Optional.empty())), fetched.failed()));
    }

    /** A list naming a script that was not asked for is refused before any chunk is fetched. */
    @Test
    void anOfferListingAnUnrequestedScript_isRefusedBeforeAnyChunk() throws Exception {
        serve(SdnBundleDownloadTest.bytes(CHUNK),
                offer -> offer.put("delivered", ValueFactory.newArray(ValueFactory.newString("99"))));
        start();

        assertInstanceOf(SdnBundleFetch.Failed.class, SdnBundleDownload.fetch(service, KEY, List.of("7")));
        assertEquals(List.of(), fake.received(LauncherProtocol.METHOD_SDN_BUNDLE_CHUNK));
    }

    /**
     * Note (b): a call the service never answers times out as "did not answer",
     * not as "not connected", and the next install works on the reconnected
     * session. Fails if a timeout leaves the service unusable.
     */
    @Test
    void aTimedOutOffer_isReportedAsATimeout_andTheNextFetchWorksAfterTheReconnect() throws Exception {
        byte[] bundle = SdnBundleDownloadTest.bytes(CHUNK);
        serve(bundle);
        AtomicInteger offers = new AtomicInteger();
        byte[] digest = SdnBundleDownloadTest.sha256(bundle);
        fake.handle(LauncherProtocol.METHOD_SDN_BUNDLE, body -> offers.getAndIncrement() == 0
                ? new FakeLauncherService.Reply.Silence()
                : FakeLauncherService.ok(FakeLauncherService.body().put("deliveryId", DELIVERY)
                        .put("size", bundle.length).put("sha256", ValueFactory.newBinary(digest))
                        .put("chunkSize", CHUNK).build()));
        start();

        SdnBundleFetch first = SdnBundleDownload.fetch(service, KEY, List.of("7"));
        FakeLauncherService.await("the re-registration", WAIT, () -> service.registrationCount() >= 2);
        FakeLauncherService.await("the reconnect", WAIT, service::isConnected);
        SdnBundleFetch second = SdnBundleDownload.fetch(service, KEY, List.of("7"));

        assertAll(() -> assertEquals(new SdnBundleFetch.Unavailable(true), first),
                () -> assertArrayEquals(bundle, assertInstanceOf(SdnBundleFetch.Fetched.class, second).bundle()));
    }

    /** Serves {@code bundle} the way the service does, refusing an index past its end. */
    private void serve(byte[] bundle) throws Exception {
        serve(bundle, UnaryOperator.identity());
    }

    /** As {@link #serve(byte[])}, with {@code offerExtras} adding fields to the offer. */
    private void serve(byte[] bundle, UnaryOperator<FakeLauncherService.Body> offerExtras) throws Exception {
        helloListing(LauncherProtocol.METHOD_SDN_BUNDLE, LauncherProtocol.METHOD_SDN_BUNDLE_CHUNK);
        byte[] digest = SdnBundleDownloadTest.sha256(bundle);
        fake.handle(LauncherProtocol.METHOD_SDN_BUNDLE, body -> FakeLauncherService.ok(offerExtras.apply(
                FakeLauncherService.body().put("deliveryId", DELIVERY).put("size", bundle.length)
                        .put("sha256", ValueFactory.newBinary(digest)).put("chunkSize", CHUNK)).build()));
        fake.handle(LauncherProtocol.METHOD_SDN_BUNDLE_CHUNK, body -> {
            long index = WireValues.integer(body, "index", -1L);
            if (index < 0 || index * CHUNK >= bundle.length) {
                return FakeLauncherService.error(LauncherProtocol.ERROR_SDN_NOT_DELIVERED);
            }
            int from = Math.toIntExact(index * CHUNK);
            byte[] data = Arrays.copyOfRange(bundle, from, Math.min(bundle.length, from + CHUNK));
            return FakeLauncherService.ok(FakeLauncherService.body().put("data", ValueFactory.newBinary(data))
                    .build());
        });
    }

    private void helloListing(String... methods) {
        List<Value> listed = Arrays.stream(methods).<Value>map(ValueFactory::newString).toList();
        fake.handle(LauncherProtocol.METHOD_HELLO, body -> FakeLauncherService.ok(FakeLauncherService.body()
                .put("protocolVersion", 1).put("serviceVersion", "fake").put("surface", "automation")
                .put("methods", ValueFactory.newArray(listed)).put("events", ValueFactory.newArray())
                .put("connectionId", 1).build()));
    }

    private void start() {
        service = new LauncherService(fake.opener(), new HelloParams("test", 1, Optional.empty()), () -> false,
                FAST, request -> { });
        service.start();
        FakeLauncherService.await("registration", WAIT, service::isConnected);
    }
}
