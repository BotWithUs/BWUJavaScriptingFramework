package com.botwithus.bot.api.entities;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.model.LocationType;
import com.botwithus.bot.api.model.SceneObjectInfo;

import java.util.List;
import java.util.function.IntFunction;

/**
 * Rich wrapper around {@link SceneObjectInfo} with lazy {@link LocationType}
 * resolution and option-keyed {@link #interact(String) interact}.
 *
 * <p>Obtained through {@link SceneObjects#query()} — don't construct
 * directly. The facade owns the LocationType cache that backs
 * {@link #getType()} repeats.</p>
 */
public final class SceneObject implements EntityContext {

    private final GameAPI api;
    private final SceneObjectInfo raw;
    private final IntFunction<LocationType> typeLookup;
    private LocationType cachedType;

    SceneObject(GameAPI api, SceneObjectInfo raw, IntFunction<LocationType> typeLookup) {
        this.api = api;
        this.raw = raw;
        this.typeLookup = typeLookup;
    }

    public SceneObjectInfo raw() { return raw; }

    public int handle()  { return raw.handle(); }

    /**
     * The loc id the server sent. This is what identity and hardcoded id sets are keyed on,
     * and what an interaction is addressed to — it is deliberately <em>not</em> the id whose
     * definition carries the name, which is {@link #resolvedId()}.
     */
    public int typeId()  { return raw.typeId(); }

    /**
     * The loc id whose definition carries this object's name and right-click options, with the
     * morphvarp ("multiloc") transform already applied by the producer.
     *
     * <p>Equal to {@link #typeId()} for a loc that does not morph, so it is always usable. For
     * one that does — a Range, a bonfire, a bank chest, a construction hotspot, most instanced
     * scenery — the server publishes a base id whose definition has an empty name and no
     * options, and only this id resolves to the real thing.</p>
     */
    public int resolvedId() { return raw.resolvedId(); }

    /**
     * Display name. Prefers the pre-resolved {@code raw.name} from the RPC
     * (cheap, no defn fetch) and falls back to the LocationType when the
     * producer didn't fill it in — which, since that LocationType is looked up on
     * {@link #resolvedId()}, is the real name even for a morph loc.
     */
    public String name() {
        if (raw.name() != null && !raw.name().isEmpty()) {
            return raw.name();
        }
        LocationType t = getType();
        return t == null ? null : t.name();
    }

    @Override public int tileX() { return raw.tileX(); }
    @Override public int tileY() { return raw.tileY(); }
    @Override public int plane() { return raw.plane(); }

    /**
     * How far this loc's model is turned, in quarter turns {@code 0..3}, exactly as the
     * producer published it.
     *
     * <p>This is <em>not</em> a compass heading. It turns the loc away from its model's own
     * default pose, and that pose is chosen per model, so the value is only meaningful when
     * compared against another rotation of the same loc, or read together with
     * {@link #shape()} to work out which tile edge a wall occupies.</p>
     *
     * <p>Inside an instance the chunk the tile was copied from may itself be rotated, by
     * {@link com.botwithus.bot.api.snapshot.SourceTile#rotation()}; anything directional read
     * from the source region has to account for both.</p>
     *
     * @return {@code 0..3}, or {@link SceneObjectInfo#UNKNOWN_ROTATION} for a row built
     *         without one
     */
    public int rotation() { return raw.rotation(); }

    /**
     * The scenery shape code as published on the wire, which says what kind of placement
     * this loc is: a wall, a wall decoration, a ground decoration, a centrepiece and so on.
     *
     * @return the shape code, or {@link SceneObjectInfo#UNKNOWN_SHAPE} for a row built
     *         without one
     */
    public int shape() { return raw.shape(); }

    // ---------------- Convenience shims (kept for scripts that pre-date the rewrite) ----------------

    /** The snapshot only carries visible objects; this stub always returns {@code false}. */
    public boolean isHidden() { return false; }
    /** Chebyshev distance to the local player tile, or {@code MAX_VALUE} if the player isn't loaded. */
    public int distanceToPlayer() {
        var lp = api.getLocalPlayer();
        return lp == null ? Integer.MAX_VALUE : distanceTo(lp.tileX(), lp.tileY());
    }
    /**
     * The producer resolves morphvarp transforms before publishing the snapshot and the
     * result rides on the row as {@link #resolvedId()}, so this is a no-op identity. Kept so
     * pre-rewrite scripts compile unchanged.
     *
     * <p>That claim was aspirational until wire v20 and is now true: {@link #getType()},
     * {@link #name()} and {@link #getOptions()} all resolve through the morph-resolved id.</p>
     */
    public SceneObject resolveTransform() { return this; }
    /**
     * Two-arg variant kept for pre-rewrite scripts. {@code unusedSubOption} is
     * ignored — the option index encodes everything the action queue needs.
     * Delegates to {@link #interact(int)}.
     */
    public void interact(int optionIndex, int unusedSubOption) { interact(optionIndex); }

    /**
     * Cached LocationType for this object. Resolved off {@link #resolvedId()}, never
     * {@link #typeId()}: a morph loc's base definition has an empty name and no options, so
     * looking up the base id is what made Ranges, bonfires, bank chests and construction
     * hotspots invisible to every name- or option-based query. {@code null} if lookup fails.
     */
    public LocationType getType() {
        if (cachedType == null) {
            cachedType = typeLookup.apply(resolvedId());
        }
        return cachedType;
    }

    /**
     * Right-click options. Prefers the pre-resolved {@code raw.options} from
     * the RPC; falls back to {@link LocationType#options()} when raw came back empty — and
     * that LocationType is the morph-resolved one, so the options are the ones the player
     * actually sees.
     */
    public List<String> getOptions() {
        if (!raw.options().isEmpty()) {
            return raw.options();
        }
        LocationType t = getType();
        return t == null ? List.of() : t.options();
    }

    /** True if any option matches {@code option} case-insensitively. */
    public boolean hasOption(String option) {
        for (String o : getOptions()) {
            if (o != null && o.equalsIgnoreCase(option)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Queue an action by 1-based right-click option index.
     *
     * @throws IllegalArgumentException for {@code optionIndex} outside [1, 6]
     */
    public void interact(int optionIndex) {
        if (optionIndex < 1 || optionIndex >= ActionTypes.OBJECT_OPTIONS.length) {
            throw new IllegalArgumentException("Object option index out of range: " + optionIndex);
        }
        api.queueAction(new GameAction(
                ActionTypes.OBJECT_OPTIONS[optionIndex],
                this.typeId(), this.tileX(), this.tileY()));
    }

    /**
     * Queue an action by option text. Returns {@code false} when the option
     * isn't on this object's menu (no action queued).
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
        return "SceneObject{" + name() + " id=" + typeId()
                + " @" + tileX() + "," + tileY() + "," + plane()
                + " shape=" + shape() + " rot=" + rotation() + "}";
    }
}
