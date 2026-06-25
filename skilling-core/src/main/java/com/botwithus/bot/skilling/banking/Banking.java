package com.botwithus.bot.skilling.banking;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.Navigation;
import com.botwithus.bot.api.entities.SceneObject;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.inventory.Bank;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.model.WalkResult;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.InventoryItem;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.api.util.Interfaces;
import com.botwithus.bot.skilling.atlas.Atlas;
import com.botwithus.bot.skilling.atlas.NamedComponent;
import com.botwithus.bot.skilling.atlas.Tile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Banking helper — a greenfield facade over the bank interface (517), because the
 * {@code api} has no deposit/withdraw surface of its own. It resolves the nearest bank
 * from the Atlas, walks to it with the pathfinder, opens a booth, and drives every bank
 * operation the client exposes that does not require keyboard/dialog plumbing: bulk
 * deposits (backpack / equipment / beast-of-burden / money pouch), per-item withdraw and
 * deposit by quantity, the note + placeholder mode toggles, and close.
 *
 * <h2>How a bank action is queued</h2>
 * Every bank click is a real server-side {@code COMPONENT} doAction (the engine's
 * {@code doAction} path), <b>not</b> a CS2 client trigger — the bank buttons ignore
 * {@code fireComponentTrigger} (it only fires the client-side onclick). There are two
 * param layouts, and mixing them up is a silent no-op:
 * <ul>
 *   <li><b>Plain button</b> (deposit-all buttons, toggles, close): {@code param1 = op},
 *       {@code param2 = -1} (no sub-slot), {@code param3 = compHash}. Matches
 *       {@code ComponentNode#interact}.</li>
 *   <li><b>Item-slot grid</b> (withdraw a banked item, deposit a carried item):
 *       {@code param1 = op}, {@code param2 = compHash}, {@code param3 = slot}. Matches
 *       {@code InventoryContainer#interact}.</li>
 * </ul>
 *
 * <h2>Component ids are resolved by gameval name</h2>
 * Each button/grid is looked up <b>from the gamevals</b> by name via the Atlas (e.g.
 * {@code BANK__BANK_INV_BUTTON} → interface 517, component 39), so a game-update renumber
 * is fixed by rebuilding the Atlas — not by editing code. When the Atlas is absent the
 * CS2-verified hardcoded fallback is used; an explicit {@code -D…} system-property override
 * always wins. The fallbacks below were read off the decompiled client scripts
 * ({@code set_menu_option} op layout) and the {@code gameval_beta_out} component table.
 *
 * <h2>Op indices</h2>
 * The withdraw (banked-items grid) and deposit (carried-items grid) right-click menus share
 * one layout: {@code 1}=default-quantity (configurable), {@code 2}=‑1, {@code 3}=‑5,
 * {@code 4}=‑10, {@code 5}=‑X(last saved), {@code 6}=‑X(enter amount), {@code 7}=‑All.
 * Arbitrary "enter amount" withdrawals (op 6) need the integer-entry dialog and are out of
 * scope here — {@link #withdraw(int, int)}/{@link #deposit(int, int)} support the exact
 * quantities {1, 5, 10, All} and fall back to All for anything else.
 *
 * <p>Not safe for concurrent use — like the {@link Atlas} it reads, a script touches this
 * only from its single script thread.</p>
 */
public final class Banking {

    private static final Logger log = LoggerFactory.getLogger(Banking.class);

    /** RS3 bank interface id (517). */
    public static final int BANK_INTERFACE = Bank.INTERFACE_ID;

    /** Gameval name of the deposit-carried-inventory button (interface 517, comp 39). */
    public static final String DEPOSIT_INVENTORY_GAMEVAL = "BANK__BANK_INV_BUTTON";
    /** Gameval name of the banked-items grid (the withdraw target — interface 517, comp 201). */
    public static final String ITEMS_GAMEVAL = "BANK__BANK_INV";
    /** Gameval name of the bank's embedded backpack grid (the deposit-item target — comp 15). */
    public static final String BACKPACK_ITEMS_GAMEVAL = "BANK__INVENTORY_CLICK";

    /** Override the deposit-inventory button component id. */
    public static final String DEPOSIT_COMPONENT_PROPERTY = "botwithus.bank.depositInventoryComponent";
    /** Override the deposit-inventory button op index (default left-click). */
    public static final String DEPOSIT_OP_PROPERTY = "botwithus.bank.depositOp";
    /** Override the banked-item withdraw op index used by {@link #withdrawOne(int)}. */
    public static final String WITHDRAW_OP_PROPERTY = "botwithus.bank.withdrawOp";
    /** Override the banked-items grid component id. */
    public static final String ITEMS_COMPONENT_PROPERTY = "botwithus.bank.itemsComponent";
    /** Override the bank's embedded backpack grid component id. */
    public static final String BACKPACK_COMPONENT_PROPERTY = "botwithus.bank.backpackComponent";

    // CS2-verified fallbacks (used only when no Atlas + no override).
    private static final int FALLBACK_DEPOSIT_COMPONENT = 39;    // BANK__BANK_INV_BUTTON
    private static final int FALLBACK_ITEMS_COMPONENT = 201;     // BANK__BANK_INV (NOT 195 = scrollbar)
    private static final int FALLBACK_BACKPACK_COMPONENT = 15;   // BANK__INVENTORY_CLICK
    private static final int NO_SUB_SLOT = -1;                   // plain button, not a slot grid

    // Bank right-click menu op indices (1-based), from the CS2 set_menu_option layout.
    private static final int OP_DEFAULT = 1;   // configurable default quantity / button left-click
    private static final int OP_ONE = 2;       // Withdraw/Deposit-1
    private static final int OP_FIVE = 3;      // -5
    private static final int OP_TEN = 4;       // -10
    private static final int OP_ALL = 7;       // -All

    /**
     * The fixed bank buttons that fire a single op-1 left-click. Each carries its gameval
     * name, the CS2-verified fallback component id, and an optional system-property override.
     */
    private enum BankButton {
        DEPOSIT_EQUIPMENT("BANK__BANK_WORN_BUTTON", 42, null),
        DEPOSIT_BEAST_OF_BURDEN("BANK__BANK_BOB_BUTTON", 45, null),
        DEPOSIT_MONEY_POUCH("BANK__BANK_POUCH_BUTTON", 48, null),
        NOTE_TOGGLE("BANK__BANK_CERT_BUTTON", 127, null),
        PLACEHOLDER_TOGGLE("BANK__PLACEHOLDER_BUTTON", 123, null),
        CLOSE("BANK__CLOSE_BUTTON_LAYER", 317, null);

        private final String gameval;
        private final int fallbackComponent;
        private final String overrideProperty;

        BankButton(String gameval, int fallbackComponent, String overrideProperty) {
            this.gameval = gameval;
            this.fallbackComponent = fallbackComponent;
            this.overrideProperty = overrideProperty;
        }
    }

    /** A resolved (interface, component) target for a bank doAction. */
    private record Resolved(int interfaceId, int componentId) {}

    private final GameAPI api;
    private final Navigation nav;
    private final Atlas atlas; // nullable — scene-only mode when absent

    public Banking(GameAPI api, Navigation nav, Atlas atlas) {
        this.api = api;
        this.nav = nav;
        this.atlas = atlas;
    }

    // -------------------------------------------------------------------- state

    /** Whether the bank interface is currently open. */
    public boolean isOpen() {
        GameSnapshot s = api.snapshot();
        return s != null && s.isInterfaceOpen(BANK_INTERFACE);
    }

    /** True when a usable bank booth is already in the loaded scene within {@code radius}. */
    public boolean boothInRange(int radius) {
        return api.objects().query()
                .withinDistance(radius)
                .filter(o -> o.hasOption("Bank"))
                .exists();
    }

    /** Total quantity of {@code itemId} banked (0 when the bank inventory isn't published). */
    public int count(int itemId) {
        return api.bank().count(itemId);
    }

    /** Whether {@code itemId} is present in the bank. */
    public boolean contains(int itemId) {
        return api.bank().contains(itemId);
    }

    /** Free bank slots from the snapshot header (best-effort — relies on the published slot count). */
    public int freeSlots() {
        return api.bank().freeSlots();
    }

    // --------------------------------------------------------------- open/close

    /** Click the nearest bank booth/chest to open the bank. */
    public boolean openNearbyBooth() {
        SceneObject booth = api.objects().query()
                .withinDistance(10)
                .filter(o -> o.hasOption("Bank") || o.hasOption("Use"))
                .nearest();
        if (booth == null) {
            return false;
        }
        return booth.interact("Bank") || booth.interact("Use");
    }

    /** Walk to the nearest Atlas bank (or no-op when a booth is already in range). */
    public boolean walkToNearestBank() {
        LocalPlayer p = api.getLocalPlayer();
        if (p == null) {
            return false;
        }
        if (boothInRange(8)) {
            return true;
        }
        if (atlas == null) {
            log.warn("No Atlas — cannot resolve a bank to walk to");
            return false;
        }
        Optional<Tile> bank = atlas.nearestBank(p.tileX(), p.tileY(), p.plane());
        if (bank.isEmpty()) {
            log.warn("Atlas has no bank on plane {}", p.plane());
            return false;
        }
        Tile t = bank.get();
        log.info("Walking to bank at ({},{},{})", t.x(), t.y(), t.plane());
        WalkResult r = nav.walkWorldPath(t.x(), t.y(), t.plane());
        if (r != WalkResult.ARRIVED) {
            log.warn("Walk to bank ended {}", r);
            return false;
        }
        return true;
    }

    /**
     * One-step-per-tick "get the bank open": no-op when already open, else walk to the
     * nearest bank and click a booth. Returns {@code true} once a booth-open was queued (or
     * the bank is already open); the caller re-checks {@link #isOpen()} on the next tick.
     */
    public boolean open() {
        if (isOpen()) {
            return true;
        }
        if (!walkToNearestBank()) {
            return false;
        }
        return openNearbyBooth();
    }

    /** Close the open bank interface. No-op (returns {@code false}) when the bank is closed. */
    public boolean close() {
        return clickWhenOpen(BankButton.CLOSE);
    }

    // ------------------------------------------------------------ bulk deposits

    /**
     * Queue the bank's "deposit carried items" doAction (the backpack). Requires the bank
     * open. Fire-and-forget — preserved for the gather-loop's banking sequence; for a
     * guarded variant use {@link #depositBackpack()}.
     */
    public void depositInventory() {
        doDepositBackpack();
    }

    /** Deposit the whole backpack ("deposit carried items"). {@code false} when bank closed. */
    public boolean depositBackpack() {
        if (!isOpen()) {
            return false;
        }
        doDepositBackpack();
        return true;
    }

    /** Deposit worn equipment ("deposit worn items"). {@code false} when bank closed. */
    public boolean depositEquipment() {
        return clickWhenOpen(BankButton.DEPOSIT_EQUIPMENT);
    }

    /** Deposit the familiar's beast-of-burden inventory. {@code false} when bank closed. */
    public boolean depositBeastOfBurden() {
        return clickWhenOpen(BankButton.DEPOSIT_BEAST_OF_BURDEN);
    }

    /** Deposit the money pouch. {@code false} when bank closed. */
    public boolean depositMoneyPouch() {
        return clickWhenOpen(BankButton.DEPOSIT_MONEY_POUCH);
    }

    private void doDepositBackpack() {
        int op = Integer.getInteger(DEPOSIT_OP_PROPERTY, OP_DEFAULT);
        Resolved r = resolve(DEPOSIT_INVENTORY_GAMEVAL, FALLBACK_DEPOSIT_COMPONENT, DEPOSIT_COMPONENT_PROPERTY);
        log.debug("Deposit-inventory doAction → iface {} comp {} op {}", r.interfaceId(), r.componentId(), op);
        queueButton(r, op);
    }

    // ----------------------------------------------------------------- withdraw

    /**
     * Withdraw exactly one of {@code itemId} (op 2, "Withdraw-1"). Immune to the player's
     * configured default left-click quantity. Targets the {@code BANK__BANK_INV} grid (201),
     * not {@code Bank.COMPONENT_ID} (195, which is the scrollbar). Override the op with
     * {@code -Dbotwithus.bank.withdrawOp=<index>}. No-op (returns {@code false}) when the bank
     * is closed or the item isn't banked.
     */
    public boolean withdrawOne(int itemId) {
        int op = Integer.getInteger(WITHDRAW_OP_PROPERTY, OP_ONE);
        return withdrawSlot(itemId, op);
    }

    /** Withdraw the entire stack of {@code itemId} (op 7, "Withdraw-All"). */
    public boolean withdrawAll(int itemId) {
        return withdrawSlot(itemId, OP_ALL);
    }

    /**
     * Withdraw {@code amount} of {@code itemId} using the exact-quantity menu ops — {@code 1},
     * {@code 5} and {@code 10} map to Withdraw-1/5/10; any other amount falls back to
     * Withdraw-All (arbitrary "enter amount" needs the integer dialog, out of scope).
     */
    public boolean withdraw(int itemId, int amount) {
        return withdrawSlot(itemId, opForAmount(amount));
    }

    private boolean withdrawSlot(int itemId, int op) {
        if (!isOpen()) {
            return false;
        }
        InventoryItem it = api.bank().getFirst(itemId);
        if (it == null) {
            return false;
        }
        Resolved r = resolve(ITEMS_GAMEVAL, FALLBACK_ITEMS_COMPONENT, ITEMS_COMPONENT_PROPERTY);
        api.queueAction(new GameAction(ActionTypes.COMPONENT, op,
                Interfaces.componentHash(r.interfaceId(), r.componentId()), it.slot()));
        return true;
    }

    // ------------------------------------------------------------ item deposits

    /** Deposit the entire carried stack of {@code itemId} (op 7, "Deposit-All"). */
    public boolean depositAll(int itemId) {
        return depositItem(itemId, OP_ALL);
    }

    /**
     * Deposit {@code amount} of {@code itemId} from the backpack while banking — {@code 1},
     * {@code 5} and {@code 10} map to Deposit-1/5/10; any other amount falls back to
     * Deposit-All. Clicks the bank's embedded backpack grid ({@code BANK__INVENTORY_CLICK}),
     * whose right-click menu the client remaps to Deposit-ops while the bank is open.
     */
    public boolean deposit(int itemId, int amount) {
        return depositItem(itemId, opForAmount(amount));
    }

    private boolean depositItem(int itemId, int op) {
        if (!isOpen()) {
            return false;
        }
        InventoryItem it = api.backpack().getFirst(itemId);
        if (it == null) {
            return false;
        }
        Resolved r = resolve(BACKPACK_ITEMS_GAMEVAL, FALLBACK_BACKPACK_COMPONENT, BACKPACK_COMPONENT_PROPERTY);
        api.queueAction(new GameAction(ActionTypes.COMPONENT, op,
                Interfaces.componentHash(r.interfaceId(), r.componentId()), it.slot()));
        return true;
    }

    // ------------------------------------------------------------------ toggles

    /**
     * Toggle "withdraw as note/cert" mode (one-shot click on {@code BANK__BANK_CERT_BUTTON}).
     * Stateless — it flips the current mode; the caller decides when to toggle.
     */
    public boolean toggleNoteMode() {
        return clickWhenOpen(BankButton.NOTE_TOGGLE);
    }

    /**
     * Toggle "always set placeholder" mode (one-shot click on {@code BANK__PLACEHOLDER_BUTTON}).
     * Stateless — it flips the current mode.
     */
    public boolean togglePlaceholderMode() {
        return clickWhenOpen(BankButton.PLACEHOLDER_TOGGLE);
    }

    // -------------------------------------------------------------- convenience

    /**
     * Drive one bank cycle for a gather script: get to a bank, open it, deposit. Designed to
     * be called from a loop — it advances one step per call and the caller re-checks
     * {@code backpack.isFull()} on the next tick:
     * <ol>
     *   <li>not at/openable bank yet → walk + open, return {@code false}</li>
     *   <li>bank open → deposit carried items, return {@code true}</li>
     * </ol>
     *
     * @param resourceItemId the gathered item (for logging; deposit clears the pack)
     * @return {@code true} once a deposit was issued this call
     */
    public boolean bankResource(int resourceItemId) {
        if (!isOpen()) {
            if (!walkToNearestBank()) {
                return false;
            }
            if (!openNearbyBooth()) {
                log.warn("At a bank tile but found no booth to open");
            }
            return false; // let the next tick observe the opened interface, then deposit
        }
        log.debug("Depositing carried items (resource {})", resourceItemId);
        depositInventory();
        return true;
    }

    // --------------------------------------------------------------- internals

    /** Click a fixed bank button with the left-click op (1), but only when the bank is open. */
    private boolean clickWhenOpen(BankButton button) {
        if (!isOpen()) {
            return false;
        }
        Resolved r = resolve(button.gameval, button.fallbackComponent, button.overrideProperty);
        queueButton(r, OP_DEFAULT);
        return true;
    }

    /** Queue a plain-button doAction: op in param1, no sub-slot in param2, compHash in param3. */
    private void queueButton(Resolved r, int op) {
        api.queueAction(new GameAction(ActionTypes.COMPONENT, op, NO_SUB_SLOT,
                Interfaces.componentHash(r.interfaceId(), r.componentId())));
    }

    /**
     * Resolve a component target: a {@code -D} override wins (component within interface 517),
     * else the Atlas gameval, else the CS2-verified fallback within interface 517.
     */
    private Resolved resolve(String gameval, int fallbackComponent, String overrideProperty) {
        if (overrideProperty != null) {
            Integer override = Integer.getInteger(overrideProperty);
            if (override != null) {
                return new Resolved(BANK_INTERFACE, override);
            }
        }
        if (atlas != null) {
            Optional<NamedComponent> nc = atlas.component(gameval);
            if (nc.isPresent()) {
                return new Resolved(nc.get().interfaceId(), nc.get().componentId());
            }
        }
        return new Resolved(BANK_INTERFACE, fallbackComponent);
    }

    /** Map a desired quantity to the exact-quantity menu op, falling back to All. */
    private static int opForAmount(int amount) {
        return switch (amount) {
            case 1 -> OP_ONE;
            case 5 -> OP_FIVE;
            case 10 -> OP_TEN;
            default -> {
                if (amount > 0 && amount != Integer.MAX_VALUE) {
                    log.debug("No exact bank op for amount {} — using All (op {})", amount, OP_ALL);
                }
                yield OP_ALL;
            }
        };
    }
}
