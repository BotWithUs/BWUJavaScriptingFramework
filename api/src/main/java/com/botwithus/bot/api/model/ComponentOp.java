package com.botwithus.bot.api.model;

/**
 * One right-click op of an interface component, as the client's menu lists it.
 *
 * @param op        the op number the client sends when the option is chosen, {@code 1..10}
 *                  ({@link ComponentOps#MAX_OP}). This is what goes in {@code param1} of a
 *                  {@code COMPONENT} action, and it is <em>not</em> the option's position in
 *                  an item's cache option list.
 * @param name      the option's text, as the right-click menu shows it (for example
 *                  {@code "Drop"})
 * @param transmits whether choosing the op reaches the server. An op that does not still
 *                  shows in the menu and runs the component's client-side script.
 */
public record ComponentOp(int op, String name, boolean transmits) {

    public ComponentOp {
        if (name == null) {
            throw new IllegalArgumentException("name");
        }
    }
}
