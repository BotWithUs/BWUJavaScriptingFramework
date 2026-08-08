package com.botwithus.bot.core.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * Regression tests for a key-envelope signature-verification bypass
 * (internal tracker #119).
 *
 * <p>A key envelope must never authenticate under a degenerate signature, for
 * any message body. These tests assert that property directly and require no
 * network or server rig; they are skipped when not running on the custom JDK.
 */
class SdnForgedEnvelopeTest {

    private static final int ENVELOPE_LEN = 168;
    private static final int ENVELOPE_BODY_LEN = 104;

    @Test
    void rejectsAllZeroSignature() {
        assumeTrue(sdnClassLoaderPresent(),
                "requires the custom JDK with jdk.internal.sdn.SdnClassLoader");

        byte[] envelope = new byte[ENVELOPE_LEN];
        // Well-formed body, degenerate signature.
        for (int i = 0; i < ENVELOPE_BODY_LEN; i++) {
            envelope[i] = (byte) (i * 7 + 3);
        }

        assertAllZeroSignature(envelope);

        // Must not yield a loader. Any rejection reason is acceptable — the
        // observable outcome is what matters: throw, never return a loader.
        assertThrows(RuntimeException.class,
                () -> SdnLoader.defineLoader(new byte[0], envelope,
                        getClass().getClassLoader()),
                "an all-zero signature must never authenticate a key envelope");
    }

    @Test
    void rejectsAllZeroSignatureOverManyDistinctBodies() {
        assumeTrue(sdnClassLoaderPresent(),
                "requires the custom JDK with jdk.internal.sdn.SdnClassLoader");

        // Rejection must not depend on the body, so vary it and re-assert.
        for (int round = 0; round < 16; round++) {
            byte[] envelope = new byte[ENVELOPE_LEN];
            for (int i = 0; i < ENVELOPE_BODY_LEN; i++) {
                envelope[i] = (byte) (i * 31 + round * 101);
            }
            assertAllZeroSignature(envelope);

            int attempt = round;
            assertThrows(RuntimeException.class,
                    () -> SdnLoader.defineLoader(new byte[0], envelope,
                            getClass().getClassLoader()),
                    "zero signature accepted for body variant " + attempt);
        }
    }

    @Test
    void rejectsWrongLengthEnvelope() {
        assumeTrue(sdnClassLoaderPresent(),
                "requires the custom JDK with jdk.internal.sdn.SdnClassLoader");

        for (int length : new int[] {0, 1, ENVELOPE_BODY_LEN, ENVELOPE_LEN - 1, ENVELOPE_LEN + 1}) {
            byte[] envelope = new byte[length];
            assertThrows(RuntimeException.class,
                    () -> SdnLoader.defineLoader(new byte[0], envelope,
                            getClass().getClassLoader()),
                    "envelope of length " + length + " must be rejected");
        }
    }

    /** Guards the test's own premise: the signature really is all zeros. */
    private static void assertAllZeroSignature(byte[] envelope) {
        assertEquals(ENVELOPE_LEN, envelope.length, "envelope length");
        byte[] signature = Arrays.copyOfRange(envelope, ENVELOPE_BODY_LEN, ENVELOPE_LEN);
        for (byte b : signature) {
            assertTrue(b == 0, "test bug: signature is not all-zero");
        }
    }

    private static boolean sdnClassLoaderPresent() {
        try {
            Class.forName("jdk.internal.sdn.SdnClassLoader");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
