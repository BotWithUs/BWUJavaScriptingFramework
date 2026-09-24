package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.input.InputDialog;
import com.botwithus.bot.api.input.InputMode;
import com.botwithus.bot.api.input.KeyStroke;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.inventory.Bank;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.core.impl.snapshot.GameSnapshotImpl;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.shm.SharedRegion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live END-TO-END test of interface input: deposit-X then withdraw-X of the same
 * amount through {@code Bank.startDepositX/startWithdrawX} +
 * {@code Bank.finishTransferX}, asserting the backpack count moves by
 * <b>exactly</b> the amount each way, which restores the starting state.
 *
 * <p>What counts as a failure: the dialog never reaching amount mode (the menu op
 * did not open it); the count not moving (the digits were dropped or never
 * submitted); the count moving by anything other than the amount (a digit was
 * lost, doubled, or read from the wrong half of the key argument); or the dialog
 * still open afterwards (Enter did not submit).</p>
 *
 * <p><b>This test mutates the game.</b> Gated behind
 * {@code -Dbotwithus.smoke.input=true} (the {@code :core:liveInputDialogTest}
 * task). It needs the bank open, or {@code -Dbotwithus.input.openBank=a,loc,x,y}
 * to open it with that object action, and at least the amount of the item in
 * the backpack. It self-skips otherwise. Knobs: {@code botwithus.input.pid}
 * (required when more than one agent is running), {@code botwithus.input.item}
 * (default 436, copper ore), {@code botwithus.input.amount} (default 3).</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "botwithus.smoke.input", matches = "true")
class LiveInputDialogTest {

    private static final Logger log = LoggerFactory.getLogger(LiveInputDialogTest.class);

    private static final int DEFAULT_ITEM = 436;
    private static final int DEFAULT_AMOUNT = 3;
    /** The agent dispatches about one action per tick; allow generous headroom. */
    private static final long DIALOG_TIMEOUT_MS = 10_000;
    private static final long TRANSFER_TIMEOUT_MS = 12_000;
    private static final long BANK_OPEN_TIMEOUT_MS = 30_000;
    /** How long a moved count must hold before it is taken as final (two ticks). */
    private static final long SETTLE_MS = 1_500;
    private static final long POLL_MS = 150;
    private static final int OPEN_BANK_FIELDS = 4;

    private RpcClient rpc;
    private SharedRegion region;
    private GameAPI api;
    private Bank bank;
    private int item;
    private int amount;

    @BeforeAll
    void connect() throws InterruptedException {
        String pipe = choosePipe();
        long pid = SharedRegion.parsePid(pipe).orElseThrow();
        region = SharedRegion.open(pid);
        rpc = new RpcClient(new PipeClient(pipe));
        api = new GameAPIImpl(rpc, null, () -> new GameSnapshotImpl(region.snapshot()));
        bank = api.bank();
        item = Integer.getInteger("botwithus.input.item", DEFAULT_ITEM);
        amount = Integer.getInteger("botwithus.input.amount", DEFAULT_AMOUNT);

        openBankIfAsked();
        Assumptions.assumeTrue(bank.isOpen(),
                "SKIPPED, not passed: the bank is not open. Open it, or pass -Dbotwithus.input.openBank=a,loc,x,y");
        Assumptions.assumeTrue(api.backpack().count(item) >= amount,
                "SKIPPED, not passed: fewer than " + amount + " of item " + item + " in the backpack");
        Assumptions.assumeTrue(api.inputDialog().mode() == InputMode.CLOSED,
                "SKIPPED, not passed: an input dialog is already open");
        log.info("connected to pid {}; item {} x{}", pid, item, amount);
    }

    @AfterAll
    void disconnect() {
        if (rpc != null) {
            rpc.close();
        }
        if (region != null) {
            region.close();
        }
    }

    @Test
    void depositXThenWithdrawX_moveTheBackpackCountByExactlyTheAmount() throws InterruptedException {
        int start = api.backpack().count(item);

        assertTrue(bank.startDepositX(item), "deposit-X menu op queued");
        transfer(start - amount, "deposit-X");

        assertTrue(bank.startWithdrawX(item), "withdraw-X menu op queued");
        transfer(start, "withdraw-X");
    }

