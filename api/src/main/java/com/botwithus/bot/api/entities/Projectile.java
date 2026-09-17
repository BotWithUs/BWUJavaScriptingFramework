package com.botwithus.bot.api.entities;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.snapshot.GameSnapshot;

/**
 * Rich wrapper around a snapshot {@link com.botwithus.bot.api.snapshot.Projectile}
 * record — one in-flight projectile at the producer's current tick (v17+).
 *
 * <p>Obtained via {@link Projectiles#query()}.</p>
 *
 * <p><b>This is a read-only entity.</b> Unlike {@link Npc}, {@link SceneObject}
 * and {@link GroundItem}, a projectile is not clickable — there is no menu
 * action against a graphic in flight, and correspondingly no {@code ActionTypes}
 * entry for one. The absence of {@code interact()} here is deliberate, not an
 * oversight.</p>
 *
 * <p><b>Position semantics.</b> A projectile has two positions: where it was
 * launched and where it lands. {@link EntityContext} exposes exactly one, and
 * this wrapper anchors it on the <em>target</em> (end) tile — so
 * {@code nearest()} and {@code withinDistance()} answer "what is about to land
 * near me", which is the question combat scripts actually ask. The launch point
 * remains available as {@link #startTileX()} / {@link #startTileY()}.</p>
 *
 * <p>Name collision note: the snapshot type
 * {@code com.botwithus.bot.api.snapshot.Projectile} shares the simple name
 * {@code Projectile} with this wrapper, so the snapshot type is fully qualified
 * at the field declaration below and reflected mechanically on the constructor
 * parameter and {@link #raw()} accessor (Java has no import aliasing, and the
 * enclosing class shadows any import of the snapshot type).</p>
 */
public final class Projectile implements EntityContext {

    private final GameAPI api;
    // FQN: disambiguates from this-class entity Projectile — see class-level note.
    private final com.botwithus.bot.api.snapshot.Projectile raw;

    Projectile(GameAPI api, com.botwithus.bot.api.snapshot.Projectile raw) {
        this.api = api;
        this.raw = raw;
    }

    /** The underlying snapshot record. */
    public com.botwithus.bot.api.snapshot.Projectile raw() { return raw; }

    // ---------------- Identity ----------------

    /**
     * Graphic (spot-anim) id of the projectile.
     *
     * <p>There is no name for this id: the API has no {@code SpotAnimType} and
     * {@code NXTCacheLibrary} carries no spot-anim decoder, so
     * {@link EntityQuery#named(String)} never matches on projectiles. Filter
     * with {@link EntityQuery#withId(int)} instead.</p>
     */
    public int projectileId() { return raw.projectileId(); }

    // ---------------- Flight ----------------

    /** Game cycle the projectile was launched. */
    public int startCycle() { return raw.startCycle(); }

    /** Game cycle it lands. */
    public int endCycle() { return raw.endCycle(); }

    /**
     * Flight progress, {@code 0.0} at launch through {@code 1.0} at impact,
     * clamped to that range.
     *
     * <p>The cycle is a <b>parameter</b> by design. The snapshot does not carry
     * the live game cycle, so the only source is {@link GameAPI#getGameCycle()}
     * — an RPC round-trip. Hiding that inside a no-arg {@code progress()} would
     * put a pipe call in the middle of what reads like a cheap field access, and
     * a script iterating projectiles would pay it once per element. Fetch the
     * cycle once per tick and pass it in.</p>
     *
     * <p>Returns {@code 1.0} for a degenerate zero-length flight.</p>
     */
    public double flightProgress(int currentCycle) {
        int span = endCycle() - startCycle();
        if (span <= 0) {
            return 1.0d;
        }
        double t = (double) (currentCycle - startCycle()) / span;
        return Math.max(0.0d, Math.min(1.0d, t));
    }

    /**
     * Cycles remaining until impact, floored at {@code 0}. Same cycle-as-a-
     * parameter rationale as {@link #flightProgress(int)}.
     */
    public int cyclesRemaining(int currentCycle) {
        return Math.max(0, endCycle() - currentCycle);
    }

    // ---------------- Endpoints ----------------

    /**
     * Server index of the source entity, or {@code -1} when the projectile
     * originates from a fixed tile rather than an entity.
     */
    public int sourceIndex() { return raw.sourceIndex(); }

