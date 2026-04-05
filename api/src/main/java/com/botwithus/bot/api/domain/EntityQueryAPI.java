package com.botwithus.bot.api.domain;

import com.botwithus.bot.api.model.*;
import com.botwithus.bot.api.query.EntityFilter;

import java.util.List;

/**
 * Entity querying, ground items, projectiles, spot animations, and hint arrows.
 *
 * @see com.botwithus.bot.api.GameAPI
 */
public interface EntityQueryAPI {

    // ============================== Entity Queries ==============================

    /**
     * Queries entities (NPCs, players, objects) matching the given filter.
     *
     * @param filter the entity filter criteria
     * @return a list of matching entities
     * @see EntityFilter
     */
    List<Entity> queryEntities(EntityFilter filter);

    /**
     * Returns extended information about an entity.
     *
     * @param handle the entity handle
     * @return the entity info, or {@code null} if the handle is invalid
     */
    EntityInfo getEntityInfo(int handle);

    /**
     * Returns the display name of an entity.
     *
     * @param handle the entity handle
     * @return the entity name, or {@code null} if the handle is invalid
     */
    String getEntityName(int handle);

    /**
     * Returns the health state of an entity.
     *
     * @param handle the entity handle
     * @return the entity health data
     */
    EntityHealth getEntityHealth(int handle);

    /**
     * Returns the tile position of an entity.
     *
     * @param handle the entity handle
     * @return the entity position
     */
    EntityPosition getEntityPosition(int handle);

    /**
     * Checks whether an entity handle still refers to a valid in-game entity.
     *
     * @param handle the entity handle
     * @return {@code true} if the entity is still valid
     */
    boolean isEntityValid(int handle);

    /**
     * Returns the active hitmarks (damage splats) on an entity.
     *
     * @param handle the entity handle
     * @return a list of hitmarks
     */
    List<Hitmark> getEntityHitmarks(int handle);

    /**
     * Returns the active headbars (HP bars) on an entity.
     *
     * @param handle the entity handle
     * @return a list of headbars
     */
    List<Headbar> getEntityHeadbars(int handle);

    /**
     * Returns the active spot animation IDs on an entity.
     *
     * @param handle the entity handle
     * @return a list of spot animation type IDs
     */
    List<Integer> getEntitySpotAnims(int handle);

    /**
     * Returns the current animation ID of an entity.
     *
     * @param handle the entity handle
     * @return the animation ID, or {@code -1} if idle
     */
    int getEntityAnimation(int handle);

    /**
     * Returns any overhead text currently displayed on an entity.
     *
     * @param handle the entity handle
     * @return the overhead text, or {@code null} if none
     */
    String getEntityOverheadText(int handle);

    /**
     * Returns the duration of an animation in game ticks.
     *
     * @param animationId the animation ID
     * @return the animation length in ticks
     */
    int getAnimationLength(int animationId);

    // ============================== Ground Items ==============================

    /**
     * Queries ground item stacks matching the given filter.
     *
     * @param filter the entity filter criteria for ground items
     * @return a list of matching ground item stacks
     */
    List<GroundItemStack> queryGroundItems(EntityFilter filter);

    /**
     * Returns the individual items within an object stack.
     *
     * @param handle the object stack handle
     * @return a list of ground items in the stack
     */
    List<GroundItem> getObjStackItems(int handle);

    /**
     * Queries object stack entities matching the given filter.
     *
     * @param filter the entity filter criteria
     * @return a list of matching object stack entities
     */
    List<Entity> queryObjStacks(EntityFilter filter);

    // ============================== Projectiles & Spot Anims ==============================

    /**
     * Queries active projectiles in the game world.
     *
     * @param projectileId the projectile type ID to filter by, or {@code -1} for all
     * @param plane        the plane (height level) to search
     * @param maxResults   maximum number of results to return
     * @return a list of matching projectiles
     */
    List<Projectile> queryProjectiles(int projectileId, int plane, int maxResults);

    /**
     * Queries active spot animations (graphics) in the game world.
     *
     * @param animId     the animation ID to filter by, or {@code -1} for all
     * @param plane      the plane (height level) to search
     * @param maxResults maximum number of results to return
     * @return a list of matching spot animations
     */
    List<SpotAnim> querySpotAnims(int animId, int plane, int maxResults);

    // ============================== Hint Arrows ==============================

    /**
     * Queries active hint arrows displayed on the game screen.
     *
     * @param maxResults maximum number of results to return
     * @return a list of active hint arrows
     */
    List<HintArrow> queryHintArrows(int maxResults);

    // ============================== Query Context ==============================

    /**
     * Computes a name hash from a display name string.
     *
     * @param name the display name
     * @return the computed name hash
     */
    int computeNameHash(String name);

    /**
     * Refreshes the internal query context used for entity lookups.
     * Call this before performing a batch of queries for consistent results.
     */
    void updateQueryContext();

    /**
     * Invalidates the cached query context, forcing a refresh on the next query.
     */
    void invalidateQueryContext();
}
