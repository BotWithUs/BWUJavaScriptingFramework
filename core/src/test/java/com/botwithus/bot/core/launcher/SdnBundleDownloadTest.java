package com.botwithus.bot.core.launcher;

import com.botwithus.bot.api.script.LauncherException;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The host half of amendment B1's download: one offer, every chunk in order,
 * and a bundle that is accepted only when its length and digest match the offer.
 */
class SdnBundleDownloadTest {

    private static final int CHUNK = 1000;
    private static final int SHORT_TAIL = 345;
    private static final long DELIVERY = 7;
    private static final byte[] KEY = new byte[LauncherProtocol.SDN_PUBLIC_KEY_BYTES];
    private static final List<String> IDS = List.of("7", "12");

    @Test
    void reassemblesEveryChunk_includingAShortLastOne() {
        byte[] bundle = bytes(2 * CHUNK + SHORT_TAIL);
        ServedBundle served = new ServedBundle(bundle, CHUNK);
        SdnBundleFetch.Fetched fetched = assertInstanceOf(SdnBundleFetch.Fetched.class,
                SdnBundleDownload.fetch(served, KEY, IDS));
        assertAll(() -> assertArrayEquals(bundle, fetched.bundle()),
                () -> assertEquals(List.of(0L, 1L, 2L), served.requestedIndices),
                () -> assertArrayEquals(KEY, served.publicKey),
                () -> assertEquals(IDS, served.scriptIds));
    }

    @Test
    void reassemblesABundleThatIsAnExactMultipleOfTheChunkSize() {
        byte[] bundle = bytes(3 * CHUNK);
        ServedBundle served = new ServedBundle(bundle, CHUNK);
        SdnBundleFetch.Fetched fetched = assertInstanceOf(SdnBundleFetch.Fetched.class,
                SdnBundleDownload.fetch(served, KEY, IDS));
        assertAll(() -> assertArrayEquals(bundle, fetched.bundle()),
                () -> assertEquals(List.of(0L, 1L, 2L), served.requestedIndices));
    }

    @Test
    void aBundleThatDoesNotMatchItsDigest_isRefused() {
        ServedBundle served = new ServedBundle(bytes(2 * CHUNK + SHORT_TAIL), CHUNK);
        served.tamper = chunk -> {
            byte[] changed = chunk.clone();
            changed[0] ^= 1;
            return changed;
        };
        SdnBundleFetch.Failed failed = assertInstanceOf(SdnBundleFetch.Failed.class,
                SdnBundleDownload.fetch(served, KEY, IDS));
        assertEquals("the bundle did not match its SHA-256", failed.reason());
    }

    @Test
    void aChunkLongerThanItsPlace_isRefusedAndNothingMoreIsAsked() {
        ServedBundle served = new ServedBundle(bytes(2 * CHUNK + SHORT_TAIL), CHUNK);
        served.tamper = chunk -> Arrays.copyOf(chunk, chunk.length + 1);
        assertInstanceOf(SdnBundleFetch.Failed.class, SdnBundleDownload.fetch(served, KEY, IDS));
        assertEquals(List.of(0L), served.requestedIndices);
    }

    @Test
    void aShortChunkBeforeTheLast_isRefused() {
        ServedBundle served = new ServedBundle(bytes(2 * CHUNK + SHORT_TAIL), CHUNK);
        served.tamper = chunk -> Arrays.copyOf(chunk, chunk.length - 1);
        assertInstanceOf(SdnBundleFetch.Failed.class, SdnBundleDownload.fetch(served, KEY, IDS));
        assertEquals(List.of(0L), served.requestedIndices);
    }

