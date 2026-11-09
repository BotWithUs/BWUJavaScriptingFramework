package com.botwithus.bot.api.event;

/**
 * Fired for every key-down the game's input dispatcher received, whether a person pressed
 * it or the bot sent it with its own key input. Key-up is not reported.
 *
 * <p>{@code key} is the Windows virtual-key code as the game received it. Modifier keys
 * arrive as the generic codes ({@code VK_SHIFT} 16, {@code VK_CONTROL} 17,
 * {@code VK_MENU} 18), never as their left/right variants. The modifier flags report what
 * was held at the time, <em>including the pressed key itself</em>: pressing Shift reports
 * {@code key == 16} with {@code shift == true}.</p>
 *
 * <p>The three trailing flags were added after the event first shipped. An agent that
 * predates them reports all three as {@code false}, so {@code false} means "not marked",
 * not "known not to be".</p>
 *
 * @param key              the virtual-key code (VK_*) as the game received it
 * @param alt              whether ALT was held, including when {@code key} is ALT
 * @param ctrl             whether CTRL was held, including when {@code key} is CTRL
 * @param shift            whether SHIFT was held, including when {@code key} is SHIFT
 * @param synthetic        whether the key was sent by the bot itself rather than typed by a
 *                         person; a script reacting to human input should skip these
 * @param textInputFocused whether a text field had the keyboard when the key arrived, so a
 *                         script can ignore keys typed into chat. <strong>Coverage is
 *                         incomplete:</strong> today the agent sets this for native text
 *                         fields only, and it is <em>not</em> set while typing in chat. Until
 *                         the agent gains a chat source, {@code false} does not prove the key
 *                         was meant for the game
 * @param repeat           whether this is the keyboard's autorepeat of a key that was already
 *                         held, rather than a fresh press
 * @param timestamp        event creation time in milliseconds since epoch
 */
public record KeyInputEvent(int key, boolean alt, boolean ctrl, boolean shift,
                            boolean synthetic, boolean textInputFocused, boolean repeat,
                            long timestamp)
        implements GameEvent {

    /**
     * Creates an event with all three trailing flags {@code false}, stamped now.
     * Kept for callers written before the flags existed.
     */
    public KeyInputEvent(int key, boolean alt, boolean ctrl, boolean shift) {
        this(key, alt, ctrl, shift, System.currentTimeMillis());
    }

    /**
     * Creates an event with all three trailing flags {@code false}.
     * Kept for callers written before the flags existed.
     */
    public KeyInputEvent(int key, boolean alt, boolean ctrl, boolean shift, long timestamp) {
        this(key, alt, ctrl, shift, false, false, false, timestamp);
    }

    /** Creates an event with every field supplied, stamped now. */
    public KeyInputEvent(int key, boolean alt, boolean ctrl, boolean shift,
                         boolean synthetic, boolean textInputFocused, boolean repeat) {
        this(key, alt, ctrl, shift, synthetic, textInputFocused, repeat,
                System.currentTimeMillis());
    }
}
