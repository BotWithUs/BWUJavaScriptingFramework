package com.botwithus.bot.api.event;

/**
 * Fired when a key is pressed while the overlay has keyboard focus.
 *
 * @param key       the virtual key code (VK_*)
 * @param alt       whether ALT was held
 * @param ctrl      whether CTRL was held
 * @param shift     whether SHIFT was held
 * @param timestamp event creation time in milliseconds since epoch
 */
public record KeyInputEvent(int key, boolean alt, boolean ctrl, boolean shift, long timestamp)
        implements GameEvent {

    public KeyInputEvent(int key, boolean alt, boolean ctrl, boolean shift) {
        this(key, alt, ctrl, shift, System.currentTimeMillis());
    }
}
