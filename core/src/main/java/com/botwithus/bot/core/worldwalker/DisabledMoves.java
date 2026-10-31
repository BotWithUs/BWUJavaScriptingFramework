package com.botwithus.bot.core.worldwalker;

import com.botwithus.bot.api.model.WorldPathConfig;
import com.botwithus.bot.api.model.WorldPathConfig.MovementOptions;
import com.botwithus.bot.api.snapshot.LocalPlayer;

/**
 * The {@code disabledMoves} mask a walk or query hands the planner, mirroring the
 * argument of {@code ww_executor_run_ex} and {@code ww_query_moves}.
 *
 * <p>The mask carries two kinds of bit. The low bits are the library's movement
 * categories ({@code WW_MOVE_*}): a set bit keeps the planner off every transition
 * of that category, on the first plan and every re-plan. They come from a script's
 * {@link WorldPathConfig} through {@link #forConfig}. The top bit is not a category
 * but the library's free-to-play restriction ({@code WW_RESTRICT_FREE_TO_PLAY}),
 * set by the host through {@link #forWalk}: with it set the planner plans as a
 * free-to-play account, refusing members transitions and members land. The two are
 * independent and combine with {@link #with}.</p>
 *
 * <p>A clear mask is exactly the behaviour of the plain entry points, which is why
 * {@link #NONE} is sent through them rather than through the extended ones.</p>
 *
 * <p>Two library versions cannot honour a set bit, and neither says so: a
 * {@code worldwalker.dll} with no extended entry points (the host then walks
 * unrestricted and logs a warning once, see {@link MovesEntry}), and one that has
 * the entry points but predates a bit, which ignores it.</p>
 *
 * <p>{@link MovementOptions#allowSurge()} and {@link MovementOptions#allowDive()}
 * have no bit: they gate abilities the host fires on walk hops, not transitions
 * the planner picks, so the walker's callback bridge applies them itself.</p>
 *
 * @param mask the raw mask, bit for bit as the C ABI takes it
 */
public record DisabledMoves(int mask) {

    /** {@code (1u << WW_MOVE_DOORS)}: doors and gates. */
    public static final int DOORS_BIT = 1;
    /** {@code (1u << WW_MOVE_SHORTCUTS)}: agility shortcuts and obstacles. */
    public static final int SHORTCUTS_BIT = 1 << 1;
    /** {@code (1u << WW_MOVE_PLANE)}: stairs, ladders, trapdoors, into dungeons. */
    public static final int PLANE_BIT = 1 << 2;
    /** {@code (1u << WW_MOVE_CLIMBOVERS)}: stiles and walls climbed or stepped over. */
    public static final int CLIMBOVERS_BIT = 1 << 3;
    /** {@code (1u << WW_MOVE_TRANSPORTS)}: boats, carts, portals, other loc / NPC links. */
    public static final int TRANSPORTS_BIT = 1 << 4;
    /** {@code (1u << WW_MOVE_TELEPORTS)}: spell and item teleports. */
    public static final int TELEPORTS_BIT = 1 << 5;
    /** {@code (1u << WW_MOVE_LODESTONES)}. */
    public static final int LODESTONES_BIT = 1 << 6;
    /** {@code (1u << WW_MOVE_FAIRY_RINGS)}. */
    public static final int FAIRY_RINGS_BIT = 1 << 7;
    /** {@code (1u << WW_MOVE_SPIRIT_TREES)}. */
    public static final int SPIRIT_TREES_BIT = 1 << 8;
    /** {@code (1u << WW_MOVE_GLIDERS)}. */
    public static final int GLIDERS_BIT = 1 << 9;
    /** {@code (1u << WW_MOVE_CHARTERS)}: charter ships. */
    public static final int CHARTERS_BIT = 1 << 10;
    /** {@code (1u << WW_MOVE_MAGIC_CARPETS)}. */
    public static final int MAGIC_CARPETS_BIT = 1 << 11;
    /** {@code (1u << WW_MOVE_OTHER_CHAINS)}: any other teleport-chain network. */
    public static final int OTHER_CHAINS_BIT = 1 << 12;

