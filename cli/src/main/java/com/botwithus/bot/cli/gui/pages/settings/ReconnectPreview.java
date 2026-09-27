package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.core.rpc.ReconnectPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.stream.Collectors;

/**
 * The waits a reconnect policy makes between tries, for the preview chart under
 * the Reconnecting settings, and one sentence saying the same in words.
 *
 * @param waitsMs the wait before each of the first tries, in order
 * @param summary e.g. "Waits 500 ms, 1 s, 2 s, 4 s… then every 15 s, until the client is back. ..."
 */
public record ReconnectPreview(List<Long> waitsMs, String summary) {

    /** How many tries the chart shows when the policy has no lower cap. */
    public static final int TRIES_SHOWN = 10;

    /** How many waits the sentence names before it summarises the rest. */
    private static final int WAITS_NAMED = 4;

    public ReconnectPreview {
        waitsMs = List.copyOf(waitsMs);
    }

    /** The preview of {@code policy}, computed with the policy's own back-off. */
    public static ReconnectPreview of(ReconnectPolicy policy) {
        OptionalInt limit = policy.attemptLimit();
        int tries = limit.isPresent() ? Math.min(limit.getAsInt(), TRIES_SHOWN) : TRIES_SHOWN;
        List<Long> waits = new ArrayList<>(tries);
        for (int attempt = 1; attempt <= tries; attempt++) {
            waits.add(policy.delayForAttempt(attempt));
        }
        return new ReconnectPreview(waits, describe(policy, waits, limit));
    }

    /** The longest wait shown, for scaling the bars. */
    public long longestMs() {
        return waitsMs.stream().mapToLong(Long::longValue).max().orElse(0L);
    }

    private static String describe(ReconnectPolicy policy, List<Long> waits, OptionalInt limit) {
        String named = waits.stream().limit(WAITS_NAMED).map(WaitText::of).collect(Collectors.joining(", "));
        StringBuilder text = new StringBuilder("Waits ").append(named);
        if (waits.size() > WAITS_NAMED) {
            boolean isCapped = waits.getLast() >= policy.maxDelayMs();
            text.append(isCapped ? "… then every " : "… growing to at most ")
                    .append(WaitText.of(policy.maxDelayMs()));
        }
        text.append(limit.isPresent() ? ", up to " + limit.getAsInt() + " tries." : ", until the client is back.");
        return text.append(' ').append(total(waits, limit)).toString();
    }

    /** "The first 10 tries take 74 s." or, when that is every try, "All 3 tries take 3.5 s." */
    private static String total(List<Long> waits, OptionalInt limit) {
        long totalMs = waits.stream().mapToLong(Long::longValue).sum();
        boolean isEveryTry = limit.isPresent() && limit.getAsInt() <= TRIES_SHOWN;
        String tries = waits.size() == 1 ? "1 try takes " : waits.size() + " tries take ";
        return (isEveryTry ? "All " : "The first ") + tries + WaitText.of(totalMs) + ".";
    }
}
