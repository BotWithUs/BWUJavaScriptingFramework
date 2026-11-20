package com.botwithus.bot.api.combat;

import com.botwithus.bot.api.snapshot.Npc;
import com.botwithus.bot.api.snapshot.Player;

/** What the local player is targeting. Dispatch with a {@code switch}; the variants are closed. */
public sealed interface CombatTarget {

    /** No target. */
    record None() implements CombatTarget {}

    /** An NPC, as the snapshot row read on the same tick. */
    record NpcTarget(Npc npc) implements CombatTarget {}

    /** Another player, as the snapshot row read on the same tick. */
    record PlayerTarget(Player player) implements CombatTarget {}

    /**
     * A target the snapshot names but could not resolve: its row is not loaded, or its type
     * code is one this host does not know. The raw values are kept for logging.
     */
    record Unresolved(int targetIndex, int targetType) implements CombatTarget {}

    /** The shared {@link None} instance. */
    CombatTarget NONE = new None();
}
