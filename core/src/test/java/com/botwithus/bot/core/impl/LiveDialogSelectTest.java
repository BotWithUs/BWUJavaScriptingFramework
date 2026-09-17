package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.dialog.Dialog;
import com.botwithus.bot.api.dialog.DialogOption;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.ActionEntry;
import com.botwithus.bot.api.util.Interfaces;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.RpcClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live END-TO-END test: query the multi-choice dialog by option text and click,
 * against a running, injected client. Stands up the real
 * {@code PipeClient → RpcClient → GameAPIImpl} stack a BotScript uses, resolves
 * the option whose label matches, fires {@link Dialog#select(GameAPI, String)},
 * and proves the dispatched action targeted the option <b>row</b> — the
 * component the server keys on — not the text-label leaf.
 *
 * <p><b>This test mutates the game.</b> It picks a dialog option, advancing the
 * conversation. It is therefore gated behind its own opt-in
 * ({@code -Dbotwithus.smoke.dialog=true}, set by the
 * {@code :core:liveDialogSelectTest} task) so the read-only smoke suite never
 * selects an option by accident.</p>
 *
 * <p>Requires the multi-choice dialog ({@code DIALOG_OPTIONS}, 1188) open with
 * options; the class self-skips when no pipe is visible or no dialog is open.
 * Which option is clicked comes from {@code -Dbotwithus.dialog.option="<text>"}
 * (case-insensitive substring of the label); unset, it selects the first listed
 * option by resolving that option's own label and querying by it.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "botwithus.smoke.dialog", matches = "true")
class LiveDialogSelectTest {

    private static final Logger log = LoggerFactory.getLogger(LiveDialogSelectTest.class);

    /** Property naming the option label to click; unset → first option. */
    private static final String OPTION_PROPERTY = "botwithus.dialog.option";

    /** param1 a dialogue-option selection carries (engine ignores a menu index here). */
    private static final int DIALOGUE_PARAM1 = 0;
    /** param2 a dialogue selection carries (no inventory sub-slot). */
    private static final int NO_SUB_SLOT = -1;

    /** Max action-history rows to scan (producer clamps at 128). */
    private static final int HISTORY_CAP = 128;
    /** How long to wait for the queued click to execute on a game tick. */
    private static final long EXEC_TIMEOUT_MS = 5_000;
    /** Poll interval while waiting for the executed action to surface. */
    private static final long EXEC_POLL_MS = 150;

    private RpcClient rpc;
    private GameAPI api;

    @BeforeAll
    void connect() {
        List<String> pipes = PipeClient.scanPipes(PipeClient.NAME_PREFIX);
        Assumptions.assumeFalse(pipes.isEmpty(),
                "no BotWithUs_<pid> pipe visible — inject the DLL into a running client");

        PipeClient pipe = new PipeClient(pipes.getFirst());
        rpc = new RpcClient(pipe);
        api = new GameAPIImpl(rpc);

        Assumptions.assumeTrue(Dialog.isOpen(api),
                "multi-choice dialog (1188) not open with options — open one and rerun");
        log.info("connected to {}", pipe.getPipePath());
    }

    @AfterAll
    void disconnect() {
        if (rpc != null) {
            rpc.close();
        }
    }

    @Test
    void selectsOptionByTextAndClicksTheRow() throws InterruptedException {
        List<DialogOption> options = Dialog.options(api);
        assertFalse(options.isEmpty(), "dialog must show at least one selectable option");
        for (DialogOption o : options) {
            log.info("option {} = '{}' -> row comp {}", o.index(), o.text(), o.componentId());
        }

        String target = System.getProperty(OPTION_PROPERTY, options.getFirst().text());

        // Independent query-by-text: resolve the option whose label matches, and
        // capture the ROW component it should click (not the text-label leaf).
        DialogOption expected = options.stream()
                .filter(o -> labelMatches(o.text(), target))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no option label matching '" + target + "' in " + summarize(options)));
        int expectedHash = Interfaces.componentHash(Dialog.MULTI_CHOICE_INTERFACE, expected.componentId());
        log.info("targeting '{}' (option {}) -> row comp {}, hash {}",
                expected.text(), expected.index(), expected.componentId(), expectedHash);

        List<String> beforeLabels = options.stream().map(DialogOption::text).toList();
        // Newest DIALOGUE action before the selection, so we only match the new one.
        List<ActionEntry> before = api.getActionHistory(HISTORY_CAP, ActionTypes.DIALOGUE);
        long sinceTimestamp = before.isEmpty() ? Long.MIN_VALUE : before.getLast().timestamp();

        // Select via the same text path a script uses.
        boolean clicked = Dialog.select(api, target);
        assertTrue(clicked, "Dialog.select must report a matching option clicked");

        // Prove the selection executed against the game carrying the ROW hash via
        // the DIALOGUE action (type 30) — a COMPONENT click on the row dispatches
        // but the server ignores it.
        ActionEntry dispatched = awaitDialogueAction(expectedHash, sinceTimestamp)
                .orElseThrow(() -> new AssertionError(
                        "no executed DIALOGUE action with row hash " + expectedHash
                                + " within " + EXEC_TIMEOUT_MS + "ms"));
        assertEquals(ActionTypes.DIALOGUE, dispatched.actionId(), "dispatched a DIALOGUE action (type 30)");
        assertEquals(expectedHash, dispatched.param3(), "selection targeted the option ROW, not the label leaf");
        assertEquals(DIALOGUE_PARAM1, dispatched.param1(), "dialogue param1");
        assertEquals(NO_SUB_SLOT, dispatched.param2(), "no inventory sub-slot on a dialogue selection");
        log.info("selected '{}' — executed action {} params=({},{},{})",
                expected.text(), dispatched.actionId(),
                dispatched.param1(), dispatched.param2(), dispatched.param3());

        // And prove it actually took effect: the option set must change (the
        // dialogue advanced, closed, or moved to a different prompt).
        assertTrue(awaitOptionsChanged(beforeLabels),
                "dialogue must advance after selection — option set should change from " + beforeLabels);
        log.info("dialogue advanced; options now {}",
                Dialog.options(api).stream().map(DialogOption::text).toList());
    }

    /**
     * Polls the executed-action history until a DIALOGUE action newer than
     * {@code sinceTimestamp} carries {@code expectedHash}, or the timeout lapses.
     * The queued selection runs on a game tick, so it surfaces here a few hundred
     * ms after {@link Dialog#select}.
     */
    private Optional<ActionEntry> awaitDialogueAction(int expectedHash, long sinceTimestamp)
            throws InterruptedException {
        long deadline = System.nanoTime() + EXEC_TIMEOUT_MS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            for (ActionEntry e : api.getActionHistory(HISTORY_CAP, ActionTypes.DIALOGUE)) {
                if (e.timestamp() > sinceTimestamp && e.param3() == expectedHash) {
                    return Optional.of(e);
                }
            }
            Thread.sleep(EXEC_POLL_MS);
        }
        return Optional.empty();
    }

    /** Polls until the live option labels differ from {@code before}, or timeout. */
    private boolean awaitOptionsChanged(List<String> before) throws InterruptedException {
        long deadline = System.nanoTime() + EXEC_TIMEOUT_MS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            List<String> now = Dialog.options(api).stream().map(DialogOption::text).toList();
            if (!now.equals(before)) {
                return true;
            }
            Thread.sleep(EXEC_POLL_MS);
        }
        return false;
    }

    /** Case-insensitive, markup-stripped substring match (mirrors Dialog's matching). */
    private static boolean labelMatches(String label, String target) {
        return strip(label).contains(strip(target));
    }

    private static String strip(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("<[^>]+>", " ").toLowerCase(Locale.ROOT).strip().replaceAll("\\s+", " ");
    }

    private static String summarize(List<DialogOption> options) {
        return options.stream().map(o -> o.index() + ":'" + o.text() + "'").toList().toString();
    }
}
