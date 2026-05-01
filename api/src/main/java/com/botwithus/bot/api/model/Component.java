package com.botwithus.bot.api.model;

/**
 * Snapshot of an interface component at the moment the producer answered
 * an RPC. Phase 1 starter set — fields cover identity and base-Component
 * geometry (everything Component::DecodeType writes from the if3 packet
 * plus what LayoutComponents writes back into screen_*). Per-type fields
 * (text, sprite id, item slot, hidden bit, color, font) are not yet here
 * and arrive in later slices once each Component subclass has been mapped.
 *
 * <p>Lifetime: every {@link com.botwithus.bot.api.GameAPI#getComponent} call
 * is a fresh pipe round-trip; this record reflects the component state at
 * one specific tick. Don't cache across ticks — Phase 2 will add a
 * version-keyed cache once setter hooks land.</p>
 *
 * @param ifaceId       owning interface id; the producer reads the game's
 *                      {@code 0xFFFF} sentinel as {@code -1}
 * @param compId        this component's id within {@code ifaceId}
 * @param subId         sub-selector / option index (used by some component types)
 * @param type          component type byte from {@code vtable[1](comp)}; -1
 *                      on resolution failure. Different types stash their
 *                      type-specific fields (text, sprite id, item slot,
 *                      hidden bit) at different offsets, so this is the
 *                      dispatch key any per-type read needs. Some known
 *                      values: type 8 is ButtonComponent.
 * @param x             post-layout screen-space x (pixels)
 * @param y             post-layout screen-space y
 * @param width         post-layout computed width
 * @param height        post-layout computed height
 * @param rawX          pre-layout raw config x — what the cache stored before
 *                      {@code CalcPositionFromMode} converted it
 * @param rawY          pre-layout raw config y
 * @param rawW          pre-layout raw config width
 * @param rawH          pre-layout raw config height
 * @param xPosMode      anchor mode for {@code rawX} (0-5; pixel / percent /
 *                      anchored-from-edge / etc.)
 * @param yPosMode      anchor mode for {@code rawY}
 * @param xSizeMode     interpretation of {@code rawW} (0-4; mode 4 = aspect
 *                      ratio of {@code rawH}, see also the unexposed
 *                      {@code aspect_w}/{@code aspect_h} numerator/denominator
 *                      pair on the producer side)
 * @param ySizeMode     interpretation of {@code rawH}
 * @param absScreenPos  flag — when nonzero, position is absolute screen
 *                      coordinates regardless of parent
 * @param text          embedded label text for components that carry one
 *                      (Button / List / Input / Slider / Carousel /
 *                      Component19 / RadioGroup / GroupBox plus categories
 *                      2 and 9). Empty string when this category doesn't
 *                      carry text or when the embedded string is empty.
 *                      Never null.
 * @param hidden        tri-state visibility flag:
 *                      {@code -1} = unsupported (category has no text
 *                      region — Panel, Grid, Sprite, Box, Line, etc. —
 *                      until those mechanisms are reverse-engineered);
 *                      {@code 0} = visible;
 *                      {@code 1} = hidden.
 *                      Use {@link #isHidden()} for a forgiving boolean
 *                      that treats unsupported as visible.
 * @param spriteId      primary visual reference id — sprite id for Sprite,
 *                      icon sprite for Button (distinct from the button's
 *                      label text), and similarly for Input. {@code -1}
 *                      means either the category carries no graphic
 *                      sub-region (per sub_CA450's dispatch) or the
 *                      field is set to "none" / 0xFFFFFFFF.
 * @see com.botwithus.bot.api.GameAPI#getComponent
 */
public record Component(
        int ifaceId,
        int compId,
        int subId,
        int type,
        int x,
        int y,
        int width,
        int height,
        int rawX,
        int rawY,
        int rawW,
        int rawH,
        int xPosMode,
        int yPosMode,
        int xSizeMode,
        int ySizeMode,
        int absScreenPos,
        String text,
        int hidden,
        int spriteId) {

    /** True iff the producer reported this component is hidden ({@code hidden == 1}). */
    public boolean isHidden() { return hidden == 1; }
}
