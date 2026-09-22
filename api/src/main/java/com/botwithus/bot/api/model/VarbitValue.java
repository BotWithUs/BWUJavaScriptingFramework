package com.botwithus.bot.api.model;

/**
 * A resolved varbit value: the varbit id paired with its current decoded value.
 * Returned in batch by
 * {@link com.botwithus.bot.api.domain.VariableAPI#queryVarbits(java.util.List)}.
 *
 * @param varbitId the varbit type ID
 * @param value    the decoded varbit value; {@code 0} when the varbit's base
 *                 variable is unset, or {@code -1} when the varbit id is
 *                 unknown
 */
public record VarbitValue(int varbitId, int value) {}
