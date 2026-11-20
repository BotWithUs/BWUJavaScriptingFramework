package com.botwithus.bot.api.combat;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.gameval.GamevalRef;
import com.botwithus.bot.api.gameval.GamevalType;
import com.botwithus.bot.api.model.EntityKind;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.api.snapshot.Player;

import java.util.Optional;

/**
 * The local player's combat state: adrenaline, life, prayer and summoning points, the
 * current target, whether it is in combat, and its name.
 *
 * <p>Each read is one varbit/varp read or one snapshot read; none is cached.</p>
 */
public final class LocalCombat {

    /**
     * {@link LocalPlayer#targetType()} of an NPC target; {@code targetIndex} is then the NPC's
     * server index. Verified live (951-4): attacking a duck read {@code 5912 / 1}.
     */
    public static final int TARGET_TYPE_NPC = 1;
    /** {@link LocalPlayer#targetType()} of a player target. Inferred, not yet seen live. */
    public static final int TARGET_TYPE_PLAYER = 2;
    /** {@link LocalPlayer#targetType()} once a target is dropped (seen live after a kill). */
    public static final int TARGET_TYPE_CLEARED = 0;
    /** {@link LocalPlayer#targetType()} before anything was targeted this session (seen live). */
    public static final int TARGET_TYPE_NEVER_SET = 127;
    /** {@link LocalPlayer#targetIndex()} when the producer publishes no index. */
    public static final int NO_TARGET_INDEX = -1;
    /** How recent a hitmark on the local player must be to count as combat, in ms. */
    public static final long RECENT_HIT_MS = 6_000L;

    private static final int IN_COMBAT = 1;
    /** {@code LORE_POINTS} stores tenths of a point. */
    private static final int TENTHS = 10;

    private static final GamevalRef ADRENALINE = new GamevalRef(GamevalType.VARP, "SA_ENERGY", 679);
    private static final GamevalRef IN_COMBAT_FLAG =
            new GamevalRef(GamevalType.VARBIT, "COMBATV2_PLAYER_IS_IN_COMBAT", 1899);
    private static final GamevalRef SUMMONING_POINTS =
            new GamevalRef(GamevalType.VARBIT, "LORE_POINTS", 41524);
    private static final GamevalRef LIFE_POINTS =
            new GamevalRef(GamevalType.VARP, "LIFEPOINTS", 13537);
    private static final GamevalRef MAX_LIFE_POINTS =
            new GamevalRef(GamevalType.VARP, "MAXLIFEPOINTS", 13538);

    private final GameAPI api;
    private final Prayers prayers;

    public LocalCombat(GameAPI api) {
        this(api, new Prayers(api));
    }

    public LocalCombat(GameAPI api, Prayers prayers) {
        this.api = api;
        this.prayers = prayers;
    }

    /** Adrenaline as the game stores it ({@code SA_ENERGY}); see the PR notes for its scale. */
    public int adrenaline() {
        return api.getVarp(ADRENALINE.resolve(api.gamevals()));
    }

    /** Current life points: the snapshot's value, or the {@code LIFEPOINTS} varp. */
    public int lifePoints() {
        LocalPlayer self = self().orElse(null);
        if (self != null && self.hasHealth()) {
            return self.currentHealth();
        }
        return api.getVarp(LIFE_POINTS.resolve(api.gamevals()));
    }

    /** Maximum life points: the snapshot's value, or the {@code MAXLIFEPOINTS} varp. */
    public int maxLifePoints() {
        LocalPlayer self = self().orElse(null);
        if (self != null && self.hasHealth()) {
            return self.maxHealth();
        }
        return api.getVarp(MAX_LIFE_POINTS.resolve(api.gamevals()));
    }

    /** Current prayer points; the same value as {@link Prayers#points()}. */
    public int prayerPoints() {
        return prayers.points();
    }

    /** Current summoning points as the orb shows them; see {@code Summoning.points()}. */
    public int summoningPoints() {
        int raw = api.getVarbit(SUMMONING_POINTS.resolve(api.gamevals()));
        return raw < 0 ? -1 : raw / TENTHS;
    }

    /** The local player's display name, if its player row is loaded. */
    public Optional<String> name() {
        GameSnapshot snapshot = api.snapshot();
        if (snapshot == null) {
            return Optional.empty();
        }
        return snapshot.players().byServerIndex(snapshot.ownIndex())
                .filter(Player::hasName)
                .map(Player::name);
    }

    /** What the local player is targeting, resolved against the same snapshot. */
    public CombatTarget target() {
        GameSnapshot snapshot = api.snapshot();
        if (snapshot == null || snapshot.self() == null) {
            return CombatTarget.NONE;
        }
        return resolveTarget(snapshot, snapshot.self());
    }

    /**
     * Whether the local player is in combat. A heuristic, true when any of these holds:
     * <ol>
     *   <li>the game's own flag {@code COMBATV2_PLAYER_IS_IN_COMBAT} is set;</li>
     *   <li>the local player has a target;</li>
     *   <li>{@code hitmarks} (when not {@code null}) holds a hitmark on the local player from
     *       the last {@value #RECENT_HIT_MS} ms.</li>
     * </ol>
     * The flag is the game's answer; the other two cover the moments around it (a target
     * picked but no swing landed, being hit while not retaliating).
     */
    public boolean isInCombat(HitmarkHistory hitmarks) {
        if (api.getVarbit(IN_COMBAT_FLAG.resolve(api.gamevals())) == IN_COMBAT) {
            return true;
        }
        GameSnapshot snapshot = api.snapshot();
        if (snapshot == null || snapshot.self() == null) {
            return false;
        }
        if (hasTarget(snapshot.self())) {
            return true;
        }
        return hitmarks != null && !hitmarks.recent(EntityKind.PLAYER,
                snapshot.self().serverIndex(), RECENT_HIT_MS).isEmpty();
    }

    /** {@link #isInCombat(HitmarkHistory)} without the hitmark signal. */
    public boolean isInCombat() {
        return isInCombat(null);
    }

    static CombatTarget resolveTarget(GameSnapshot snapshot, LocalPlayer self) {
        if (!hasTarget(self)) {
            return CombatTarget.NONE;
        }
        int index = self.targetIndex();
        return switch (self.targetType()) {
            case TARGET_TYPE_NPC -> snapshot.npcs().byServerIndex(index)
                    .<CombatTarget>map(CombatTarget.NpcTarget::new)
                    .orElse(new CombatTarget.Unresolved(index, TARGET_TYPE_NPC));
            case TARGET_TYPE_PLAYER -> snapshot.players().byServerIndex(index)
                    .<CombatTarget>map(CombatTarget.PlayerTarget::new)
                    .orElse(new CombatTarget.Unresolved(index, TARGET_TYPE_PLAYER));
            default -> new CombatTarget.Unresolved(index, self.targetType());
        };
    }

    private static boolean hasTarget(LocalPlayer self) {
        int type = self.targetType();
        return self.targetIndex() != NO_TARGET_INDEX && type != TARGET_TYPE_CLEARED
                && type != TARGET_TYPE_NEVER_SET;
    }

    private Optional<LocalPlayer> self() {
        GameSnapshot snapshot = api.snapshot();
        return snapshot == null ? Optional.empty() : Optional.ofNullable(snapshot.self());
    }
}
