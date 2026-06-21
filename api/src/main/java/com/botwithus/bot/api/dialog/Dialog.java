package com.botwithus.bot.api.dialog;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.component.ComponentNode;
import com.botwithus.bot.api.component.ComponentTree;
import com.botwithus.bot.api.component.ComponentType;
import com.botwithus.bot.api.constants.InterfaceIds;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.util.Interfaces;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reads RS3 conversation dialogs: selects options on the multi-choice dialog
 * (interface {@code DIALOG_OPTIONS}, 1188 — "select an option") and advances a
 * text-only NPC-chat page (interface {@code DIALOG_NPC}, 1184).
 *
 * <h2>The text&rarr;component correlation</h2>
 * The dialog is laid out as a fixed set of option <em>rows</em> ({@code LAYER}
 * components) hanging directly off the options container (component
 * {@link #OPTIONS_CONTAINER}). The visible text is <em>not</em> on the row: each
 * row's subtree carries two {@code TEXT} leaves — the option <b>label</b>
 * ("What's wrong?") and a <b>number cell</b> ("1."). The number cell yields the
 * 1-based option index directly, so the row&rarr;index mapping is read from the
 * game rather than assumed.
 *
 * <p>Crucially, selection targets the <b>row</b> (component 8/13/18/23/28…) via
 * the {@code DIALOGUE} action (type 30, the engine's resume / pausebutton path)
 * — not the text leaf that carries the label, and not a {@code COMPONENT} click
 * (which dispatches but the server ignores, leaving the dialogue stuck). This
 * class walks label&rarr;row so callers select by text or index and the right
 * component is driven the right way. Verified live against interface 1188: label
 * "What's wrong?" lives on component 6, three levels under row component 8, and
 * a {@code DIALOGUE} action on row 8 advances the dialogue.</p>
 *
 * <pre>{@code
 * if (Dialog.isOpen(api)) {
 *     Dialog.select(api, "What's wrong?");   // clicks row component 8
 * }
 * }</pre>
 */
public final class Dialog {

    /** The multi-choice dialog interface ("select an option"). */
    public static final int MULTI_CHOICE_INTERFACE = InterfaceIds.DIALOG_OPTIONS;

    /** Component id of the container the option rows hang off. */
    private static final int OPTIONS_CONTAINER = 0;

    /** The text-only NPC-chat dialog interface (no options, just a continue button). */
    public static final int NPC_CHAT_INTERFACE = InterfaceIds.DIALOG_NPC;

    /** The continue / next button on the NPC-chat dialog (advances to the next page). */
    private static final int NPC_CHAT_CONTINUE_COMPONENT = 15;

    /** param1 a dialogue selection carries (the engine ignores a menu index here). */
    private static final int DIALOGUE_PARAM1 = 0;

    /** param2 a dialogue selection carries (no inventory sub-slot). */
    private static final int NO_SUB_SLOT = -1;

    /** A number cell: the whole text is digits followed by a single dot ("1."). */
    private static final Pattern NUMBER_CELL = Pattern.compile("\\d+\\.");

    /** Color / formatting markup the game embeds in label text ({@code <col=...>}). */
    private static final Pattern MARKUP = Pattern.compile("<[^>]+>");

    /** Collapses any run of whitespace to a single space. */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private Dialog() {}

    /** True when the multi-choice dialog is open with at least one selectable option. */
    public static boolean isOpen(GameAPI api) {
        return !options(api).isEmpty();
    }

    /**
     * The selectable options currently shown, in display order. Empty when the
     * multi-choice dialog isn't open. One {@code get_interface_tree} round-trip;
     * the row/label/number walk is then in-memory.
     */
    public static List<DialogOption> options(GameAPI api) {
        ComponentTree tree = api.components().in(MULTI_CHOICE_INTERFACE).tree();
        ComponentNode container = tree.node(MULTI_CHOICE_INTERFACE, OPTIONS_CONTAINER);
        if (container == null) {
            return List.of();
        }
        List<DialogOption> out = new ArrayList<>();
        for (ComponentNode row : container.children()) {
            if (row.type() != ComponentType.LAYER) {
                continue;
            }
            DialogOption option = readOption(row, out.size() + 1);
            if (option != null) {
                out.add(option);
            }
        }
        return out;
    }

    /**
     * Selects the first option whose label contains {@code text} (case-insensitive,
     * markup-stripped, whitespace-normalized). Clicks the option row.
     *
     * @return {@code true} when a matching option was found and clicked
     */
    public static boolean select(GameAPI api, String text) {
        String needle = normalize(text);
        if (needle.isEmpty()) {
            return false;
        }
        for (DialogOption option : options(api)) {
            if (normalize(option.text()).contains(needle)) {
                option.select();
                return true;
            }
        }
        return false;
    }

    /**
     * Selects the option with the given 1-based index (as shown by its "N." cell).
     * Clicks the option row.
     *
     * @return {@code true} when an option with that index was found and clicked
     */
    public static boolean select(GameAPI api, int index) {
        for (DialogOption option : options(api)) {
            if (option.index() == index) {
                option.select();
                return true;
            }
        }
        return false;
    }

    /**
     * True when the text-only NPC-chat dialog (1184) is open. 1184 fully unloads
     * when the page closes, so its root component resolving is an exact gate.
     * (Note: walking its tree from the root misses the content — the NPC line is
     * a flat sibling under a different parent, not a descendant of component 0.)
     */
    public static boolean isChatOpen(GameAPI api) {
        return api.components().isOpen(NPC_CHAT_INTERFACE);
    }

    /**
     * Advances a text-only NPC-chat page (interface 1184) by driving its
     * continue / next button (component 15) with the {@code DIALOGUE} action.
     *
     * <p>A manual click on that button emits exactly this action — verified via
     * the agent's DoAction hook: {@code action=30 p1=0 p2=-1 p3=(1184<<16)|15}.
     * Spacebar is the keyboard equivalent, routed through the button's type-10
     * key trigger. A {@code COMPONENT} click would dispatch but not advance.</p>
     *
     * @return {@code true} when the NPC-chat dialog was open and a continue was
     *         queued; {@code false} when it isn't open
     */
    public static boolean continueChat(GameAPI api) {
        if (!isChatOpen(api)) {
            return false;
        }
        api.queueAction(new GameAction(
                ActionTypes.DIALOGUE,
                DIALOGUE_PARAM1,
                NO_SUB_SLOT,
                Interfaces.componentHash(NPC_CHAT_INTERFACE, NPC_CHAT_CONTINUE_COMPONENT)));
        return true;
    }

    /**
     * Builds an option from a candidate row, or {@code null} when the row carries
     * no label (an empty/unused slot). The row's {@code TEXT} descendants are the
     * number cell ("N.") and the label; the number cell sets the index, falling
     * back to {@code positionalIndex} when absent.
     */
    private static DialogOption readOption(ComponentNode row, int positionalIndex) {
        String label = null;
        int parsedIndex = -1;
        for (ComponentNode leaf : row.descendants()) {
            if (leaf.type() != ComponentType.TEXT) {
                continue;
            }
            String t = leaf.text();
            if (t == null || t.isBlank()) {
                continue;
            }
            int n = numberCell(t);
            if (n > 0) {
                parsedIndex = n;
            } else {
                label = t;
            }
        }
        if (label == null || label.isBlank()) {
            return null;
        }
        return new DialogOption(parsedIndex > 0 ? parsedIndex : positionalIndex, label, row);
    }

    /** Parses an "N." number cell to its integer, or -1 when the text isn't one. */
    private static int numberCell(String text) {
        String s = text.strip();
        if (!NUMBER_CELL.matcher(s).matches()) {
            return -1;
        }
        return Integer.parseInt(s.substring(0, s.length() - 1));
    }

    /** Lowercase + strip markup tags + collapse whitespace, for tolerant matching. */
    static String normalize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String stripped = MARKUP.matcher(raw).replaceAll(" ");
        String collapsed = WHITESPACE.matcher(stripped).replaceAll(" ");
        return collapsed.toLowerCase(Locale.ROOT).strip();
    }
}
