package com.botwithus.bot.api.inventory;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.component.ComponentNode;
import com.botwithus.bot.api.entities.SceneObject;
import com.botwithus.bot.api.gameval.GamevalRef;
import com.botwithus.bot.api.gameval.GamevalType;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.snapshot.InventoryItem;
import com.botwithus.bot.api.util.Interfaces;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * A bank deposit box ({@code BANK_DEPOSIT_BOX}, 11): open it from the nearest box, deposit
 * everything or everything but some items, worn items, the familiar's items or the money
 * pouch. Obtained from {@link GameAPI#depositBox()}.
 *
 * <p>The four deposit buttons and Close are found by the option they list (the names xapi
 * used), over one {@code get_interface_tree} plus one {@code get_component_ops} per node, so a
 * renumbered button is still found. A single item stack is deposited from the box's backpack
 * grid with the op its slot lists as {@value #DEPOSIT_ALL}. Every method returns
 * {@code false}, and queues nothing, when the box is not open or the option is not listed.</p>
 */
public final class DepositBox {

    private static final Logger log = LoggerFactory.getLogger(DepositBox.class);

    private static final int INTERFACE_ID = 11;
    private static final GamevalRef INTERFACE = new GamevalRef(GamevalType.INTERFACE,
            "BANK_DEPOSIT_BOX", INTERFACE_ID);
    /** The box's view of the backpack: one sub per backpack slot. */
    private static final GamevalRef BACKPACK_GRID = new GamevalRef(GamevalType.COMPONENT,
            "BANK_DEPOSIT_BOX__ICONS", Interfaces.componentHash(INTERFACE_ID, 19));

    /** The scene option that opens a deposit box. */
    static final String OPEN = "Deposit";
    static final String DEPOSIT_CARRIED = "Deposit Carried Items";
    static final String DEPOSIT_WORN = "Deposit Worn Items";
    static final String DEPOSIT_FAMILIAR = "Deposit Familiar's Items";
    static final String DEPOSIT_POUCH = "Deposit Money Pouch";
    static final String CLOSE = "Close";
    static final String DEPOSIT_ALL = "Deposit-All";

    private final GameAPI api;

    public DepositBox(GameAPI api) {
        this.api = api;
    }

    /** True when the deposit box is open. Snapshot read: no round-trip. */
    public boolean isOpen() {
        return api.isInterfaceOpen(INTERFACE.resolve(api.gamevals()));
    }

    /**
     * Uses the nearest scene object that offers {@value #OPEN}.
     *
     * @return {@code true} when such an object was found and the click queued; the box opens
     *         once the player reaches it, so wait for {@link #isOpen()}
     */
    public boolean open() {
        SceneObject box = api.objects().query().withOption(OPEN).nearest();
        return box != null && box.interact(OPEN);
    }

    /** Presses the box's Close button. */
    public boolean close() {
        return press(CLOSE);
    }

    /** Deposits everything carried in the backpack. */
    public boolean depositAll() {
        return press(DEPOSIT_CARRIED);
    }

    /** Deposits worn equipment. */
    public boolean depositWorn() {
        return press(DEPOSIT_WORN);
    }

    /** Deposits the familiar's items. */
    public boolean depositFamiliar() {
        return press(DEPOSIT_FAMILIAR);
    }

    /** Deposits the money pouch. */
    public boolean depositMoneyPouch() {
        return press(DEPOSIT_POUCH);
    }

    /** Deposits every carried item except those with the given ids, one stack per item. */
    public boolean depositAllExcept(int... itemIds) {
        return depositAllMatching(InventoryContainer.byIds(itemIds).negate());
    }

    /** Deposits every carried item not named exactly (ignoring case) any of {@code names}. */
    public boolean depositAllExcept(String... names) {
        return depositAllMatching(api.backpack().byExactNames(names).negate());
    }

    /** Deposits every carried item whose whole name matches none of {@code namePatterns}. */
    public boolean depositAllExcept(Pattern... namePatterns) {
        return depositAllMatching(api.backpack().byPatterns(namePatterns).negate());
    }

    private boolean press(String option) {
        if (!isOpen()) {
            return false;
        }
        ComponentNode button = api.components().in(INTERFACE.resolve(api.gamevals()))
                .withOption(option).first();
        if (button == null) {
            log.debug("deposit box lists no '{}' button", option);
            return false;
        }
        return button.interact(option);
    }

    /**
     * One {@value #DEPOSIT_ALL} on the first slot of each distinct carried item that
     * {@code matcher} accepts.
     *
     * @return {@code true} when the box was open and every matched item had the option
     */
    private boolean depositAllMatching(Predicate<InventoryItem> matcher) {
        if (!isOpen()) {
            return false;
        }
        int grid = BACKPACK_GRID.resolve(api.gamevals());
        Set<Integer> queued = new HashSet<>();
        boolean allQueued = true;
        for (InventoryItem item : api.backpack().allMatching(matcher)) {
            if (queued.add(item.itemId())) {
                allQueued &= depositSlot(grid, item);
            }
        }
        return allQueued;
    }

    private boolean depositSlot(int grid, InventoryItem item) {
        OptionalInt op = api.getComponentOps(Interfaces.interfaceIdFromHash(grid),
                Interfaces.componentIdFromHash(grid), item.slot()).opNamed(DEPOSIT_ALL);
        if (op.isEmpty()) {
            log.debug("deposit box slot {} lists no '{}'", item.slot(), DEPOSIT_ALL);
            return false;
        }
        api.queueAction(new GameAction(ActionTypes.COMPONENT, op.getAsInt(), item.slot(), grid));
        return true;
    }
}
