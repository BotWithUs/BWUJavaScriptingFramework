package com.botwithus.bot.quest;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.component.ComponentNode;
import com.botwithus.bot.api.component.Components;
import com.botwithus.bot.api.dialog.Dialog;

import java.util.Locale;
import java.util.Optional;

/**
 * Discovery-driven dialog handler. Probes a short list of "hint" interface
 * ids — the chatbox / NPC-chat / player-chat / book/scroll readers the game
 * routinely opens for quest dialog — picks the first one that's open, walks
 * its component tree via the {@link Components} facade, and matches options
 * by label text.
 *
 * <p>The hint ids are a cheap probe set, not the ground truth. Once a probe
 * resolves we use the tree fetch (one round-trip) to find an option whose
 * label contains the requested text, and {@link ComponentNode#interact(int)}
 * to click it. The producer doesn't currently expose synthesized key input
 * so the "press space to continue" fallback in
 * {@link #tryContinue(GameAPI)} clicks a continue/please-wait label instead.</p>
 */
public final class QuestDialog {

    /**
     * Interface ids commonly opened by quest dialog — chatbox /
     * NPC-chat / player-chat variants, plus book/scroll readers. Probed in
     * order; the first that resolves to a loaded interface wins.
     */
    public static final int[] HINT_INTERFACES = {
            1184, 1188, 1189, 1191, 1186, 1183, 1218, 162, 1450
    };

    private QuestDialog() {}

    /** Returns the first hint interface that's currently loaded, or empty. */
    public static Optional<Integer> findOpenInterface(GameAPI api) {
        Components ui = api.components();
        for (int id : HINT_INTERFACES) {
            if (ui.isOpen(id)) {
                return Optional.of(id);
            }
        }
        return Optional.empty();
    }

    /** True iff any of {@link #HINT_INTERFACES} is open. */
    public static boolean isOpen(GameAPI api) {
        return findOpenInterface(api).isPresent();
    }

    /**
     * Looks for an option label containing {@code optionText} (case-insensitive,
     * whitespace-normalized, color tags stripped) and clicks it.
     *
     * <p>On the multi-choice dialog the label text and the clickable component
     * are different components: the label is a {@code TEXT} leaf, but the server
     * keys the click on the option <em>row</em> above it. So that interface is
     * routed exclusively through {@link Dialog}, which walks label&rarr;row and
     * clicks the row. Other hint dialogs that present a directly-clickable label
     * keep the legacy match-and-click path.</p>
     *
     * @return {@code true} when a matching option was clicked
     */
    public static boolean clickOption(GameAPI api, String optionText) {
        Components ui = api.components();
        // Multi-choice options: never click the text leaf — only the row the
        // server expects. Dialog owns that label->row correlation.
        if (ui.isOpen(Dialog.MULTI_CHOICE_INTERFACE)) {
            return Dialog.select(api, optionText);
        }
        Optional<Integer> open = findOpenInterface(api);
        if (open.isEmpty()) {
            return false;
        }
        String needle = normalize(optionText);
        if (needle.isEmpty()) {
            return false;
        }
        ComponentNode match = ui.in(open.get())
                .filter(n -> normalize(n.text()).contains(needle))
                .first();
        if (match == null) {
            return false;
        }
        match.interact(1);
        return true;
    }

    /**
     * Best-effort continue-prompt handler. Advances a text-only NPC-chat page
     * (1184) via {@link Dialog#continueChat}, which drives the continue button
     * with the {@code DIALOGUE} action — a plain component click on the continue
     * label dispatches but doesn't advance. Falls back to a label-match-and-click
     * on other hint dialogs we don't model.
     *
     * @return {@code true} when a continue prompt was found and actioned
     */
    public static boolean tryContinue(GameAPI api) {
        if (Dialog.continueChat(api)) {
            return true;
        }
        Optional<Integer> open = findOpenInterface(api);
        if (open.isEmpty()) {
            return false;
        }
        String[] phrases = { "click here to continue", "please wait", "continue" };
        Components ui = api.components();
        for (String phrase : phrases) {
            ComponentNode match = ui.in(open.get())
                    .filter(n -> normalize(n.text()).contains(phrase))
                    .first();
            if (match != null) {
                match.interact(1);
                return true;
            }
        }
        return false;
    }

    /** Lowercase + strip HTML-ish color tags + collapse whitespace. */
    static String normalize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String stripped = raw.replaceAll("<[^>]+>", " ");
        return stripped.toLowerCase(Locale.ROOT).strip().replaceAll("\\s+", " ");
    }
}
