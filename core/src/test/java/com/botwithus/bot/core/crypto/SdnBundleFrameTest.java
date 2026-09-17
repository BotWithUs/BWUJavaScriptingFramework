package com.botwithus.bot.core.crypto;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Framing tests for the multi-bundle courier payload.
 *
 * <p>This is a cross-language wire format: the launcher writes it in C++, the
 * reference producer emits it in Go (BotWithUs-Heartbeat {@code cmd/sdnbundle}),
 * and this parses it in Java. A disagreement between any two of those does not
 * announce itself — the observable symptom on this side is a 30-second timeout
 * or a silently short list, both of which read as "the courier isn't running".
 * So every rejection path is pinned here rather than left to integration.
 *
 * <p>These stop at the frame boundary on purpose. Everything past it calls
 * {@code SdnLoader.defineLoader}, which needs the patched JDK, so a test that
 * went further could not run on a stock toolchain.
 */
class SdnBundleFrameTest {

    private static final int ENVELOPE_LEN = 168;

    private static byte[] envelope(int fill) {
        byte[] envelope = new byte[ENVELOPE_LEN];
        Arrays.fill(envelope, (byte) fill);
        return envelope;
    }

    /** Writes the agreed framing: u32 count, envelope[168], N x (u32 len, jar). */
    private static byte[] frame(byte[] envelope, byte[]... jars) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.BIG_ENDIAN)
                .putInt(jars.length).array());
        out.writeBytes(envelope);
        for (byte[] jar : jars) {
            out.writeBytes(ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.BIG_ENDIAN)
                    .putInt(jar.length).array());
            out.writeBytes(jar);
        }
        return out.toByteArray();
    }

    @Test
    void parseFrame_threeJars_returnsAllInOrderWithOneSharedEnvelope() {
        byte[] envelope = envelope(0x5A);
        byte[] first = {1, 2, 3};
        byte[] second = {4, 5};
        byte[] third = {6};

        Optional<SdnDiskBundleSource.BundleFrame> parsed =
                SdnDiskBundleSource.parseFrame(frame(envelope, first, second, third));

        assertTrue(parsed.isPresent());
        List<byte[]> jars = parsed.get().jars();
        assertEquals(3, jars.size());
        assertArrayEquals(first, jars.get(0));
        assertArrayEquals(second, jars.get(1));
        assertArrayEquals(third, jars.get(2));
        // One envelope covers every jar: the content key is per-session.
        assertArrayEquals(envelope, parsed.get().envelope());
    }

    @Test
    void parseFrame_singleJar_stillParses() {
        byte[] only = {9, 9, 9, 9};
        Optional<SdnDiskBundleSource.BundleFrame> parsed =
                SdnDiskBundleSource.parseFrame(frame(envelope(1), only));

        assertTrue(parsed.isPresent());
        assertEquals(1, parsed.get().jars().size());
        assertArrayEquals(only, parsed.get().jars().get(0));
    }

    @Test
    void parseFrame_emptyJarInTheSet_isKeptNotSkipped() {
        // A zero-length jar is a courier bug, but swallowing it here would make
        // the count and the list disagree, which is worse to diagnose.
        Optional<SdnDiskBundleSource.BundleFrame> parsed =
                SdnDiskBundleSource.parseFrame(frame(envelope(2), new byte[0], new byte[]{7}));

        assertTrue(parsed.isPresent());
        assertEquals(2, parsed.get().jars().size());
        assertEquals(0, parsed.get().jars().get(0).length);
    }

    @Test
    void parseFrame_shorterThanCountPlusEnvelope_isRejected() {
        assertTrue(SdnDiskBundleSource.parseFrame(new byte[0]).isEmpty());
        assertTrue(SdnDiskBundleSource.parseFrame(new byte[Integer.BYTES]).isEmpty());
        assertTrue(SdnDiskBundleSource.parseFrame(
                new byte[Integer.BYTES + ENVELOPE_LEN - 1]).isEmpty());
    }

    @Test
    void parseFrame_zeroOrNegativeCount_isRejected() {
        assertTrue(SdnDiskBundleSource.parseFrame(frame(envelope(3))).isEmpty());

        byte[] negative = frame(envelope(3), new byte[]{1});
        ByteBuffer.wrap(negative).order(ByteOrder.BIG_ENDIAN).putInt(0, -1);
        assertTrue(SdnDiskBundleSource.parseFrame(negative).isEmpty());
    }

    @Test
    void parseFrame_countBeyondTheSanityBound_isRejectedWithoutAllocating() {
        byte[] absurd = frame(envelope(4), new byte[]{1});
        ByteBuffer.wrap(absurd).order(ByteOrder.BIG_ENDIAN).putInt(0, Integer.MAX_VALUE);
        assertTrue(SdnDiskBundleSource.parseFrame(absurd).isEmpty());
    }

    @Test
    void parseFrame_truncatedMidJar_isRejected() {
        byte[] full = frame(envelope(5), new byte[]{1, 2, 3, 4, 5});
        byte[] truncated = Arrays.copyOf(full, full.length - 2);
        assertTrue(SdnDiskBundleSource.parseFrame(truncated).isEmpty());
    }

    @Test
    void parseFrame_jarLengthOverrunningTheBuffer_isRejected() {
        byte[] overrun = frame(envelope(6), new byte[]{1, 2});
        // Rewrite the first jar's length to claim far more than is present.
        ByteBuffer.wrap(overrun).order(ByteOrder.BIG_ENDIAN)
                .putInt(Integer.BYTES + ENVELOPE_LEN, 4096);
        assertTrue(SdnDiskBundleSource.parseFrame(overrun).isEmpty());
    }

    @Test
    void parseFrame_trailingBytes_isRejected() {
        // A writer that appends anything after the last jar disagrees with this
        // parser about the format, and silently ignoring the tail would hide it.
        byte[] full = frame(envelope(7), new byte[]{1, 2, 3});
        byte[] padded = Arrays.copyOf(full, full.length + 1);
        assertTrue(SdnDiskBundleSource.parseFrame(padded).isEmpty());
    }

    @Test
    void parseFrame_countHigherThanJarsPresent_isRejected() {
        // The launcher writing a count of 3 but only two jars must not yield a
        // quietly-short list of 2.
        byte[] bundle = frame(envelope(8), new byte[]{1}, new byte[]{2});
        ByteBuffer.wrap(bundle).order(ByteOrder.BIG_ENDIAN).putInt(0, 3);
        assertTrue(SdnDiskBundleSource.parseFrame(bundle).isEmpty());
    }

    /**
     * The cross-language test. Every other case here is this file's own writer
     * checked against this file's own reader, which only proves self-consistency.
     * This fixture was produced by the Go reference producer
     * ({@code BotWithUs-Heartbeat cmd/sdnbundle deliver}) and committed verbatim,
     * so it fails if either side's framing drifts.
     */
    @Test
    void parseFrame_bytesFromTheGoReferenceProducer_parseExactly() throws Exception {
        byte[] bundle;
        try (InputStream in = SdnBundleFrameTest.class
                .getResourceAsStream("/sdn/delivery-2jar.sdn")) {
            assertNotNull(in, "fixture /sdn/delivery-2jar.sdn is missing from test resources");
            bundle = in.readAllBytes();
        }

        Optional<SdnDiskBundleSource.BundleFrame> parsed =
                SdnDiskBundleSource.parseFrame(bundle);

        assertTrue(parsed.isPresent(), "the Go producer's framing was rejected by this parser");
        assertEquals(2, parsed.get().jars().size());
        assertArrayEquals("JAR-ONE-CONTENT".getBytes(StandardCharsets.UTF_8),
                parsed.get().jars().get(0));
        assertArrayEquals("JAR-TWO".getBytes(StandardCharsets.UTF_8),
                parsed.get().jars().get(1));
        assertEquals(ENVELOPE_LEN, parsed.get().envelope().length);
    }
}
