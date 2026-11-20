package com.botwithus.bot.api.combat;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.gameval.GamevalIndex;
import com.botwithus.bot.api.gameval.GamevalRef;
import com.botwithus.bot.api.gameval.GamevalType;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.model.ScriptResult;
import com.botwithus.bot.api.model.VarbitValue;
import com.botwithus.bot.api.snapshot.GameSnapshot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The main action bar (the bar on {@code TOPLEVEL_V2_COMBAT_BAR}): which preset bar it
 * shows, what each of its {@value #SLOTS} slots holds, using a slot, and cooldowns.
 *
 * <p>Slot contents come from the per-bar varbits {@code ACTIONBAR<bar>_INT<slot>_TYPE} /
 * {@code _ID} and the varp {@code ACTIONBAR<bar>_OBJ<slot>}, addressed by gameval name, and
 * are named through the cache ({@link AbilityCatalog}). With no gameval index deployed the
 * names do not resolve and {@link #slots()} is empty.</p>
 *
 * <p>Cooldowns are read through the client's own scripts, the join the game itself uses:
 * the cache has no link from an ability to its cooldown variables. See
 * {@link #cooldown(Ability)}.</p>
 */
public final class ActionBar {

    /** Slots on one bar. */
    public static final int SLOTS = 14;
    /** Highest preset bar number. */
    public static final int MAX_BAR = 18;

    /** Client script: ability struct to the struct its cooldown is kept under. */
    static final int SCRIPT_COOLDOWN_STRUCT = 8247;
    /** Client script: ability struct to its cooldown window {@code (start, end)} in cycles. */
    static final int SCRIPT_COOLDOWN_WINDOW = 6506;

    private static final GamevalRef CURRENT_BAR =
            new GamevalRef(GamevalType.VARBIT, "COMBATV2_ACTIONBAR_CURRENT_BAR", 1893);
    private static final GamevalRef GLOBAL_COOLDOWN_START = new GamevalRef(
            GamevalType.VAR_CLIENT, "COMBATV2_GLOBAL_COOLDOWN_START_CLIENT", 2091);
    private static final GamevalRef GLOBAL_COOLDOWN_END = new GamevalRef(
            GamevalType.VAR_CLIENT, "COMBATV2_GLOBAL_COOLDOWN_END_CLIENT", 2092);
    /** The slot's click layer: {@code GRAPHIC_n} carries the triggers, {@code BUTTON_n} has none. */
    private static final String SLOT_BUTTON_PREFIX = "TOPLEVEL_V2_COMBAT_BAR__GRAPHIC_";

    private static final int NO_SUB_INDEX = -1;
    private static final int OP_ACTIVATE = 1;
    private static final int EMPTY_TYPE = 0;
    private static final String INT_RETURN = "int";

    private final GameAPI api;
    private final AbilityCatalog catalog;

    public ActionBar(GameAPI api) {
        this(api, new AbilityCatalog(api));
    }

    public ActionBar(GameAPI api, AbilityCatalog catalog) {
        this.api = api;
        this.catalog = catalog;
    }

    /** The cache-backed ability lookup this bar resolves slots through. */
    public AbilityCatalog catalog() {
        return catalog;
    }

    /** The preset bar the main action bar shows, {@code 1..MAX_BAR}, or {@code -1} unknown. */
    public int activeBar() {
        int bar = api.getVarbit(CURRENT_BAR.resolve(api.gamevals()));
        return bar >= 1 && bar <= MAX_BAR ? bar : -1;
    }

    /** Every slot of the active bar, slot 1 first; empty when the bar cannot be read. */
    public List<ActionBarSlot> slots() {
        int bar = activeBar();
        return bar < 0 ? List.of() : slotsOf(bar);
    }

    /**
     * Every slot of preset bar {@code bar}, slot 1 first. Empty when {@code bar} is out of
     * range or its variables are not in the gameval index.
     */
    public List<ActionBarSlot> slotsOf(int bar) {
        if (bar < 1 || bar > MAX_BAR) {
            return List.of();
        }
        Optional<SlotVars> vars = SlotVars.resolve(api.gamevals(), bar);
        if (vars.isEmpty()) {
            return List.of();
        }
        List<VarbitValue> bits = api.queryVarbits(vars.get().varbits());
        List<Integer> items = api.getVarps(vars.get().itemVarps());
        List<ActionBarSlot> slots = new ArrayList<>(SLOTS);
        for (int i = 0; i < SLOTS; i++) {
            slots.add(decode(i + 1, bits.get(2 * i).value(), bits.get(2 * i + 1).value(),
                    items.get(i)));
        }
        return List.copyOf(slots);
    }

    /** Slot {@code slot} ({@code 1..SLOTS}) of the active bar. */
    public Optional<ActionBarSlot> slot(int slot) {
        List<ActionBarSlot> all = slots();
        return slot >= 1 && slot <= all.size() ? Optional.of(all.get(slot - 1)) : Optional.empty();
    }

    /** The active bar's slot holding the ability named {@code name} (ignoring case). */
    public Optional<ActionBarSlot.AbilitySlot> findAbility(String name) {
        for (ActionBarSlot slot : slots()) {
            switch (slot) {
                case ActionBarSlot.AbilitySlot held when held.ability().isNamed(name) -> {
                    return Optional.of(held);
                }
                case ActionBarSlot.AbilitySlot other -> { }
                case ActionBarSlot.Empty empty -> { }
                case ActionBarSlot.ItemSlot item -> { }
                case ActionBarSlot.Unresolved unresolved -> { }
            }
        }
        return Optional.empty();
    }

    /** True when the active bar holds the ability named {@code name}. */
    public boolean containsAbility(String name) {
        return findAbility(name).isPresent();
    }

    /**
     * Presses slot {@code slot} ({@code 1..SLOTS}) of the main bar. Returns whether the press
     * was queued; that the ability then fired is the game's decision (cooldown, adrenaline,
     * target), so check {@link #cooldown(Ability)} or the game state afterwards.
     */
    public boolean useSlot(int slot) {
        if (slot < 1 || slot > SLOTS) {
            return false;
        }
        OptionalInt button = api.gamevals().id(GamevalType.COMPONENT, SLOT_BUTTON_PREFIX + slot);
        if (button.isEmpty()) {
            return false;
        }
        api.queueAction(new GameAction(ActionTypes.COMPONENT, OP_ACTIVATE, NO_SUB_INDEX,
                button.getAsInt()));
        return true;
    }

    /** Presses the active bar's slot holding {@code name}; false when it is not on the bar. */
    public boolean useAbility(String name) {
        return findAbility(name).map(held -> useSlot(held.slot())).orElse(false);
    }

    /** The global cooldown window, read from its client varcs. */
    public Cooldown globalCooldown() {
        GamevalIndex index = api.gamevals();
        int start = api.getVarcInt(GLOBAL_COOLDOWN_START.resolve(index));
        int end = api.getVarcInt(GLOBAL_COOLDOWN_END.resolve(index));
        return Cooldown.of(start, end);
    }

    /**
     * The cooldown window of {@code ability}, or {@link Cooldown#UNKNOWN}.
     *
     * <p>Runs the client's own scripts {@value #SCRIPT_COOLDOWN_STRUCT} (ability struct to the
     * struct its cooldown is kept under) and {@value #SCRIPT_COOLDOWN_WINDOW} (that struct to
     * the {@code START_CLIENT}/{@code END_CLIENT} varc pair's values). Both only read client
     * variables. When either cannot run the answer is unknown; there is deliberately no
     * fallback that guesses the varcs from the ability's name, because the game names many
     * of them after an older ability (Barge's cooldown lives in {@code ..._ATTACK_CHARGE_...}).</p>
     */
    public Cooldown cooldown(Ability ability) {
        OptionalInt cooldownStruct = runIntScript(SCRIPT_COOLDOWN_STRUCT, ability.structId(), 1);
        if (cooldownStruct.isEmpty()) {
            return Cooldown.UNKNOWN;
        }
        Optional<ScriptResult> window =
                runScript(SCRIPT_COOLDOWN_WINDOW, cooldownStruct.getAsInt(), 2);
        if (window.isEmpty()) {
            return Cooldown.UNKNOWN;
        }
        OptionalInt start = window.get().intAt(0);
        OptionalInt end = window.get().intAt(1);
        if (start.isEmpty() || end.isEmpty()) {
            return Cooldown.UNKNOWN;
        }
        return Cooldown.of(start.getAsInt(), end.getAsInt());
    }

    /** The cooldown of the ability named {@code name}, or unknown when the cache has none. */
    public Cooldown cooldown(String name) {
        return catalog.byName(name).map(this::cooldown).orElse(Cooldown.UNKNOWN);
    }

    /**
     * Milliseconds until {@code ability} is off cooldown, {@code 0} when ready, {@code -1}
     * when unknown. Reads the snapshot's client cycle.
     */
    public long cooldownRemainingMs(Ability ability) {
        GameSnapshot snapshot = api.snapshot();
        if (snapshot == null) {
            return -1;
        }
        return cooldown(ability).remainingMs(snapshot.gameCycle());
    }

    private ActionBarSlot decode(int slot, int type, int id, int itemId) {
        if (itemId > 0) {
            return new ActionBarSlot.ItemSlot(slot, itemId);
        }
        if (type == EMPTY_TYPE || type < 0) {
            return new ActionBarSlot.Empty(slot);
        }
        return catalog.resolve(type, id)
                .<ActionBarSlot>map(ability -> new ActionBarSlot.AbilitySlot(slot, ability))
                .orElseGet(() -> new ActionBarSlot.Unresolved(slot, type, id));
    }

    private OptionalInt runIntScript(int scriptId, int arg, int returnCount) {
        return runScript(scriptId, arg, returnCount)
                .map(result -> result.intAt(0))
                .orElse(OptionalInt.empty());
    }

    private Optional<ScriptResult> runScript(int scriptId, int arg, int returnCount) {
        long handle;
        try {
            handle = api.getScriptHandle(scriptId);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        try {
            String[] returns = new String[returnCount];
            Arrays.fill(returns, INT_RETURN);
            return Optional.ofNullable(api.executeScript(handle, new int[] {arg}, null, returns));
        } catch (RuntimeException e) {
            return Optional.empty();
        } finally {
            releaseHandle(handle);
        }
    }

    private void releaseHandle(long handle) {
        try {
            api.destroyScriptHandle(handle);
        } catch (RuntimeException ignored) {
            // A handle that will not release costs the agent a slot, not this read.
        }
    }

    /** The gameval-resolved variable ids for one bar's slots. */
    private record SlotVars(List<Integer> varbits, List<Integer> itemVarps) {

        /** Type then id varbit per slot, interleaved; empty if any name is unknown. */
        static Optional<SlotVars> resolve(GamevalIndex index, int bar) {
            List<Integer> varbits = new ArrayList<>(2 * SLOTS);
            List<Integer> itemVarps = new ArrayList<>(SLOTS);
            for (int slot = 1; slot <= SLOTS; slot++) {
                String base = "ACTIONBAR" + bar + "_INT" + slot;
                OptionalInt type = index.id(GamevalType.VARBIT, base + "_TYPE");
                OptionalInt id = index.id(GamevalType.VARBIT, base + "_ID");
                OptionalInt item = index.id(GamevalType.VARP, "ACTIONBAR" + bar + "_OBJ" + slot);
                if (type.isEmpty() || id.isEmpty() || item.isEmpty()) {
                    return Optional.empty();
                }
                varbits.add(type.getAsInt());
                varbits.add(id.getAsInt());
                itemVarps.add(item.getAsInt());
            }
            return Optional.of(new SlotVars(List.copyOf(varbits), List.copyOf(itemVarps)));
        }
    }
}
