package com.botwithus.bot.api.model;

import java.util.List;

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
 * @param category      stable, build-independent semantic category code
 *                      (mirrors the producer's {@code WireCategory}); the
 *                      durable form to query on. Map it with
 *                      {@link com.botwithus.bot.api.component.ComponentType#fromCode(int)}.
 *                      Prefer this over {@code type} — the raw {@code type}
 *                      byte drifts between game builds. {@code 0} (Unknown)
 *                      when read from a producer that predates this field.
 * @param x             absolute client-space x (pixels) — the producer resolves
 *                      the parent-component chain and the sub-interface mount
 *                      offset, so this is where the component actually sits in
 *                      the game's client area, not a parent-relative offset
 * @param y             absolute client-space y (pixels); see {@code x}
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
 * @param hidden        the component's own visibility as an int, every
 *                      category: {@code 0} = visible by its own flag,
 *                      {@code 1} = hidden by its own flag, {@code -1} =
 *                      unknown (not found, or a client channel that cannot
 *                      read the visibility offsets). Since v24 this is
 *                      {@code visible ? 0 : 1}; before, it was a text-region
 *                      bit that read {@code -1} on every category without
 *                      text and got some text components wrong. Use
 *                      {@link #isHidden()} for the boolean, and
 *                      {@link #isVisibleGlobal()} to ask whether the
 *                      component is actually on screen.
 * @param spriteId      primary visual reference id — sprite id for Sprite,
 *                      icon sprite for Button (distinct from the button's
 *                      label text), and similarly for Input. {@code -1}
 *                      means either the category carries no graphic
 *                      sub-region (per sub_CA450's dispatch) or the
 *                      field is set to "none" / 0xFFFFFFFF.
 * @param itemId        ModelComponent (cat 4) primary id. For inventory-
 *                      slot displays this is the displayed item's id; for
 *                      NPC / object viewer interfaces it's a model_id.
 *                      {@code -1} for non-ModelComponents. Always prefer
 *                      the per-tick {@code Snapshot.inventoryAt(...)}
 *                      path for actual backpack/bank reads — that comes
 *                      from {@code InventoryManager} directly without an
 *                      RPC round-trip and without depending on whether
 *                      the inventory UI is currently open.
 * @param itemAmount    paired with {@code itemId}: quantity for inventory
 *                      slots, animation_id for model viewers. {@code -1}
 *                      for non-ModelComponents.
 * @param triggers      CS2 event triggers bound to this component — the
 *                      discovery side of trigger firing. Each {@link ComponentTrigger}
 *                      is a (type, scriptId) the engine would run on that input
 *                      event; pass its {@code type} to
 *                      {@link com.botwithus.bot.api.GameAPI#fireComponentTrigger}.
 *                      Empty for the vast majority of components, which carry no
 *                      trigger array. Never null.
 * @param parentGlobal  the parent the client walks next when it decides visibility, packed
 *                      {@code (iface << 16) | comp}: the live parent, else the static parent,
 *                      else the host component an open sub-interface is mounted on.
 *                      {@link #NO_PARENT} at a root. See {@link #parentGlobalRef()}
 * @param visibility    the client's own visibility answer (v24); see {@link ComponentVisibility}
 * @param scroll        layer scroll state (v24); {@link ComponentScroll#NONE} on non-layers
 * @see com.botwithus.bot.api.GameAPI#getComponent
 */
public record Component(
        int ifaceId,
        int compId,
        int subId,
        int type,
        int category,
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
        int spriteId,
        int itemId,
        int itemAmount,
        List<ComponentTrigger> triggers,
        int parentGlobal,
        ComponentVisibility visibility,
        ComponentScroll scroll) {

    /** {@link #parentGlobal()} when the component has no parent to walk to. */
    public static final int NO_PARENT = -1;

    /** Bit position of the interface id in a packed {@link #parentGlobal()}. */
    private static final int PACKED_IFACE_SHIFT = 16;

    /** Mask of the component id in a packed {@link #parentGlobal()}. */
    private static final int PACKED_COMP_MASK = 0xFFFF;

    public Component {
        if (visibility == null) {
            visibility = ComponentVisibility.fromLegacyHidden(hidden);
        }
        if (scroll == null) {
            scroll = ComponentScroll.NONE;
        }
    }

    /**
     * Builds a record without the v24 keys; kept so pre-v24 callers compile unchanged. Visibility
     * is derived from {@code hidden}, there is no parent and no scroll.
     */
    public Component(int ifaceId, int compId, int subId, int type, int category, int x, int y,
                     int width, int height, int rawX, int rawY, int rawW, int rawH, int xPosMode,
                     int yPosMode, int xSizeMode, int ySizeMode, int absScreenPos, String text,
                     int hidden, int spriteId, int itemId, int itemAmount,
                     List<ComponentTrigger> triggers) {
        this(ifaceId, compId, subId, type, category, x, y, width, height, rawX, rawY, rawW, rawH,
                xPosMode, yPosMode, xSizeMode, ySizeMode, absScreenPos, text, hidden, spriteId,
                itemId, itemAmount, triggers, NO_PARENT,
                ComponentVisibility.fromLegacyHidden(hidden), ComponentScroll.NONE);
    }

    /**
     * True iff the component is hidden by its <b>own</b> flag ({@code hidden == 1}). A
     * component can read false here and still be off screen under a hidden parent; ask
     * {@link #isVisibleGlobal()} for that.
     */
    public boolean isHidden() { return hidden == 1; }

    /** The component's own visibility flag (v24 {@code visible}). */
    public boolean isVisible() { return visibility.isVisible(); }

    /** True when the component is on screen: visible at every level up to a root (v24 {@code visible_global}). */
    public boolean isVisibleGlobal() { return visibility.isVisibleGlobal(); }

    /** {@link #parentGlobal()} unpacked, or {@code null} when there is none. */
    public ComponentRef parentGlobalRef() {
        if (parentGlobal == NO_PARENT) {
            return null;
        }
        return new ComponentRef(parentGlobal >>> PACKED_IFACE_SHIFT, parentGlobal & PACKED_COMP_MASK);
    }
}
