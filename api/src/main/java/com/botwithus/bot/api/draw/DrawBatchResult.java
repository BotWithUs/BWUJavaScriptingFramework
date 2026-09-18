package com.botwithus.bot.api.draw;

/**
 * What a batched submit actually achieved.
 *
 * <p><b>Read this, do not assume a batch succeeded.</b> {@code debug_draw_set_batch}
 * answers a normal success envelope even when it applied nothing: the per-item
 * failures come back as a count and the <i>first</i> error string only, never as
 * per-item {@code {key, error}}. A caller that checks only for a thrown RPC error
 * will read a 256-item batch that drew nothing as a success. That is why
 * {@link DrawFrame#flush()} returns this and why {@link DrawFrame#close()} logs a
 * warning rather than swallowing it — a script that believes it has a highlight
 * it does not have is the worst outcome available.</p>
 *
 * <p>The mirror of that is worth knowing too: <b>a batch that <i>does</i> fail at
 * the envelope level is not a clean failure.</b> The producer applies items to its
 * store as it walks them, so you get no result at all while part of the batch may
 * already be drawn. There is deliberately no {@code DrawBatchResult} for that case,
 * because the wire carries no count with the error — see {@link DrawFrame#flush()}
 * for what is recoverable and how.</p>
 *
 * @param applied    commands the producer accepted
 * @param dropped    commands the producer refused
 * @param firstError the producer's message for the first refusal, or {@code ""}
 *                   when nothing was refused. There is no second message: the
 *                   wire does not carry one.
 */
public record DrawBatchResult(int applied, int dropped, String firstError) {

    /** The result of flushing nothing. */
    public static final DrawBatchResult EMPTY = new DrawBatchResult(0, 0, "");

    public DrawBatchResult {
        firstError = firstError == null ? "" : firstError;
    }

    /** True when every command in the batch was accepted. */
    public boolean isComplete() {
        return dropped == 0;
    }

    /** Total commands the batch carried. */
    public int submitted() {
        return applied + dropped;
    }

    /**
     * This result combined with a later one, for a frame large enough to need
     * more than one {@link DrawLimits#MAX_BATCH_ITEMS}-item batch. The earliest
     * non-empty error wins, matching the single-batch contract that you are told
     * about the first refusal only.
     */
    public DrawBatchResult merge(DrawBatchResult next) {
        return new DrawBatchResult(applied + next.applied, dropped + next.dropped,
                firstError.isEmpty() ? next.firstError : firstError);
    }
}