    /**
     * Server index of the target entity, or {@code -1} when the projectile is
     * aimed at a fixed tile.
     */
    public int targetIndex() { return raw.targetIndex(); }

    /**
     * Raw engine entity-type tag for the source endpoint, surfaced unmodified.
     *
     * <p>The concrete values are not decoded yet — the producer passes the
     * engine's tag straight through. Treat it as an opaque discriminator to
     * compare between projectiles, not as a value to switch on.</p>
     */
    public int sourceType() { return raw.sourceType(); }

    /** Raw engine entity-type tag for the target endpoint. See {@link #sourceType()}. */
    public int targetType() { return raw.targetType(); }

    /** {@code true} when the source end is a fixed tile rather than an entity. */
    public boolean isSourceTile() { return raw.sourceIndex() < 0; }

    /** {@code true} when the target end is a fixed tile rather than an entity. */
    public boolean isTargetTile() { return raw.targetIndex() < 0; }

    /**
     * Resolve the source endpoint to a live NPC, or {@code null} if no NPC in
     * this tick's snapshot carries that server index.
     *
     * <p><b>Caveat:</b> NPC and player server indices are separate index spaces,
     * so an index alone cannot tell you which kind of entity the endpoint is —
     * that is what {@link #sourceType()} is for, and its values are not decoded
     * yet. Until they are, a non-null result here does not by itself prove the
     * source was an NPC; it may be a same-numbered NPC coinciding with a player
     * endpoint. Cross-check {@link #sourceType()} against a projectile whose
     * origin you already know.</p>
     */
    public Npc sourceNpc() {
        return npcByIndex(sourceIndex());
    }

    /** Resolve the target endpoint to a live NPC. Same caveat as {@link #sourceNpc()}. */
    public Npc targetNpc() {
        return npcByIndex(targetIndex());
    }

    /** Resolve the source endpoint to a live player. Same caveat as {@link #sourceNpc()}. */
    public Player sourcePlayer() {
        return playerByIndex(sourceIndex());
    }

    /** Resolve the target endpoint to a live player. Same caveat as {@link #sourceNpc()}. */
    public Player targetPlayer() {
        return playerByIndex(targetIndex());
    }

    /**
     * {@code true} when this projectile is aimed at the local player — the
     * "is this incoming at me" predicate.
     *
     * <p>Carries the same index-space caveat as {@link #sourceNpc()}: it
     * compares {@link #targetIndex()} against the snapshot's {@code ownIndex},
     * which is a <em>player</em> index, so an NPC target that happens to share
     * the local player's index would read as a false positive. This becomes
     * exact once {@link #targetType()}'s values are decoded.</p>
     */
    public boolean targetsLocalPlayer() {
        if (isTargetTile()) {
            return false;
        }
        GameSnapshot snap = api.snapshot();
        if (snap == null) {
            return false;
        }
        int own = snap.ownIndex();
        return own >= 0 && targetIndex() == own;
    }

    // ---------------- Position ----------------

    /** Absolute world tile X of the launch point. */
    public int startTileX() { return raw.startTileX(); }

    /** Absolute world tile Y of the launch point. */
    public int startTileY() { return raw.startTileY(); }

    /** Absolute world tile X of the impact point. Same as {@link #tileX()}. */
    public int endTileX() { return raw.endTileX(); }

    /** Absolute world tile Y of the impact point. Same as {@link #tileY()}. */
    public int endTileY() { return raw.endTileY(); }

    /** Impact tile X — see the position-semantics note on the class. */
    @Override public int tileX() { return raw.endTileX(); }

    /** Impact tile Y — see the position-semantics note on the class. */
    @Override public int tileY() { return raw.endTileY(); }

    @Override public int plane() { return raw.plane(); }

    @Override
    public String toString() {
        return "Projectile{gfx=" + projectileId()
                + " src=" + sourceIndex()
                + " dst=" + targetIndex()
                + " " + startTileX() + "," + startTileY()
                + "->" + endTileX() + "," + endTileY()
                + "," + plane()
                + " cycles=" + startCycle() + ".." + endCycle() + "}";
    }

    private Npc npcByIndex(int serverIndex) {
        if (serverIndex < 0) {
            return null;
        }
        return api.npcs().query().filter(n -> n.serverIndex() == serverIndex).first();
    }

    private Player playerByIndex(int serverIndex) {
        if (serverIndex < 0) {
            return null;
        }
        return api.players().byServerIndex(serverIndex);
    }
}
