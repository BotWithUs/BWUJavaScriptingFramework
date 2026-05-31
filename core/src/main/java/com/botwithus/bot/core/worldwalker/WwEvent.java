package com.botwithus.bot.core.worldwalker;

/**
 * One progress event reported by the executor through
 * {@link WwCallbacks#onEvent}. Mirrors the C ABI {@code WwEvent} byte-for-byte
 * after decode; this record is heap-allocated for each emission, so it is
 * safe to retain past the callback.
 *
 * @param kind             decoded discriminator (see {@link WwEventKind})
 * @param rawKind          the raw {@code int32_t} the executor emitted; equals
 *                         {@code kind.wireValue()} for known kinds, otherwise
 *                         the unrecognised wire value (with {@code kind ==
 *                         WwEventKind.UNKNOWN})
 * @param stepIndex        index of the step the event refers to, or {@code -1}
 *                         if not applicable (e.g. {@link WwEventKind#ARRIVED})
 * @param transitionIndex  index of the transition the event refers to, or
 *                         {@code -1} if not applicable
 */
public record WwEvent(
        WwEventKind kind,
        int rawKind,
        int stepIndex,
        int transitionIndex) {

    /** Sentinel emitted by the executor when a step/transition index is N/A. */
    public static final int INDEX_NA = -1;
}
