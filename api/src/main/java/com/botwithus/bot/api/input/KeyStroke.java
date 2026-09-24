package com.botwithus.bot.api.input;

/**
 * One key event delivered to a component's key (type-10) CS2 trigger — the
 * argument the trigger's script reads as its key code and key character.
 *
 * <p>The engine delivers a real keystroke as two events: a key-down carrying the
 * code with no character, then a character event carrying the character with no
 * code. Input fields read the two halves differently, and a stroke that carries
 * the wrong half does nothing:</p>
 * <ul>
 *   <li>a printable character is taken from the <b>character</b> half, so type
 *       it as {@link #character(char)} ({@code (-1, c)}); a key-down alone
 *       appends nothing;</li>
 *   <li>a control key is taken from the <b>code</b> half, so send
 *       {@link #ENTER}, {@link #BACKSPACE} or {@link #ESCAPE} ({@code (code, 0)});
 *       a character 13 alone does not submit.</li>
 * </ul>
 *
 * @param keyCode Jagex key code, {@code -32768..32767}, or {@link #NO_KEY_CODE}
 * @param keyChar CP1252 character, {@code 0..65535}, or {@link #NO_KEY_CHAR}
 */
public record KeyStroke(int keyCode, int keyChar) {

    /** {@link #keyCode()} of a stroke that carries only a character. */
    public static final int NO_KEY_CODE = -1;
    /** {@link #keyChar()} of a stroke that carries only a key code. */
    public static final int NO_KEY_CHAR = 0;

    /** Jagex key code for Enter. */
    public static final int CODE_ENTER = 84;
    /** Jagex key code for Backspace. */
    public static final int CODE_BACKSPACE = 85;
    /** Jagex key code for Escape. */
    public static final int CODE_ESCAPE = 13;

    /** Submits an input field. */
    public static final KeyStroke ENTER = new KeyStroke(CODE_ENTER, NO_KEY_CHAR);
    /** Deletes the character before the caret. */
    public static final KeyStroke BACKSPACE = new KeyStroke(CODE_BACKSPACE, NO_KEY_CHAR);
    /** Cancels an input dialog. */
    public static final KeyStroke ESCAPE = new KeyStroke(CODE_ESCAPE, NO_KEY_CHAR);

    /** Lowest printable ASCII character (space). */
    private static final char FIRST_PRINTABLE = ' ';
    /** Highest printable ASCII character (tilde). */
    private static final char LAST_PRINTABLE = '~';
    /** Bits the character half occupies in {@link #packed()}. */
    private static final int CHAR_BITS = 16;
    /** Mask for the character half of {@link #packed()}. */
    private static final int CHAR_MASK = 0xFFFF;

    public KeyStroke {
        if (keyCode < Short.MIN_VALUE || keyCode > Short.MAX_VALUE) {
            throw new IllegalArgumentException("keyCode out of int16 range: " + keyCode);
        }
        if (keyChar < 0 || keyChar > CHAR_MASK) {
            throw new IllegalArgumentException("keyChar out of uint16 range: " + keyChar);
        }
    }

    /**
     * A character event typing {@code c}: {@code (-1, c)}. Printable ASCII only,
     * because that is the range where a Java {@code char} and the CP1252 byte the
     * game reads are the same number; build the record directly for anything
     * else.
     *
     * @throws IllegalArgumentException when {@code c} is not printable ASCII
     */
    public static KeyStroke character(char c) {
        if (c < FIRST_PRINTABLE || c > LAST_PRINTABLE) {
            throw new IllegalArgumentException(
                    String.format("not printable ASCII: U+%04X", (int) c));
        }
        return new KeyStroke(NO_KEY_CODE, c);
    }

    /**
     * The trigger argument this stroke travels as:
     * {@code (keyCode << 16) | keyChar}, the code sign-carried in the high half.
     */
    public int packed() {
        return (keyCode << CHAR_BITS) | (keyChar & CHAR_MASK);
    }
}
