package com.botwithus.bot.api.entities;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.model.NpcType;

import java.util.List;
import java.util.function.IntFunction;

/**
 * Rich wrapper around a snapshot {@link com.botwithus.bot.api.snapshot.Npc}
 * record plus the cached {@link NpcType} definition. Carries the snapshot
 * fields for cheap access (position, animation, hp) and resolves the
 * definition lazily for name / options / interaction.
 *
 * <p>Obtained via {@link Npcs#query()}. Don't construct directly — the
 * facade owns the definition cache that makes repeat lookups cheap.</p>
 *
 * <p>Name collision note: the snapshot type {@code com.botwithus.bot.api.snapshot.Npc}
 * shares the simple name {@code Npc} with this wrapper, so the snapshot
 * type is fully qualified at the field declaration below and reflected
 * mechanically on the constructor parameter and {@link #raw()} accessor
 * (Java has no import aliasing, and the enclosing class shadows any
 * import of the snapshot type).</p>
 */
public final class Npc implements EntityContext {

    private final GameAPI api;
    // FQN: disambiguates from this-class entity Npc — see class-level note.
    private final com.botwithus.bot.api.snapshot.Npc raw;
    /** Definition cache shared across all Npc wrappers in the same Npcs facade. */
    private final IntFunction<NpcType> typeLookup;
    private NpcType cachedType;

    Npc(GameAPI api,
        com.botwithus.bot.api.snapshot.Npc raw,
        IntFunction<NpcType> typeLookup) {
        this.api = api;
        this.raw = raw;
        this.typeLookup = typeLookup;
    }

    /** The underlying snapshot record. */
    public com.botwithus.bot.api.snapshot.Npc raw() { return raw; }

    // ---------------- Identity ----------------

    public int serverIndex()    { return raw.serverIndex(); }
    public int typeId()         { return raw.typeId(); }
    public String name()        { NpcType t = getType(); return t == null ? null : t.name(); }
    public int combatLevel()    { NpcType t = getType(); return t == null ? 0 : t.combatLevel(); }

    // ---------------- Position (EntityContext) ----------------

    @Override public int tileX() { return raw.tileX(); }
    @Override public int tileY() { return raw.tileY(); }
    @Override public int plane() { return raw.plane(); }

    // ---------------- Snapshot state ----------------

    public boolean isMoving()      { return raw.isMoving(); }
    public int followingIndex()    { return raw.followingIndex(); }
    public int animationId()       { return raw.animationId(); }
    public int stanceId()          { return raw.stanceId(); }
    public int hp()                { return raw.hp(); }
    public int maxHp()             { return raw.maxHp(); }
    public boolean isAlive()       { return raw.hp() > 0; }

    // ---------------- Definition ----------------

    /** Cached NpcType for this NPC's base typeId. May be {@code null} if the lookup fails. */
    public NpcType getType() {
        if (cachedType == null) {
            cachedType = typeLookup.apply(typeId());
        }
        return cachedType;
    }

    /** Right-click options. Empty list if the definition isn't available. */
    public List<String> getOptions() {
        NpcType t = getType();
        return t == null ? List.of() : t.options();
    }

    /** True if any option matches {@code option} (case-insensitive). */
    public boolean hasOption(String option) {
        for (String o : getOptions()) {
            if (o != null && o.equalsIgnoreCase(option)) return true;
        }
        return false;
    }

    public boolean isDefinitionVisible() {
        NpcType t = getType();
        return t != null && t.visible();
    }

    public boolean isClickable() {
        NpcType t = getType();
        return t != null && t.clickable();
    }

    // ---------------- Interaction ----------------

    /**
     * Queue an action against this NPC by 1-based option index. Index 1 is
     * the default left-click action (e.g. "Attack"); 2-6 are the right-click
     * sub-options shown on the menu.
     *
     * @throws IllegalArgumentException for {@code optionIndex} outside [1, 6]
     */
    public void interact(int optionIndex) {
        if (optionIndex < 1 || optionIndex >= ActionTypes.NPC_OPTIONS.length) {
            throw new IllegalArgumentException("NPC option index out of range: " + optionIndex);
        }
        api.queueAction(new GameAction(
                ActionTypes.NPC_OPTIONS[optionIndex],
                serverIndex(), 0, 0));
    }

    /**
     * Queue an action by option text (e.g. "Attack", "Talk-to"). Returns
     * {@code false} when the option isn't on this NPC's menu (no action queued).
     */
    public boolean interact(String option) {
        List<String> opts = getOptions();
        for (int i = 0; i < opts.size(); ++i) {
            String o = opts.get(i);
            if (o != null && o.equalsIgnoreCase(option)) {
                interact(i + 1);
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "Npc{" + name() + " id=" + typeId()
                + " @" + tileX() + "," + tileY() + "," + plane() + "}";
    }
}
