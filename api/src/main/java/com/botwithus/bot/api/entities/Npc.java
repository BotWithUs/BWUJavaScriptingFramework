package com.botwithus.bot.api.entities;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.Entity;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.model.NpcType;

import java.util.List;

/**
 * Rich wrapper for an NPC entity with convenient access to the NPC definition
 * and interaction helpers.
 *
 * <p>Obtain instances through {@link Npcs}:</p>
 * <pre>{@code
 * Npcs npcs = new Npcs(api);
 * Npc goblin = npcs.nearest("Goblin");
 * if (goblin != null) {
 *     System.out.println(goblin.name() + " combat: " + goblin.getCombatLevel());
 *     System.out.println("Options: " + goblin.getOptions());
 * }
 * }</pre>
 *
 * @see Npcs
 * @see EntityContext
 */
public class Npc extends EntityContext {

    private NpcType cachedType;

    public Npc(GameAPI api, Entity raw) {
        super(api, raw);
    }

    /**
     * Returns the NPC cache definition for the base (parent) type, fetched lazily.
     */
    public NpcType getType() {
        if (cachedType == null) {
            cachedType = api.getNpcType(typeId());
        }
        return cachedType;
    }

    /**
     * The NPC's right-click interaction options, resolved through any active transformation.
     * Prefers the pre-resolved options from the entity query (C++ side) when available,
     * falling back to the resolved NPC type definition.
     */
    public List<String> getOptions() {
        List<String> entityOptions = raw.options();
        if (entityOptions != null && !entityOptions.isEmpty()) {
            return entityOptions;
        }
        return resolveTransform().options();
    }

    /** Whether this NPC has a specific right-click option (case-insensitive). */
    public boolean hasOption(String option) {
        return containsOption(getOptions(), option);
    }

    /** Whether this NPC is visible according to its definition. */
    public boolean isDefinitionVisible() {
        return getType().visible();
    }

    /** Whether this NPC is clickable according to its definition. */
    public boolean isClickable() {
        return getType().clickable();
    }

    // ========================== Transformation ==========================

    /**
     * Whether this NPC can transform based on a varbit/varp value.
     */
    public boolean canTransform() {
        return getType().varbitId() != -1 || getType().varpId() != -1;
    }

    /** The varbit controlling transformation, or -1. */
    public int getVarbitId() {
        return getType().varbitId();
    }

    /** The varp controlling transformation, or -1. */
    public int getVarpId() {
        return getType().varpId();
    }

    /** The possible type IDs this NPC can transform into. */
    public List<Integer> getTransforms() {
        return getType().transforms();
    }

    /**
     * Resolves the current transform of this NPC based on varbit/varp state.
     * Returns this NPC's base type ID if no transformation is active,
     * or the transformed type ID.
     */
    public int resolveTransformId() {
        NpcType type = getType();
        int value = -1;
        if (type.varbitId() != -1) {
            value = api.getVarbit(type.varbitId());
        } else if (type.varpId() != -1) {
            value = api.getVarp(type.varpId());
        }
        if (value < 0 || type.transforms().isEmpty()) {
            return typeId();
        }
        if (value < type.transforms().size()) {
            int transformed = type.transforms().get(value);
            return transformed != -1 ? transformed : typeId();
        }
        int last = type.transforms().getLast();
        return last != -1 ? last : typeId();
    }

    /**
     * Returns the resolved {@link NpcType} after applying any varbit/varp transformation.
     */
    public NpcType resolveTransform() {
        int id = resolveTransformId();
        return id == typeId() ? getType() : api.getNpcType(id);
    }

    // ========================== Interaction ==========================

    /**
     * Interacts with this NPC using the given right-click option name.
     *
     * @param option the option text (e.g. "Attack", "Talk-to"), case-insensitive
     * @return {@code true} if the option was found and the action was queued
     */
    public boolean interact(String option) {
        int index = findOptionIndex(getOptions(), option);
        if (index == -1) return false;
        interact(index);
        return true;
    }

    /**
     * Interacts with this NPC using the given 1-based option index.
     *
     * @param optionIndex the 1-based option index (1–6)
     */
    public void interact(int optionIndex) {
        if (optionIndex < 1 || optionIndex >= ActionTypes.NPC_OPTIONS.length) {
            throw new IllegalArgumentException("NPC option index out of range: " + optionIndex);
        }
        api.queueAction(new GameAction(ActionTypes.NPC_OPTIONS[optionIndex], raw.serverIndex(), 0, 0));
    }

    @Override
    public String toString() {
        return "Npc{" + name() + " id=" + typeId()
                + " @" + tileX() + "," + tileY() + "," + plane() + "}";
    }
}
