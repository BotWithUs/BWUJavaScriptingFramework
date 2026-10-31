package com.botwithus.bot.api.model;

import java.util.List;

/**
 * A loaded player's applied appearance, from {@link com.botwithus.bot.api.GameAPI#getPlayerAppearance(int)}.
 *
 * <p><b>{@link #slots()} are in the client's order, not wearpos.</b> A worn shortbow (wearpos 3)
 * sits at slot 15 on the current client. Find a worn item by item id, never by index. Items the
 * client does not render (ammunition, for one) are absent.</p>
 *
 * @param status         {@link AppearanceStatus#OK} when the rest is a real reading. On any other
 *                       status the ints are {@link #UNKNOWN} and the lists are empty
 * @param renderId       render (stance) id; equals the snapshot {@code stanceId} for that player
 * @param npcTransformId NPC the player is transformed into, or {@link #UNKNOWN} when not
 *                       transformed
 * @param gender         <b>inferred</b>, not verified: bit 0 of the appearance flags. Published
 *                       raw, so anything other than {@code 0} or {@code 1} means a misread
 * @param slots          kit/item slots in client order (see above)
 * @param colours        recolour indices, one per body-colour slot
 */
public record PlayerAppearance(
        AppearanceStatus status,
        int renderId,
        int npcTransformId,
        int gender,
        List<AppearanceSlot> slots,
        List<Integer> colours) {

    /** Int fields on a refused read, and {@link #npcTransformId()} when not transformed. */
    public static final int UNKNOWN = -1;

    public PlayerAppearance {
        slots = List.copyOf(slots);
        colours = List.copyOf(colours);
    }

    /** A refused read carrying only its reason. */
    public static PlayerAppearance refused(AppearanceStatus status) {
        return new PlayerAppearance(status, UNKNOWN, UNKNOWN, UNKNOWN, List.of(), List.of());
    }

    /** True when {@link #status()} is {@link AppearanceStatus#OK}. */
    public boolean isAvailable() {
        return status == AppearanceStatus.OK;
    }

    /** True when the player is transformed into an NPC. */
    public boolean isTransformed() {
        return npcTransformId != UNKNOWN;
    }

    /** True when any slot shows {@code itemId}. The way to ask "is this item worn", since slots are not wearpos. */
    public boolean isWearing(int itemId) {
        return slots.stream().anyMatch(slot -> slot.item() == itemId);
    }
}
