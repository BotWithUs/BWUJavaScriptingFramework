package com.botwithus.bot.cli.gui.usermode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The pulse lane's numbers, separate from its drawing so they can be tested
 * without a GL context: which slot each recent loop lands in, how tall its bar
 * is, and whether it is a spike.
 *
 * <p>The lane always has {@link #SLOTS} slots, newest on the right. With fewer
 * samples than slots the left end stays empty rather than stretching the bars.</p>
 */
public final class PulseLane {

    /** Bars in a lane. Matches the host-side loop history's capacity. */
    public static final int SLOTS = 24;

    /** A loop slower than this multiple of the average is drawn amber. */
    public static final double SPIKE_FACTOR = 2.0;

    /** Head-room above the tallest bar, so the tallest never touches the top. */
    private static final double HEADROOM = 1.1;

    /** The shortest a bar is drawn, as a fraction of the lane, so fast loops stay visible. */
    private static final float MIN_HEIGHT = 0.08f;

    private static final double NANOS_PER_MS = 1_000_000.0;

    /**
     * One bar.
     *
     * @param height fraction of the lane height, in {@code [MIN_HEIGHT, 1)}
     * @param spike  slower than {@link #SPIKE_FACTOR} times the average
     * @param older  in the older half of the lane, drawn dimmer
     */
    public record Bar(int slot, float height, boolean spike, boolean older) {}

    private PulseLane() {}

    /** True when {@code sampleMs} is more than {@link #SPIKE_FACTOR} times {@code averageMs}. */
    public static boolean isSpike(double sampleMs, double averageMs) {
        return averageMs > 0 && sampleMs > averageMs * SPIKE_FACTOR;
    }

    /**
     * Lays out {@code recentNanos} (oldest first) into bars. Only the newest
     * {@link #SLOTS} samples are used.
     *
     * @param averageMs the average the card shows; spikes are measured against it
     */
    public static List<Bar> bars(long[] recentNanos, double averageMs) {
        int count = Math.min(recentNanos.length, SLOTS);
        if (count == 0) {
            return List.of();
        }
        int from = recentNanos.length - count;
        double max = 0;
        for (int i = from; i < recentNanos.length; i++) {
            max = Math.max(max, recentNanos[i]);
        }
        double scale = max * HEADROOM;
        List<Bar> bars = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long nanos = recentNanos[from + i];
            int slot = SLOTS - count + i;
            float height = scale > 0 ? (float) Math.max(MIN_HEIGHT, nanos / scale) : MIN_HEIGHT;
            boolean spike = isSpike(nanos / NANOS_PER_MS, averageMs);
            bars.add(new Bar(slot, height, spike, slot < SLOTS / 2));
        }
        return Collections.unmodifiableList(bars);
    }
}
