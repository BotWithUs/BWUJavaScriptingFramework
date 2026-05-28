package com.botwithus.bot.api.model;

/**
 * A resolved varbit value: the varbit id paired with its current decoded value.
 * Returned in batch by
 * {@link com.botwithus.bot.api.domain.VariableAPI#queryVarbits(java.util.List)}.
 *
 * @param varbitId the varbit type ID
 * @param value    the decoded varbit value, or {@code -1} if the varbit is unknown
 */
public record VarbitValue(int varbitId, int value) {}
