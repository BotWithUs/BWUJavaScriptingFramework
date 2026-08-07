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
    public int typeId()  { return raw.typeId(); }

    /**
     * Display name. Prefers the pre-resolved {@code raw.name} from the RPC
     * (cheap, no defn fetch) and falls back to the LocationType when the
     * producer didn't fill it in.
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

    // ---------------- Convenience shims (kept for scripts that pre-date the rewrite) ----------------

    /** The snapshot only carries visible objects; this stub always returns {@code false}. */
    public boolean isHidden() { return false; }
    /** Chebyshev distance to the local player tile, or {@code MAX_VALUE} if the player isn't loaded. */
    public int distanceToPlayer() {
        var lp = api.getLocalPlayer();
        return lp == null ? Integer.MAX_VALUE : distanceTo(lp.tileX(), lp.tileY());
    }
    /**
     * The producer now resolves morphvarp transforms server-side before
     * publishing the snapshot, so the script-side transform-resolution call is
     * a no-op identity. Kept so pre-rewrite scripts compile unchanged.
     */
    public SceneObject resolveTransform() { return this; }
    /**
     * Two-arg variant kept for pre-rewrite scripts. The second {@code _ignored}
     * parameter (sub-option) was dropped — the option index encodes everything
     * the action queue needs. Delegates to {@link #interact(int)}.
     */
    public void interact(int optionIndex, int _ignored) { interact(optionIndex); }

    /** Cached LocationType for this object's typeId. {@code null} if lookup fails. */
    public LocationType getType() {
        if (cachedType == null) {
            cachedType = typeLookup.apply(typeId());
        }
        return cachedType;
    }

    /**
     * Right-click options. Prefers the pre-resolved {@code raw.options} from
     * the RPC (already covers transform-resolution server-side); falls back
     * to {@link LocationType#options()} when raw came back empty.
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
                + " @" + tileX() + "," + tileY() + "," + plane() + "}";
    }
}
