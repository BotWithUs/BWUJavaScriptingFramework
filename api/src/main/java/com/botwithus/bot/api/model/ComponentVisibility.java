package com.botwithus.bot.api.model;

/**
 * A component's visibility as the client decides it (component map keys {@code visible},
 * {@code visible_global} and {@code slot_flags}).
 *
 * @param isVisible       the component's <b>own</b> flag: its slot flag byte is {@code 0}. A
 *                        component can be visible by its own flag and still be off screen,
 *                        because a parent is hidden
 * @param isVisibleGlobal {@code isVisible} at every level up to a root, crossing into the host
 *                        component of a mounted sub-interface, with every interface on the way
 *                        loaded. This is what "on screen" means
 * @param slotFlags       the raw slot flag byte behind {@code isVisible}; {@link #NO_SLOT} when
 *                        no slot was found (the client treats that as not visible) or the client
 *                        channel cannot read it
 */
public record ComponentVisibility(boolean isVisible, boolean isVisibleGlobal, int slotFlags) {

    /** {@link #slotFlags()} when there is no slot byte to report. */
    public static final int NO_SLOT = -1;

    /**
     * What a producer that predates the v24 visibility keys implies: the only signal it sent was
     * {@code hidden}, so own and global visibility are both {@code hidden != 1}.
     *
     * @param hidden the component map's {@code hidden} value
     */
    public static ComponentVisibility fromLegacyHidden(int hidden) {
        boolean isShown = hidden != 1;
        return new ComponentVisibility(isShown, isShown, NO_SLOT);
    }
}
