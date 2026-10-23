package com.botwithus.bot.api.model;

/**
 * A resolved varbit value: the varbit id paired with its current decoded value.
 * Returned in batch by
 * {@link com.botwithus.bot.api.domain.VariableAPI#queryVarbits(java.util.List)}.
 *
 * @param varbitId the varbit type ID
 * @param value    the decoded varbit value, as
 *                 {@link com.botwithus.bot.api.domain.VariableAPI#getVarbit(int)}
 *                 values it; {@code -1} when there is none
 */
public record VarbitValue(int varbitId, int value) {}
