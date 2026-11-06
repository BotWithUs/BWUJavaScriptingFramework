package com.botwithus.bot.api.model;

import java.util.List;
import java.util.OptionalInt;

/**
 * The live right-click ops of one interface component, or of one slot of it
 * (a backpack slot is a sub of the backpack grid), read from the client by
 * {@link com.botwithus.bot.api.GameAPI#getComponentOps}.
 *
 * <p>{@link #ops()} is the menu as the client would list it now: the agent applies the
 * client's own rule for which ops show, so an op that is here is one a player could
 * choose. For an item in an inventory slot the names follow the item, so the same option
 * can sit on a different op for a different item, and a members item on a free world
 * lists fewer ops than its cache definition.</p>
 *
 * @param mask the component's op-flags word. Bit {@code n} set means op {@code n} reaches
 *             the server; the word also carries flags that are not ops, so only bits
 *             {@code 1..MAX_OP} are read as ops
 * @param ops  every listed op, in op order
 */
public record ComponentOps(int mask, List<ComponentOp> ops) {

    /** Highest op number a component carries. */
    public static final int MAX_OP = 10;

    /** No ops: the component did not resolve, or the agent could not answer. */
    public static final ComponentOps NONE = new ComponentOps(0, List.of());

    public ComponentOps {
        ops = List.copyOf(ops);
    }

    /**
     * Whether the mask lets op {@code op} reach the server. False outside
     * {@code 1..MAX_OP}. A listed op with its bit clear still runs client-side script.
     */
    public boolean isTransmitting(int op) {
        if (op < 1 || op > MAX_OP) {
            return false;
        }
        return (mask & (1 << op)) != 0;
    }

    /**
     * The op number for an option name, matched case-insensitively among the listed ops.
     * Empty when no listed op has that name.
     */
    public OptionalInt opNamed(String name) {
        if (name == null) {
            return OptionalInt.empty();
        }
        for (ComponentOp candidate : ops) {
            if (name.equalsIgnoreCase(candidate.name())) {
                return OptionalInt.of(candidate.op());
            }
        }
        return OptionalInt.empty();
    }
}
