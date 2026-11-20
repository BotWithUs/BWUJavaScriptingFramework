package com.botwithus.bot.api.model;

import java.util.List;
import java.util.OptionalInt;

/**
 * The result of executing a client script via the pipe RPC.
 *
 * <p>The return values are typed based on the {@code returns} descriptors
 * passed to {@link com.botwithus.bot.api.GameAPI#executeScript}.</p>
 *
 * @param returns the list of return values from the script execution
 * @see com.botwithus.bot.api.GameAPI#executeScript
 */
public record ScriptResult(List<Object> returns) {

    /** Return value {@code index} as an {@code int}, or empty when absent or not an int. */
    public OptionalInt intAt(int index) {
        if (returns == null || index < 0 || index >= returns.size()) {
            return OptionalInt.empty();
        }
        return ConfigValues.asInt(returns.get(index));
    }
}