    @Test
    void anOfferOutsideTheContract_isRefusedBeforeAnyChunkIsAsked() {
        byte[] digest = new byte[LauncherProtocol.SDN_SHA256_BYTES];
        List<SdnBundleOffer> offers = List.of(
                new SdnBundleOffer(0, CHUNK, digest, CHUNK),
                new SdnBundleOffer(DELIVERY, 0, digest, CHUNK),
                new SdnBundleOffer(DELIVERY, LauncherProtocol.SDN_MAX_BUNDLE_BYTES + 1, digest, CHUNK),
                new SdnBundleOffer(DELIVERY, CHUNK, digest, 0),
                new SdnBundleOffer(DELIVERY, CHUNK, digest, LauncherProtocol.MAX_BODY_BYTES + 1),
                new SdnBundleOffer(DELIVERY, CHUNK, new byte[LauncherProtocol.SDN_SHA256_BYTES - 1], CHUNK));
        for (SdnBundleOffer offer : offers) {
            ServedBundle served = new ServedBundle(bytes(CHUNK), CHUNK);
            served.offer = offer;
            assertInstanceOf(SdnBundleFetch.Failed.class, SdnBundleDownload.fetch(served, KEY, IDS),
                    offer.toString());
            assertEquals(List.of(), served.requestedIndices, offer.toString());
        }
    }

    @Test
    void sdnNotDelivered_isNotDelivered_atTheOfferOrAtAChunk() {
        ServedBundle refusesOffer = new ServedBundle(bytes(CHUNK), CHUNK);
        refusesOffer.offerError = LauncherProtocol.ERROR_SDN_NOT_DELIVERED;
        ServedBundle refusesChunk = new ServedBundle(bytes(2 * CHUNK), CHUNK);
        refusesChunk.chunkError = LauncherProtocol.ERROR_SDN_NOT_DELIVERED;
        assertAll(
                () -> assertInstanceOf(SdnBundleFetch.NotDelivered.class,
                        SdnBundleDownload.fetch(refusesOffer, KEY, IDS)),
                () -> assertInstanceOf(SdnBundleFetch.NotDelivered.class,
                        SdnBundleDownload.fetch(refusesChunk, KEY, IDS)));
    }

    @Test
    void aLostService_isUnavailable_andAnyOtherCodeFailsWithTheCodeOnly() {
        ServedBundle lost = new ServedBundle(bytes(CHUNK), CHUNK);
        lost.offerError = LauncherException.SERVICE_UNAVAILABLE;
        ServedBundle refused = new ServedBundle(bytes(CHUNK), CHUNK);
        refused.offerError = "bad_request";
        assertAll(
                () -> assertEquals(new SdnBundleFetch.Unavailable(LauncherException.SERVICE_UNAVAILABLE),
                        SdnBundleDownload.fetch(lost, KEY, IDS)),
                () -> assertEquals(new SdnBundleFetch.Failed("bad_request"),
                        SdnBundleDownload.fetch(refused, KEY, IDS)));
    }

    static byte[] bytes(int length) {
        byte[] b = new byte[length];
        new Random(length).nextBytes(b);
        return b;
    }

    static byte[] sha256(byte[] bytes) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(bytes);
    }

    /** Serves one bundle as the launcher would, with seams for misbehaving. */
    static final class ServedBundle implements SdnBundleChannel {

        final List<Long> requestedIndices = new ArrayList<>();
        SdnBundleOffer offer;
        UnaryOperator<byte[]> tamper = UnaryOperator.identity();
        String offerError;
        String chunkError;
        byte[] publicKey;
        List<String> scriptIds;
        private final byte[] bundle;
        private final int chunkSize;

        ServedBundle(byte[] bundle, int chunkSize) {
            this.bundle = bundle.clone();
            this.chunkSize = chunkSize;
            try {
                this.offer = new SdnBundleOffer(DELIVERY, bundle.length, sha256(bundle), chunkSize);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public boolean offersSdnBundles() {
            return true;
        }

        @Override
        public SdnBundleOffer requestSdnBundle(byte[] key, List<String> ids) {
            publicKey = key.clone();
            scriptIds = List.copyOf(ids);
            if (offerError != null) {
                throw new LauncherException(offerError, "sdn.bundle: refused");
            }
            return offer;
        }

        @Override
        public byte[] sdnBundleChunk(long deliveryId, long index) {
            requestedIndices.add(index);
            if (chunkError != null || deliveryId != DELIVERY || index * chunkSize >= bundle.length) {
                throw new LauncherException(chunkError != null ? chunkError
                        : LauncherProtocol.ERROR_SDN_NOT_DELIVERED, "sdn.bundle_chunk: refused");
            }
            int from = Math.toIntExact(index * chunkSize);
            return tamper.apply(Arrays.copyOfRange(bundle, from, Math.min(bundle.length, from + chunkSize)));
        }
    }
}
