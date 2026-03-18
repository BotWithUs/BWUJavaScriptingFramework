package com.botwithus.bot.api.entities;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.*;

import java.util.List;

/**
 * Rich wrapper around a raw {@link Entity} that provides convenient access
 * to entity state without manually invoking {@link GameAPI} methods.
 *
 * <p>Extended information (health, animation, etc.) is fetched lazily on first
 * access and can be refreshed by calling {@link #refresh()}.</p>
 *
 * @see Npc
 * @see SceneObject
 * @see Player
 */
public class EntityContext {

    protected final GameAPI api;
    protected final Entity raw;

    private EntityInfo cachedInfo;

    public EntityContext(GameAPI api, Entity raw) {
        this.api = api;
        this.raw = raw;
    }

    /**
     * Returns the underlying raw {@link Entity} record.
     */
    public Entity raw() {
        return raw;
    }

    // ========================== Identity ==========================

    /** The unique handle for referencing this entity in API calls. */
    public int handle() { return raw.handle(); }

    /** The server-side index. */
    public int serverIndex() { return raw.serverIndex(); }

    /** The entity type/definition ID (NPC ID, object ID, etc.). */
    public int typeId() { return raw.typeId(); }

    /** The display name. */
    public String name() { return raw.name(); }

    /** The pre-computed name hash. */
    public int nameHash() { return raw.nameHash(); }

    // ========================== Position ==========================

    /** Tile X coordinate. */
    public int tileX() { return raw.tileX(); }

    /** Tile Y coordinate. */
    public int tileY() { return raw.tileY(); }

    /** Plane / height level (0-3). */
    public int plane() { return raw.tileZ(); }

    /**
     * Returns the current tile position, freshly fetched from the client.
     */
    public EntityPosition getPosition() {
        return api.getEntityPosition(raw.handle());
    }

    /**
     * Converts this entity's tile to screen coordinates.
     */
    public ScreenPosition toScreen() {
        return api.getWorldToScreen(raw.tileX(), raw.tileY());
    }

    /**
     * Chebyshev (diagonal) tile distance to another tile.
     */
    public int distanceTo(int tileX, int tileY) {
        return Math.max(Math.abs(raw.tileX() - tileX), Math.abs(raw.tileY() - tileY));
    }

    /** Distance to another entity. */
    public int distanceTo(EntityContext other) {
        return distanceTo(other.tileX(), other.tileY());
    }

    /** Distance to the local player. */
    public int distanceToPlayer() {
        LocalPlayer lp = api.getLocalPlayer();
        return distanceTo(lp.tileX(), lp.tileY());
    }

    // ========================== State ==========================

    /** Whether the entity is currently moving. */
    public boolean isMoving() { return raw.isMoving(); }

    /** Whether the entity is hidden / invisible. */
    public boolean isHidden() { return raw.isHidden(); }

    /** Whether this handle still refers to a valid in-game entity. */
    public boolean isValid() { return api.isEntityValid(raw.handle()); }

    // ========================== Extended Info (lazy) ==========================

    /**
     * Returns extended entity info, fetching it lazily on first call.
     * Call {@link #refresh()} to re-fetch.
     */
    public EntityInfo getInfo() {
        if (cachedInfo == null) {
            cachedInfo = api.getEntityInfo(raw.handle());
        }
        return cachedInfo;
    }

    /** Clears cached info so the next access re-fetches from the client. */
    public void refresh() {
        cachedInfo = null;
    }

    // ========================== Health ==========================

    /** Current health points. */
    public int getHealth() {
        return api.getEntityHealth(raw.handle()).health();
    }

    /** Maximum health points. */
    public int getMaxHealth() {
        return api.getEntityHealth(raw.handle()).maxHealth();
    }

    /** Health as a percentage (0.0 – 1.0). */
    public double getHealthPercent() {
        EntityHealth h = api.getEntityHealth(raw.handle());
        return h.maxHealth() == 0 ? 1.0 : (double) h.health() / h.maxHealth();
    }

    /** Whether this entity is dead (health == 0 and maxHealth > 0). */
    public boolean isDead() {
        EntityHealth h = api.getEntityHealth(raw.handle());
        return h.maxHealth() > 0 && h.health() == 0;
    }

    // ========================== Animation ==========================

    /** Current animation ID, or -1 if idle. */
    public int getAnimation() {
        return api.getEntityAnimation(raw.handle());
    }

    /** Whether the entity is currently playing an animation. */
    public boolean isAnimating() {
        return getAnimation() != -1;
    }

    // ========================== Combat ==========================

    /** Active hitmarks (damage splats) on this entity. */
    public List<Hitmark> getHitmarks() {
        return api.getEntityHitmarks(raw.handle());
    }

    /** Whether this entity is currently being hit (has recent hitmarks). */
    public boolean isUnderAttack() {
        return !getHitmarks().isEmpty();
    }

    /** The combat level (from extended info). */
    public int getCombatLevel() {
        return getInfo().combatLevel();
    }

    // ========================== Following ==========================

    /**
     * Returns the server index of the entity this one is following, or -1.
     */
    public int getFollowingIndex() {
        return getInfo().followingIndex();
    }

    /**
     * Whether this entity is currently following/targeting another entity.
     */
    public boolean isFollowing() {
        return getFollowingIndex() != -1;
    }

    // ========================== Options ==========================

    /**
     * Finds the 1-based index of a right-click option by name (case-insensitive).
     *
     * @param options the list of option strings
     * @param option  the option text to find
     * @return the 1-based index, or -1 if not found
     */
    static int findOptionIndex(List<String> options, String option) {
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i) != null && options.get(i).equalsIgnoreCase(option)) {
                return i + 1;
            }
        }
        return -1;
    }

    /**
     * Checks whether the given options list contains the specified option (case-insensitive).
     *
     * @param options the list of option strings
     * @param option  the option text to check
     * @return {@code true} if a matching option exists
     */
    protected static boolean containsOption(List<String> options, String option) {
        return options.stream().anyMatch(o -> o != null && o.equalsIgnoreCase(option));
    }

    // ========================== Overhead ==========================

    /** Overhead text displayed on the entity, or null. */
    public String getOverheadText() {
        return api.getEntityOverheadText(raw.handle());
    }

    // ========================== Interaction ==========================

    /**
     * Queues a raw game action targeting this entity.
     *
     * @param actionId the action type ID
     * @param param1   first parameter (typically option index)
     */
    public void interact(int actionId, int param1) {
        api.queueAction(new GameAction(actionId, param1, raw.handle(), 0));
    }

    // ========================== Object ==========================

    @Override
    public String toString() {
        return name() + " (id=" + typeId() + " @" + tileX() + "," + tileY() + "," + plane() + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EntityContext that)) return false;
        return raw.handle() == that.raw.handle();
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(raw.handle());
    }
}
