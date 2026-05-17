package com.botwithus.bot.core.util;

import java.util.function.Function;

/**
 * Shared {@link Throwable} rethrow helper for Panama downcall sites.
 *
 * <p>{@link java.lang.invoke.MethodHandle#invokeExact} is declared
 * {@code throws Throwable}; every caller is therefore forced to handle a
 * raw {@code Throwable} and route it to one of three buckets — preserve
 * the existing {@link RuntimeException}, rethrow an {@link Error}
 * unchanged, or wrap anything else in a domain-specific unchecked
 * exception. The {@code instanceof RuntimeException} / {@code instanceof
 * Error} idiom is the supported way to do that and is the one accepted
 * exception to {@code java-rules} §Banned 10 (no {@code instanceof}
 * outside sealed-{@code switch} dispatch) — the alternative would be a
 * cast plus a hand-rolled type check, which is strictly worse.
 *
 * <p>This helper centralises that idiom so callers (Panama wrappers in
 * {@code BwuClient}, {@code NXTCache}, ...) supply only the
 * domain-specific wrapper for the "unknown {@code Throwable}" case.
 */
public final class Throwables {

    private Throwables() {}

    /**
     * Convert a {@link Throwable} caught around a Panama downcall into a
     * {@link RuntimeException} the caller can throw, propagating
     * {@link Error}s unchanged.
     *
     * <p>Behaviour:
     * <ul>
     *   <li>{@link RuntimeException} — returned as-is.</li>
     *   <li>{@link Error} — rethrown immediately (this method does not
     *       return in that case).</li>
     *   <li>anything else — passed to {@code wrap} for a domain-specific
     *       unchecked exception.</li>
     * </ul>
     *
     * @param t    the caught throwable
     * @param wrap factory that wraps an unknown throwable in a
     *             domain-specific unchecked exception (cause preserved)
     * @return the {@link RuntimeException} to throw at the call site
     */
    public static RuntimeException rethrow(Throwable t, Function<Throwable, RuntimeException> wrap) {
        if (t instanceof RuntimeException re) {
            return re;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return wrap.apply(t);
    }
}
