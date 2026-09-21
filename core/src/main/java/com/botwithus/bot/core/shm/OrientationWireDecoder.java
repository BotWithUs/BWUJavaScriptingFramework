package com.botwithus.bot.core.shm;

import com.botwithus.bot.api.snapshot.Orientation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

/**
 * Turns the producer's {@code u16} facing field into an {@link Orientation} for the snapshot
 * decode path, and reports a broken producer invariant at most once.
 *
 * <p>An out-of-contract value decodes as unknown ({@link Orientation#fromWire}); this class
 * adds the rate limit. The same bad value can appear on many rows and many ticks, and a
 * per-row warning would bury the log, so only the first one per decoder instance is
 * reported. Create one per attached session, so that each session reports its own first
 * occurrence.</p>
 */
public final class OrientationWireDecoder {

    private static final Logger log = LoggerFactory.getLogger(OrientationWireDecoder.class);

    private final AtomicBoolean hasReported = new AtomicBoolean();
    private final IntConsumer report;

    /** A decoder that reports the first out-of-contract value as a {@code WARN}. */
    public OrientationWireDecoder() {
        this(value -> log.warn(
                "producer published orientation {} (neither 0..{} nor 0x{}); decoding it as "
                        + "unknown. Further occurrences this session are not logged.",
                value, Orientation.FULL_TURN - 1, Integer.toHexString(Orientation.WIRE_UNKNOWN)));
    }

    /** A decoder that hands the first out-of-contract value to {@code report}. */
    public OrientationWireDecoder(IntConsumer report) {
        this.report = report;
    }

    /** Decodes one zero-extended {@code u16}. Never throws. */
    public Orientation decode(int wireValue) {
        return Orientation.fromWire(wireValue, this::reportOnce);
    }

    private void reportOnce(int value) {
        if (hasReported.compareAndSet(false, true)) {
            report.accept(value);
        }
    }
}
