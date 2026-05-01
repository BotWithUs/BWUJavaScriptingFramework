package com.botwithus.bot.api.model;

/**
 * Snapshot of an interface component at the moment the producer answered
 * an RPC. Phase 1 starter set — fields cover identity and post-layout
 * geometry; per-type fields (text, sprite id, item slot, hidden bit, color,
 * font) are not yet here and will land as later slices reverse-engineer
 * the per-component-type Decode functions.
 *
 * <p>Lifetime: every {@link com.botwithus.bot.api.GameAPI#getComponent} call
 * is a fresh pipe round-trip; this record reflects the component state at
 * one specific tick. Don't cache across ticks — Phase 2 will add a
 * version-keyed cache once setter hooks land.</p>
 *
 * @param ifaceId     owning interface id; the producer reads the game's
 *                    {@code 0xFFFF} sentinel as {@code -1}
 * @param compId      this component's id within {@code ifaceId}
 * @param subId       sub-selector / option index (used by some component types)
 * @param x           post-layout screen-space x (pixels)
 * @param y           post-layout screen-space y
 * @param width       post-layout computed width
 * @param height      post-layout computed height
 * @param rawX        pre-layout raw config x — what the cache stored before
 *                    {@code CalcPositionFromMode} converted it
 * @param rawY        pre-layout raw config y
 * @param widthMode   how width is interpreted (absolute / percent-of-parent /
 *                    anchored); semantics match the in-game enum
 * @param heightMode  same for height
 * @param posMode     positioning mode flag
 * @see com.botwithus.bot.api.GameAPI#getComponent
 */
public record Component(
        int ifaceId,
        int compId,
        int subId,
        int x,
        int y,
        int width,
        int height,
        int rawX,
        int rawY,
        int widthMode,
        int heightMode,
        int posMode) {}
