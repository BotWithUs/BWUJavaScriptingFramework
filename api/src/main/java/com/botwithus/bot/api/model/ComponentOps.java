package com.botwithus.bot.api.model;

import java.util.List;
import java.util.OptionalInt;

/**
 * The live right-click ops of one interface component, or of one slot of it
 * (a backpack slot is a sub of the backpack grid), read from the client by
 * {@link com.botwithus.bot.api.GameAPI#getComponentOps}.
 *
 * <p>Two facts decide whether an option can be used, and both are here:</p>
 * <ul>
 *   <li>{@link #ops()} names each op. For an item in an inventory slot the names
 *       follow the item, so the same option can sit on a different op for a
 *       different item.</li>
 *   <li>{@link #mask()} is the op mask the client checks before it sends an op: bit
 *       {@code n} enables op {@code n}. An op whose bit is clear is never sent.</li>
 * </ul>
 *
 * @param mask the op mask; bit {@code n} enables op {@code n}
 * @param ops  every named op, in op order
 */
public record ComponentOps(int mask, List<ComponentOp> ops) {

    /** Highest op number a component carries. */
    public static final int MAX_OP = 10;

    /** No ops: the component did not resolve, or the agent could not answer. */
    public static final ComponentOps NONE = new ComponentOps(0, List.of());

    public ComponentOps {
        ops = List.copyOf(ops);
    }

    /** Whether the mask enables op {@code op}. False outside {@code 1..MAX_OP}. */
    public boolean isEnabled(int op) {
        if (op < 1 || op > MAX_OP) {
            return false;
        }
        return (mask & (1 << op)) != 0;
    }

    /**
     * The op number for an option name, matched case-insensitively, among the ops the
     * mask enables. Empty when no enabled op has that name.
     */
    public OptionalInt opNamed(String name) {
        if (name == null) {
            return OptionalInt.empty();
        }
        for (ComponentOp candidate : ops) {
            if (name.equalsIgnoreCase(candidate.name()) && isEnabled(candidate.op())) {
                return OptionalInt.of(candidate.op());
            }
        }
        return OptionalInt.empty();
    }
}