    /**
     * The pattern scripts written against the old javadoc use: type with the
     * type-only {@code fireKeyTrigger}, then submit with a <b>bare</b> Enter key
     * code through {@code fireComponentTrigger}. Unnormalised, that {@code 84}
     * would type a {@code 'T'} and never submit, and the count would not move.
     */
    @Test
    void legacyScriptPattern_typeThenBareEnterKeyCode_submits() throws InterruptedException {
        int start = api.backpack().count(item);

        assertTrue(bank.startDepositX(item), "deposit-X menu op queued");
        transfer(start - amount, "deposit-X (legacy)", this::typeThenLegacyEnter);

        assertTrue(bank.startWithdrawX(item), "withdraw-X menu op queued");
        transfer(start, "withdraw-X (legacy)", this::typeThenLegacyEnter);
    }

    private boolean typeThenLegacyEnter() {
        api.fireKeyTrigger(InputDialog.INTERFACE_ID, InputDialog.INPUT_COMPONENT, Integer.toString(amount));
        api.fireComponentTrigger(InputDialog.INTERFACE_ID, InputDialog.INPUT_COMPONENT,
                GameAPI.TOP_LEVEL_COMPONENT, ActionTypes.TRIGGER_TYPE_KEY, KeyStroke.CODE_ENTER);
        return true;
    }

    private void transfer(int expectedCount, String what) throws InterruptedException {
        transfer(expectedCount, what, () -> bank.finishTransferX(amount));
    }

    private void transfer(int expectedCount, String what, BooleanSupplier submitAmount)
            throws InterruptedException {
        assertTrue(await(() -> api.inputDialog().mode() == InputMode.AMOUNT, DIALOG_TIMEOUT_MS),
                what + ": the enter-amount dialog never opened (varc 5 != 7)");
        assertTrue(submitAmount.getAsBoolean(), what + ": the amount was refused by an open amount dialog");

        assertTrue(await(() -> api.backpack().count(item) == expectedCount, TRANSFER_TIMEOUT_MS),
                what + ": backpack count never reached " + expectedCount + ", now " + api.backpack().count(item));
        Thread.sleep(SETTLE_MS);
        assertEquals(expectedCount, api.backpack().count(item), what + ": count moved again after settling");
        assertTrue(await(() -> api.inputDialog().mode() == InputMode.CLOSED, DIALOG_TIMEOUT_MS),
                what + ": the dialog is still open after submit");
        log.info("{}: backpack count now {}", what, expectedCount);
    }

    private static String choosePipe() {
        List<String> pipes = PipeClient.scanPipes(PipeClient.NAME_PREFIX);
        Assumptions.assumeFalse(pipes.isEmpty(),
                "SKIPPED, not passed: no BotWithUs_<pid> pipe visible. Inject the agent into a running client.");
        String wanted = System.getProperty("botwithus.input.pid");
        if (wanted != null) {
            return pipes.stream().filter(p -> p.endsWith("_" + wanted)).findFirst()
                    .orElseThrow(() -> new AssertionError("no agent pipe for pid " + wanted + " in " + pipes));
        }
        Assumptions.assumeTrue(pipes.size() == 1,
                "SKIPPED, not passed: several agents running " + pipes + "; pick one with -Dbotwithus.input.pid");
        return pipes.getFirst();
    }

    private void openBankIfAsked() throws InterruptedException {
        String spec = System.getProperty("botwithus.input.openBank");
        if (spec == null || bank.isOpen()) {
            return;
        }
        int[] f = Arrays.stream(spec.split(",")).map(String::trim).mapToInt(Integer::parseInt).toArray();
        assertEquals(OPEN_BANK_FIELDS, f.length, "botwithus.input.openBank wants actionId,locId,x,y");
        api.queueAction(new GameAction(f[0], f[1], f[2], f[3]));
        await(bank::isOpen, BANK_OPEN_TIMEOUT_MS);
    }

    private static boolean await(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(POLL_MS);
        }
        return condition.getAsBoolean();
    }
}
