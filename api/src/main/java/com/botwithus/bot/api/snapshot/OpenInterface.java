package com.botwithus.bot.api.snapshot;

/**
 * One open sub-interface as the snapshot publishes it (wire v23): its id, how the client
 * opened it, and whether a client script rather than the server did the opening.
 *
 * <p>{@link #type()} is the client's own open type, not a decoded category, so a value this
 * class has no name for is still carried faithfully. The ones that have been observed:</p>
 * <ul>
 *   <li>{@link #TYPE_MODAL} ({@code 0}): the bank, dialogue and choice boxes. A modal closes
 *       when the player moves, and it does not block queued actions.</li>
 *   <li>{@link #TYPE_OVERLAY} ({@code 1}): HUD panels and the XP counter.</li>
 *   <li>{@link #TYPE_CLIENT_CHILD} ({@code 3}): a child opened by a client script, which
 *       closes along with its parent. Seen with {@link #clientOpened()} set.</li>
 *   <li>{@link #TYPE_UNKNOWN} ({@code 7}): the client's type was above 6 and the producer
 *       clamped it.</li>
 * </ul>
 *
 * <p>This is a plain value copied out of the snapshot, so unlike the snapshot itself it is
 * safe to keep past the tick.</p>
 *
 * @param id           the interface id, as {@link GameSnapshot#isInterfaceOpen(int)} takes it
 * @param type         the open type, {@code 0..7}
 * @param clientOpened whether a client script, not a server packet, opened it
 */
public record OpenInterface(int id, int type, boolean clientOpened) {

    /** Bits 0-2 of a wire flags byte: the open type. Mirrors {@code kOpenIfaceTypeMask}. */
    public static final int TYPE_MASK = 0x07;

    /** Bit 3 of a wire flags byte. Mirrors {@code kOpenIfaceFlagClientOpened}. */
    public static final int FLAG_CLIENT_OPENED = 0x08;

    /** Closes when the player moves; the bank and dialogues. */
    public static final int TYPE_MODAL = 0;

    /** HUD panels and other persistent overlays. */
    public static final int TYPE_OVERLAY = 1;

    /** A child opened by a client script, closed along with its parent. */
    public static final int TYPE_CLIENT_CHILD = 3;

    /** The client's type was out of the publishable range. Mirrors {@code kOpenIfaceTypeUnknown}. */
    public static final int TYPE_UNKNOWN = 0x07;

    public OpenInterface {
        if (type < 0 || type > TYPE_MASK) {
            throw new IllegalArgumentException("type must be 0.." + TYPE_MASK + ", got " + type);
        }
    }

    /**
     * Decodes one wire flags byte for interface {@code id}. Reserved bits 4-7 are ignored, so
     * a producer that starts using them cannot change what {@link #type()} or
     * {@link #clientOpened()} report.
     *
     * @param id    the interface id from the same index of the id array
     * @param flags the unsigned flags byte, {@code 0..255}
     */
    public static OpenInterface fromWire(int id, int flags) {
        return new OpenInterface(id, flags & TYPE_MASK, (flags & FLAG_CLIENT_OPENED) != 0);
    }

    /** Whether this is a modal: it closes when the player moves. */
    public boolean isModal() {
        return type == TYPE_MODAL;
    }

    /** Whether this is a persistent overlay such as a HUD panel. */
    public boolean isOverlay() {
        return type == TYPE_OVERLAY;
    }

    /** Whether the producer could not publish the client's type. */
    public boolean isTypeUnknown() {
        return type == TYPE_UNKNOWN;
    }
}
