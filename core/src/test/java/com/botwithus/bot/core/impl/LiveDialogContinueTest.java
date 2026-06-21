package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.component.ComponentNode;
import com.botwithus.bot.api.dialog.Dialog;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live END-TO-END test: advance a text-only NPC-chat page (interface 1184) and
 * prove it actually moved on. {@code Dialog.continueChat} drives the continue
 * button (component 15) with the {@code DIALOGUE} action — the exact action a
 * manual click on that button emits (verified via the agent's DoAction hook:
 * {@code action=30 p1=0 p2=-1 p3=(1184<<16)|15}).
 *
 * <p><b>This test mutates the game</b> — it advances the conversation. Gated
 * behind {@code -Dbotwithus.smoke.dialog=true} (the
 * {@code :core:liveDialogContinueTest} task). Requires a 1184 "click here to
 * continue" page open; self-skips otherwise. The advancement check (not just the
 * dispatched action) is load-bearing: a wrong action type executes and lands in
 * history yet leaves the dialogue stuck, so the action alone is not proof.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "botwithus.smoke.dialog", matches = "true")
class LiveDialogContinueTest {

    private static final Logger log = LoggerFactory.getLogger(LiveDialogContinueTest.class);

    /** Continue / next button on the NPC-chat dialog. */
    private static final int CONTINUE_COMPONENT = 15;
    /** The NPC spoken-line text component on 1184 (used to detect a page change). */
    private static final int NPC_LINE_COMPONENT = 10;

    private static final int DIALOGUE_PARAM1 = 0;
    private static final int NO_SUB_SLOT = -1;
    private static final int HISTORY_CAP = 128;
    private static final long EXEC_TIMEOUT_MS = 5_000;
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

        Assumptions.assumeTrue(Dialog.isChatOpen(api),
                "NPC-chat (1184) not open — talk to an NPC to a 'click here to continue' page and rerun");
        log.info("connected to {}", pipe.getPipePath());
    }

    @AfterAll
    void disconnect() {
        if (rpc != null) {
            rpc.close();
        }
    }

    @Test
    void continueAdvancesNpcChat() throws InterruptedException {
        String beforeLine = npcLine();
        log.info("NPC line before continue: '{}'", beforeLine);

        int expectedHash = Interfaces.componentHash(Dialog.NPC_CHAT_INTERFACE, CONTINUE_COMPONENT);
        List<ActionEntry> before = api.getActionHistory(HISTORY_CAP, ActionTypes.DIALOGUE);
        long sinceTimestamp = before.isEmpty() ? Long.MIN_VALUE : before.getLast().timestamp();

        boolean continued = Dialog.continueChat(api);
        assertTrue(continued, "continueChat must report the chat was open and a continue queued");

        // Same shape a manual continue-button click emits.
        ActionEntry dispatched = awaitDialogueAction(expectedHash, sinceTimestamp)
                .orElseThrow(() -> new AssertionError(
                        "no executed DIALOGUE action with continue hash " + expectedHash
                                + " within " + EXEC_TIMEOUT_MS + "ms"));
        assertEquals(ActionTypes.DIALOGUE, dispatched.actionId(), "dispatched a DIALOGUE action (type 30)");
        assertEquals(expectedHash, dispatched.param3(), "drove the continue button (1184,15)");
        assertEquals(DIALOGUE_PARAM1, dispatched.param1(), "dialogue param1");
        assertEquals(NO_SUB_SLOT, dispatched.param2(), "no sub-slot");

        // The load-bearing check: the page must actually move (close, advance to a
        // new line, or hand off to the options dialog).
        assertTrue(awaitChatMoved(beforeLine),
                "NPC chat must advance after continue — page should close or change from '" + beforeLine + "'");
        log.info("chat advanced — chatOpen={}, line now '{}'", Dialog.isChatOpen(api), npcLine());
    }

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

    /** Advanced when 1184 closes, or its spoken line changes from {@code before}. */
    private boolean awaitChatMoved(String before) throws InterruptedException {
        long deadline = System.nanoTime() + EXEC_TIMEOUT_MS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (!Dialog.isChatOpen(api)) {
                return true;
            }
            String now = npcLine();
            if (now != null && !now.equals(before)) {
                return true;
            }
            Thread.sleep(EXEC_POLL_MS);
        }
        return false;
    }

    /** The NPC's current spoken line, or {@code null} when 1184 isn't loaded. */
    private String npcLine() {
        ComponentNode line = api.components().get(Dialog.NPC_CHAT_INTERFACE, NPC_LINE_COMPONENT);
        return line == null ? null : line.text();
    }
}
