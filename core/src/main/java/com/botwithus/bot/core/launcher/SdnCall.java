package com.botwithus.bot.core.launcher;

/**
 * How one call of the SDN methods ended. A refusal is an answer like any other,
 * which is why this is a value and not an exception: every way it can end has to
 * reach the user as its own message.
 *
 * @param <T> what an answered call carries
 */
public sealed interface SdnCall<T> {

    /** The service answered. */
    record Answered<T>(T value) implements SdnCall<T> {}

    /** The service answered {@code sdn_not_delivered}, with whatever it said about why. */
    record Refused<T>(SdnRefusal refusal) implements SdnCall<T> {}

    /**
     * No answer came.
     *
     * @param isTimedOut whether the service was connected but did not answer in time,
     *                   rather than not connected at all
     */
    record Unavailable<T>(boolean isTimedOut) implements SdnCall<T> {}

    /** Any other error code from the service. */
    record Failed<T>(String code) implements SdnCall<T> {}
}
