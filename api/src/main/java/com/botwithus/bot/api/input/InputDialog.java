package com.botwithus.bot.api.input;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.gameval.GamevalRef;
import com.botwithus.bot.api.gameval.GamevalType;
import com.botwithus.bot.api.util.Interfaces;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The game's input dialog ({@code MESLAYER}, interface 1469): the prompt behind
 * withdraw-X, deposit-X, add-friend and the like. Obtain via
 * {@code api.inputDialog()}.
 *
 * <p>Typing goes through the key (type-10) trigger on the dialog's input field,
 * component 4. It does not go through the root, component 0, which carries no
 * triggers. That trigger exists only while the dialog is open, so a stroke
 * fired at a closed dialog is dropped rather than failing.</p>
 *
 * <pre>{@code
 * if (api.bank().startWithdrawX(itemId) && wait.until(api.inputDialog()::isOpen)) {
 *     api.inputDialog().enterAmount(28);        // types "28", then Enter
 * }
 * }</pre>
 *
 * <h2>Failure</h2>
 * Four kinds of failure, each reported its own way. In the first three nothing
 * is sent:
 * <ul>
 *   <li><b>Wrong state</b> returns {@code false}. The dialog is closed; it is
 *       open in a mode the call does not type into; or it already holds text that
 *       the new input would push past the mode's limits (the dialog appends).
 *       This is a race a script can lose and retry.</li>
 *   <li><b>Input the dialog would refuse</b> throws
 *       {@link IllegalArgumentException}: too long, a character the mode does not
 *       accept, a misplaced suffix, or an amount above {@link #MAX_AMOUNT_VALUE}.
 *       Sending it would type a truncated or different value, so it is refused
 *       here rather than left to the game.</li>
 *   <li><b>A failed read</b> of the dialog's mode or text propagates the
 *       transport's exception. It is never taken as "closed". One limit: the
 *       agent answers a varc read that found nothing with {@code -1}, the same
 *       value the mode holds before any dialog has opened. A read that timed out
 *       inside the agent therefore reads as {@link InputMode#CLOSED}, which sends
 *       nothing.</li>
 *   <li><b>A partial batch</b> throws {@link IllegalStateException}. The agent's
 *       action queue took only a prefix of the strokes, so some of them
 *       <em>were</em> sent, and the dialog may hold a partial value without its
 *       Enter.</li>
 * </ul>
 *
 * <h2>Timing</h2>
 * Each call is one action-queue round-trip, but the agent dispatches one stroke
 * per game tick: {@code enterAmount("250k")} lands five ticks later (four
 * characters and Enter). A {@code true} return means <em>queued</em>, not typed.
 */
public final class InputDialog {

    /** The input-dialog interface ({@code MESLAYER}). */
    public static final int INTERFACE_ID = 1469;
    /** The dialog's input field, which carries the key trigger ({@code MESLAYER__MES_TEXT2}). */
    public static final int INPUT_COMPONENT = 4;
    /** Longest value amount mode accepts, suffix included. */
    public static final int MAX_AMOUNT_LENGTH = 10;
    /** Longest value name mode accepts. */
    public static final int MAX_NAME_LENGTH = 12;

    private static final GamevalRef INPUT_FIELD = new GamevalRef(GamevalType.COMPONENT,
            "MESLAYER__MES_TEXT2", Interfaces.componentHash(INTERFACE_ID, INPUT_COMPONENT));
    /** {@code MESLAYERMODE} client variable, read as an {@link InputMode}. */
    private static final GamevalRef VARC_MODE =
            new GamevalRef(GamevalType.VAR_CLIENT, "MESLAYERMODE", 5);
    /** {@code MESLAYERINPUT} client string: the typed text. It outlives the dialog. */
    private static final GamevalRef VARC_TEXT =
            new GamevalRef(GamevalType.VAR_CLIENT, "MESLAYERINPUT", 2506);

    /** Largest value amount mode takes, after suffix expansion. */
    public static final long MAX_AMOUNT_VALUE = Integer.MAX_VALUE;

    /**
     * Digits, then at most one {@code k}/{@code K} (thousand) or {@code m}/{@code M}
     * (million) suffix. Both cases of {@code m} were verified live to mean a million.
     */
    private static final Pattern AMOUNT = Pattern.compile("([0-9]+)([kKmM]?)");
    private static final long THOUSAND = 1_000L;
    private static final long MILLION = 1_000_000L;
    /** Letters, digits, space and {@code _-.!}; case is kept. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9 _.!-]+");

    private final GameAPI api;

    public InputDialog(GameAPI api) {
        this.api = api;
    }

    // ---------------------------------------------------------------- State

    /** What the dialog is asking for; {@link InputMode#CLOSED} when it is not open. One RPC. */
    public InputMode mode() {
        return InputMode.fromVarc(api.getVarcInt(VARC_MODE.resolve(api.gamevals())));
    }

    /** True while an input dialog of any mode is open. One RPC. */
    public boolean isOpen() {
        return mode() != InputMode.CLOSED;
    }

    /**
     * The text typed so far, or empty when the dialog is closed. The variable
     * behind it keeps its last value after the dialog closes, so it is only read
     * while a dialog is open. Two RPCs.
     */
    public Optional<String> text() {
        if (!isOpen()) {
            return Optional.empty();
        }
        String text = api.getVarcString(VARC_TEXT.resolve(api.gamevals()));
        return Optional.of(text == null ? "" : text);
    }

    // ---------------------------------------------------------------- Typing

    /**
     * Type {@code amount} and submit it. Needs the dialog open in
     * {@link InputMode#AMOUNT}.
     *
     * @return {@code false}, sending nothing, when the dialog is not in amount mode,
     *         or it already holds text that {@code amount} would push past the
     *         limits
     * @throws IllegalArgumentException when {@code amount} is negative or above
     *                                  {@link #MAX_AMOUNT_VALUE}
     */
    public boolean enterAmount(long amount) {
        if (amount < 0 || amount > MAX_AMOUNT_VALUE) {
            throw new IllegalArgumentException("amount must be 0.." + MAX_AMOUNT_VALUE + ": " + amount);
        }
        return enterAmount(Long.toString(amount));
    }

    /**
     * Type {@code amount} and submit it. Accepts digits with an optional suffix
     * after at least one digit: {@code k}/{@code K} for thousands or
     * {@code m}/{@code M} for millions, such as {@code "250"}, {@code "10k"} or
     * {@code "2M"}. The limits are {@link #MAX_AMOUNT_LENGTH} characters in all
     * and {@link #MAX_AMOUNT_VALUE} once the suffix is expanded. Needs the dialog
     * open in {@link InputMode#AMOUNT}. After a submit, the dialog's text
     * variable holds the expanded digits ({@code "1M"} reads back as
     * {@code "1000000"}).
     *
     * <p>The dialog appends to whatever it already holds, so the check also
     * applies to the existing {@link #text()} followed by {@code amount}.
     * {@link #clear()} first to replace it.</p>
     *
     * @return {@code false}, sending nothing, when the dialog is not in amount mode,
     *         or it already holds text that {@code amount} would push past the
     *         limits
     * @throws IllegalArgumentException when {@code amount} on its own is not a
     *                                  value amount mode accepts ({@code "b"},
     *                                  {@code "1.5k"}, {@code "k5"}, {@code "5kk"},
     *                                  {@code "M5"}, {@code "2148m"}, blank, too long)
     */
    public boolean enterAmount(String amount) {
        requireAccepted(amount, InputDialog::isAcceptedAmount, "amount");
        return typeAndSubmit(InputMode.AMOUNT, amount, InputDialog::isAcceptedAmount);
    }

    /**
     * Type {@code text} and submit it. Accepts letters, digits, space and
     * {@code _-.!}, case kept, up to {@link #MAX_NAME_LENGTH} characters. Needs the
     * dialog open in {@link InputMode#NAME}. Other text modes have no verified
     * limits, so they are refused rather than guessed at; drive those with
     * {@link GameAPI#fireKeys} directly.
     *
     * <p>As with {@link #enterAmount(String)}, the check also applies to the
     * existing {@link #text()} followed by {@code text}.</p>
     *
     * @return {@code false}, sending nothing, when the dialog is not in name mode,
     *         or it already holds text that {@code text} would push past the limit
     * @throws IllegalArgumentException when {@code text} is blank, too long, or
     *                                  holds a character name mode does not accept
     */
    public boolean enterText(String text) {
        requireAccepted(text, InputDialog::isAcceptedName, "text");
        return typeAndSubmit(InputMode.NAME, text, InputDialog::isAcceptedName);
    }

    /** Press Enter. {@code false}, sending nothing, when no dialog is open. */
    public boolean submit() {
        return pressWhenOpen(List.of(KeyStroke.ENTER));
    }

    /** Press Escape, closing the dialog. {@code false}, sending nothing, when none is open. */
    public boolean cancel() {
        return pressWhenOpen(List.of(KeyStroke.ESCAPE));
    }

    /**
     * Delete everything typed so far: one Backspace per character of
     * {@link #text()}, so it takes that many ticks. {@code false}, sending nothing,
     * when no dialog is open; {@code true} with nothing sent when it is already
     * empty.
     */
    public boolean clear() {
        Optional<String> text = text();
        if (text.isEmpty()) {
            return false;
        }
        int length = text.get().length();
        if (length > 0) {
            fire(Collections.nCopies(length, KeyStroke.BACKSPACE));
        }
        return true;
    }

    // ---------------------------------------------------------------- Helpers

    private static void requireAccepted(String value, Predicate<String> accepted, String what) {
        if (value == null || !accepted.test(value)) {
            throw new IllegalArgumentException(what + " not accepted by the input dialog: " + value);
        }
    }

    private static boolean isAcceptedAmount(String value) {
        if (value.length() > MAX_AMOUNT_LENGTH) {
            return false;
        }
        Matcher parts = AMOUNT.matcher(value);
        return parts.matches() && expandedAmount(parts.group(1), parts.group(2)) <= MAX_AMOUNT_VALUE;
    }

    /** At most ten digits times a million, well inside a {@code long}. */
    private static long expandedAmount(String digits, String suffix) {
        long base = Long.parseLong(digits);
        return switch (suffix) {
            case "k", "K" -> base * THOUSAND;
            case "m", "M" -> base * MILLION;
            default -> base;
        };
    }

    private static boolean isAcceptedName(String value) {
        return value.length() <= MAX_NAME_LENGTH && NAME.matcher(value).matches();
    }

    /**
     * Every character of {@code value} then Enter, in one batch, when the dialog is
     * in {@code mode} and would still hold accepted text afterwards: the existing
     * text followed by {@code value}.
     */
    private boolean typeAndSubmit(InputMode mode, String value, Predicate<String> accepted) {
        if (mode() != mode) {
            return false;
        }
        String existing = api.getVarcString(VARC_TEXT.resolve(api.gamevals()));
        if (existing != null && !existing.isEmpty() && !accepted.test(existing + value)) {
            return false;
        }
        List<KeyStroke> keys = new ArrayList<>(value.length() + 1);
        for (int i = 0; i < value.length(); i++) {
            keys.add(KeyStroke.character(value.charAt(i)));
        }
        keys.add(KeyStroke.ENTER);
        fire(keys);
        return true;
    }

    private boolean pressWhenOpen(List<KeyStroke> keys) {
        if (!isOpen()) {
            return false;
        }
        fire(keys);
        return true;
    }

    /**
     * Queue {@code keys} as one batch.
     *
     * @throws IllegalStateException when the agent's action queue takes only part of
     *                               the batch. The part it took is a prefix and will
     *                               still be typed, perhaps without its Enter, so the
     *                               dialog may be left holding a partial value:
     *                               {@link #cancel()} or {@link #clear()} it.
     */
    private void fire(List<KeyStroke> keys) {
        int field = INPUT_FIELD.resolve(api.gamevals());
        int queued = api.fireKeys(Interfaces.interfaceIdFromHash(field),
                Interfaces.componentIdFromHash(field), keys);
        if (queued < keys.size()) {
            throw new IllegalStateException("the action queue took " + queued + " of " + keys.size()
                    + " key strokes; the dialog may now hold a partial value");
        }
    }
}
