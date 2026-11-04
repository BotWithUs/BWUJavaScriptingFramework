package com.botwithus.bot.api.entities;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.EntityKind;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.model.Headbar;
import com.botwithus.bot.api.model.OverheadText;
import com.botwithus.bot.api.snapshot.Orientation;

import java.util.List;
import java.util.Optional;

/**
 * Rich wrapper around a snapshot {@link com.botwithus.bot.api.snapshot.Player}
 * record. Players don't have a per-id type definition the way NPCs do —
 * what would be on {@code NpcType} (name, options, ...) is per-instance and
 * lives in the snapshot or arrives as packets — so this wrapper is thinner
 * than {@link Npc}.
 *
 * <p>Name collision note: the snapshot type {@code com.botwithus.bot.api.snapshot.Player}
 * shares the simple name {@code Player} with this wrapper, so the snapshot
 * type is fully qualified at the field declaration below and reflected
 * mechanically on the constructor parameter and {@link #raw()} accessor
 * (Java has no import aliasing, and the enclosing class shadows any
 * import of the snapshot type).</p>
 */
public final class Player implements EntityContext {

    private final GameAPI api;
    // FQN: disambiguates from this-class entity Player — see class-level note.
    private final com.botwithus.bot.api.snapshot.Player raw;

    Player(GameAPI api, com.botwithus.bot.api.snapshot.Player raw) {
        this.api = api;
        this.raw = raw;
    }

    public com.botwithus.bot.api.snapshot.Player raw() { return raw; }

    public int serverIndex()    { return raw.serverIndex(); }
    /**
     * Display name (wire v24), exactly as the client holds it, never {@code null}. <b>Empty
     * means not known yet</b>: the client fills it in when it decodes the player's appearance,
     * and some players in the world read empty. It can contain U+00A0 where the account name
     * has a space; the name filters on {@link Players.Query} fold that for you.
     */
    public String name()        { return raw.name(); }
    /** True when {@link #name()} is not empty. */
    public boolean hasName()    { return raw.hasName(); }
    /** True when {@link #name()} is a prefix of a name longer than the wire's 15 bytes. */
    public boolean isNameTruncated() { return raw.isNameTruncated(); }
    public int combatLevel()    { return raw.combatLevel(); }
    public int followingIndex() { return raw.followingIndex(); }
    public int animationId()    { return raw.animationId(); }
    public int stanceId()       { return raw.stanceId(); }
    public boolean isMoving()   { return raw.isMoving(); }
    /** First active spot anim (graphic) id playing on this player, or {@code -1} if none.
     *  Only the first concurrent spot anim is surfaced — subscribe to
     *  {@code SpotAnimEvent} for every newly-started one. */
    public int spotAnimId()     { return raw.spotAnimId(); }

    /**
     * Which way this player is facing (wire v21): its rendered facing, interpolated while it turns.
     * Use {@link Orientation#compass()} or {@link Orientation#degrees()} to read it, and
     * {@link Orientation#isSameFacingAs} to compare two, never {@code equals}; the client can
     * read an angle back one unit short. Unknown when the producer could not read one.
     */
    public Orientation orientation() { return raw.orientation(); }

    /** Tile X the server last sent this player towards (wire v25); {@link #tileX()} is where it is drawn. */
    public int serverTileX() { return raw.serverTileX(); }
    /** Tile Y the server last sent this player towards (wire v25). */
    public int serverTileY() { return raw.serverTileY(); }
    /** Plane of the server tile (wire v25). */
    public int serverPlane() { return raw.serverPlane(); }
    /** True when the server tile is a real reading. */
    public boolean hasServerTile() { return raw.hasServerTile(); }

    /**
     * The text above this player right now, or empty when it is silent <b>or the read was
     * refused</b>; use {@link GameAPI#getOverheadText} to tell those apart. One pipe round-trip.
     * A player's text has not been read live yet; the same client field has, on NPCs.
     */
    public Optional<String> getOverheadText() {
        return api.getOverheadText(EntityKind.PLAYER, serverIndex()).value().map(OverheadText::text);
    }

    /**
     * Every headbar entry on this player, or empty when it has none <b>or the read was
     * refused</b>; use {@link GameAPI#getHeadbars} to tell those apart. One pipe round-trip. Not
     * filtered for liveness: an old entry is still listed.
     */
    public List<Headbar> getHeadbars() {
        return api.getHeadbars(EntityKind.PLAYER, serverIndex()).value();
    }

    @Override public int tileX() { return raw.tileX(); }
    @Override public int tileY() { return raw.tileY(); }
    @Override public int plane() { return raw.plane(); }

    // ---------------- Convenience shims (kept for scripts that pre-date the rewrite) ----------------

    /** Chebyshev distance to the local player tile, or {@code MAX_VALUE} if the local player isn't loaded. */
    public int distanceToPlayer() {
        var lp = api.getLocalPlayer();
        return lp == null ? Integer.MAX_VALUE : distanceTo(lp.tileX(), lp.tileY());
    }

    /**
     * Queue an action against this player by 1-based option index.
     *
     * @throws IllegalArgumentException for {@code optionIndex} outside [1, 10]
     */
    public void interact(int optionIndex) {
        if (optionIndex < 1 || optionIndex >= ActionTypes.PLAYER_OPTIONS.length) {
            throw new IllegalArgumentException("Player option index out of range: " + optionIndex);
        }
        api.queueAction(new GameAction(
                ActionTypes.PLAYER_OPTIONS[optionIndex],
                serverIndex(), 0, 0));
    }

    @Override
    public String toString() {
        return "Player{idx=" + serverIndex() + " name=" + name()
                + " @" + tileX() + "," + tileY() + "," + plane() + "}";
    }
}
