package com.botwithus.bot.api.entities;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.snapshot.GameSnapshot;

import java.util.List;
import java.util.stream.Stream;

/**
 * In-flight projectile query facade (v17+). Singleton per {@link GameAPI};
 * obtain via {@code api.projectiles()}.
 *
 * <p>Projectiles carry no per-id type definition — the API has no
 * {@code SpotAnimType} and {@code NXTCacheLibrary} ships no spot-anim decoder —
 * so unlike {@link Npcs} / {@link SceneObjects} / {@link GroundItems} this
 * facade holds no definition cache and {@link EntityQuery#named(String)} never
 * matches. Filter on {@link EntityQuery#withId(int)} (the graphic id),
 * position, or a predicate.</p>
 *
 * <p>Queries are anchored on the projectile's <em>impact</em> tile — see the
 * position note on {@link Projectile}.</p>
 */
public final class Projectiles {

    private final GameAPI api;

    public Projectiles(GameAPI api) {
        this.api = api;
    }

    /** Start a fluent query. */
    public Query query() {
        return new Query(api);
    }

    /** Every in-flight projectile this tick. */
    public List<Projectile> all() {
        return query().all();
    }

    /**
     * Projectiles aimed at the local player. See
     * {@link Projectile#targetsLocalPlayer()} for the index-space caveat.
     */
    public List<Projectile> incoming() {
        return query().filter(Projectile::targetsLocalPlayer).all();
    }

    /**
     * The incoming projectile landing nearest the local player, or {@code null}
     * when nothing is inbound.
     */
    public Projectile nearestIncoming() {
        return query().filter(Projectile::targetsLocalPlayer).nearest();
    }

    /** Query subclass — wires the snapshot stream to {@link EntityQuery}. */
    public static final class Query extends EntityQuery<Projectile, Query> {

        Query(GameAPI api) {
            super(api);
        }

        @Override
        protected Stream<Projectile> source() {
            GameSnapshot snap = api.snapshot();
            if (snap == null) {
                return Stream.empty();
            }
            return snap.projectiles().stream().map(raw -> new Projectile(api, raw));
        }

        @Override
        protected int rawTypeId(Projectile t) {
            // The graphic (spot-anim) id is a real, meaningful type id here —
            // unlike Players, withId(N) is a useful filter on projectiles.
            return t.projectileId();
        }

        @Override
        protected String nameOf(Projectile t) {
            // No definition source exists for spot-anim ids (no SpotAnimType in
            // api/model, no decoder in NXTCacheLibrary), so named()/nameMatching()
            // will never match. Returning null is checked by EntityQuery's filters.
            return null;
        }
    }
}