    /** {@code WW_RESTRICT_FREE_TO_PLAY}, {@code (1u << 31)} in the C header. */
    public static final int RESTRICT_FREE_TO_PLAY_BIT = 1 << 31;

    /** Nothing disabled: today's routing. */
    public static final DisabledMoves NONE = new DisabledMoves(0);

    /** Plan as a free-to-play account. */
    public static final DisabledMoves FREE_TO_PLAY = new DisabledMoves(RESTRICT_FREE_TO_PLAY_BIT);

    /**
     * The free-to-play part of the mask for a walk starting now.
     *
     * <p>Restricts only when the host setting is on <em>and</em> the snapshot says
     * the account is not a member. With no local player to read, the membership is
     * unknown and the walk is planned unrestricted, which is what it was before the
     * setting existed; a walk cannot start without a position anyway.</p>
     *
     * @param isRestrictionOn the host's free-to-play routing setting
     * @param self            the local player at walk start, or {@code null}
     */
    public static DisabledMoves forWalk(boolean isRestrictionOn, LocalPlayer self) {
        if (!isRestrictionOn || self == null || self.isMember()) {
            return NONE;
        }
        return FREE_TO_PLAY;
    }

    /**
     * The movement-category part of the mask for {@code config}: one bit per
     * {@code allow*} flag that is off. {@code null} means
     * {@link WorldPathConfig#DEFAULT}, which disables nothing. Never sets the
     * free-to-play bit.
     */
    public static DisabledMoves forConfig(WorldPathConfig config) {
        if (config == null) {
            return NONE;
        }
        return new DisabledMoves(ofBasics(config) | ofMovement(config.movement()));
    }

    /** This mask with every bit of {@code other} set as well. */
    public DisabledMoves with(DisabledMoves other) {
        return new DisabledMoves(mask | other.mask);
    }

    /** Whether nothing is disabled, so the plain entry point is the right call. */
    public boolean isNone() {
        return mask == 0;
    }

    /** Whether the free-to-play bit is set. */
    public boolean isFreeToPlay() {
        return (mask & RESTRICT_FREE_TO_PLAY_BIT) != 0;
    }

    /** Whether any movement-category bit is set, i.e. anything but free-to-play. */
    public boolean hasCategories() {
        return (mask & ~RESTRICT_FREE_TO_PLAY_BIT) != 0;
    }

    private static int ofBasics(WorldPathConfig config) {
        return bitUnless(config.allowDoors(), DOORS_BIT)
                | bitUnless(config.allowShortcuts(), SHORTCUTS_BIT)
                | bitUnless(config.allowPlaneTransitions(), PLANE_BIT)
                | bitUnless(config.allowClimbovers(), CLIMBOVERS_BIT)
                | bitUnless(config.allowTransports(), TRANSPORTS_BIT)
                | bitUnless(config.allowTeleports(), TELEPORTS_BIT);
    }

    private static int ofMovement(MovementOptions movement) {
        return bitUnless(movement.allowLodestones(), LODESTONES_BIT)
                | bitUnless(movement.allowFairyRings(), FAIRY_RINGS_BIT)
                | bitUnless(movement.allowSpiritTrees(), SPIRIT_TREES_BIT)
                | bitUnless(movement.allowGliders(), GLIDERS_BIT)
                | bitUnless(movement.allowCharters(), CHARTERS_BIT)
                | bitUnless(movement.allowMagicCarpets(), MAGIC_CARPETS_BIT)
                | bitUnless(movement.allowOtherTeleportChains(), OTHER_CHAINS_BIT);
    }

    private static int bitUnless(boolean isAllowed, int bit) {
        return isAllowed ? 0 : bit;
    }
}
