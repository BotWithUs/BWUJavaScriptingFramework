package com.botwithus.bot.api.entities;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.snapshot.Orientation;
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

    /** Scale of {@link #getHealthPercent()}: full health reads as this value. */
    private static final int FULL_HEALTH_PERCENT = 100;

    private final GameAPI api;
    // FQN: disambiguates from this-class entity Npc — see class-level note.
    private final com.botwithus.bot.api.snapshot.Npc raw;
    /** Definition cache shared across all Npc wrappers in the same Npcs facade. */
    private final IntFunction<NpcType> typeLookup;
    private NpcType cachedType;
    private NpcType cachedActiveType;

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
    /** The <b>base</b> type id the server sent. Identity; see {@link #activeTypeId()} for appearance. */
    public int typeId()         { return raw.typeId(); }
    /**
     * The morph-resolved type id (wire v24): the type the client names and shows options for.
     * Equals {@link #typeId()} for an NPC with no morph, {@code -1} when the morph resolves to
     * nothing (see {@link #isHidden()}).
     */
    public int activeTypeId()   { return raw.activeTypeId(); }
    /** True when this NPC is shown as a different type than its base one. */
    public boolean isMorphed()  { return raw.isMorphed(); }
    /** Display name from the active type, falling back to the base type; {@code null} if neither resolves. */
    public String name()        { NpcType t = getActiveType(); return t == null ? null : t.name(); }
    /**
     * Combat level as the client shows it. The per-instance wire value (v24) when there is one
     * ({@code 0} is real, for a non-combat NPC); otherwise the active type's, then the base
     * type's, then {@code 0}.
     */
    public int combatLevel() {
        if (raw.combatLevel() >= 0) {
            return raw.combatLevel();
        }
        NpcType t = getActiveType();
        return t == null ? 0 : t.combatLevel();
    }

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

    // ---------------- Convenience shims (kept for scripts that pre-date the rewrite) ----------------

    /** Same as {@code !isAlive()}. */
    public boolean isDead()        { return raw.hp() <= 0; }
    /** Alias for {@link #hp()}. */
    public int getHealth()         { return raw.hp(); }
    /** Alias for {@link #maxHp()}. */
    public int getMaxHealth()      { return raw.maxHp(); }
    /** Current health as a percentage of max health, {@code 0} when max health is unknown. */
    public int getHealthPercent()  {
        int max = raw.maxHp();
        return max <= 0 ? 0 : (raw.hp() * FULL_HEALTH_PERCENT) / max;
    }
    /**
     * True when the morph resolves to nothing ({@link #activeTypeId()} is {@code -1}), so the
     * client hides this NPC. An NPC whose base type did not resolve either reads false: that is
     * an unresolved row, not a hidden one.
     */
    public boolean isHidden()      { return raw.isHidden(); }
    /** Chebyshev distance to the local player tile, or {@code MAX_VALUE} if the player isn't loaded. */
    public int distanceToPlayer()  {
        var lp = api.getLocalPlayer();
        return lp == null ? Integer.MAX_VALUE : distanceTo(lp.tileX(), lp.tileY());
    }
    /** Alias for {@link #animationId()}. */
    public int getAnimation()      { return raw.animationId(); }
    /**
     * Currently-active spot anim ids (graphics) playing on this NPC. The
     * post-rewrite snapshot surfaces only the <em>first</em> concurrent spot
     * anim via {@link #spotAnimId()}, so this list is at most one-long — for
     * every newly-started anim, subscribe to {@code SpotAnimEvent}.
     */
    public List<Integer> getSpotAnims() {
        int id = raw.spotAnimId();
        return id <= 0 ? List.of() : List.of(id);
    }
    /** True when this NPC is following another entity ({@link #followingIndex()} != -1). */
    public boolean isFollowing()       { return raw.followingIndex() != -1; }
    /** Alias for {@link #followingIndex()}. */
    public int getFollowingIndex()     { return raw.followingIndex(); }
    /**
     * Two-arg variant kept for pre-rewrite scripts. {@code unusedSubOption} is
     * ignored — the option index encodes everything the action queue needs.
     * Delegates to {@link #interact(int)}.
     */
    public void interact(int optionIndex, int unusedSubOption) { interact(optionIndex); }
    /** First active spot anim (graphic) id playing on this NPC, or {@code -1} if none.
     *  Only the first concurrent spot anim is surfaced here — subscribe to
     *  {@code SpotAnimEvent} for every newly-started one. */
    public int spotAnimId()        { return raw.spotAnimId(); }

    /**
     * Which way this NPC is facing (wire v21): its rendered facing, interpolated while it turns.
     * Use {@link Orientation#compass()} or {@link Orientation#degrees()} to read it, and
     * {@link Orientation#isSameFacingAs} to compare two, never {@code equals}; the client can
     * read an angle back one unit short. Unknown when the producer could not read one.
     */
    public Orientation orientation() { return raw.orientation(); }

    // ---------------- Definition ----------------

    /**
     * Cached NpcType for this NPC's <b>base</b> typeId. May be {@code null} if the lookup fails.
     * For name and options use {@link #getActiveType()}: a morphing NPC's base type is often
     * nameless.
     */
    public NpcType getType() {
        if (cachedType == null) {
            cachedType = typeLookup.apply(typeId());
        }
        return cachedType;
    }

    /**
     * Cached NpcType the client actually shows: {@link #activeTypeId()} when there is one, else
     * the base type. Falls back to the base type when the active id does not resolve.
     * {@code null} when neither does.
     */
    public NpcType getActiveType() {
        if (cachedActiveType == null) {
            int activeId = raw.appearanceTypeId();
            NpcType active = activeId == typeId() ? null : typeLookup.apply(activeId);
            cachedActiveType = active != null ? active : getType();
        }
        return cachedActiveType;
    }

    /** Right-click options, from the active type. Empty list if the definition isn't available. */
    public List<String> getOptions() {
        NpcType t = getActiveType();
        return t == null ? List.of() : t.options();
    }

    /** True if any option matches {@code option} (case-insensitive). */
    public boolean hasOption(String option) {
        for (String o : getOptions()) {
            if (o != null && o.equalsIgnoreCase(option)) {
                return true;
            }
        }
        return false;
    }

    public boolean isDefinitionVisible() {
        NpcType t = getActiveType();
        return t != null && t.visible();
    }

    public boolean isClickable() {
        NpcType t = getActiveType();
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
        return "Npc{" + name() + " id=" + typeId() + (isMorphed() ? " active=" + activeTypeId() : "")
                + " @" + tileX() + "," + tileY() + "," + plane() + "}";
    }
}
