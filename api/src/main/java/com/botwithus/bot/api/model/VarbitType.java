package com.botwithus.bot.api.model;

/**
 * Varbit (variable bit) definition from the game cache. A varbit is a bit-range
 * view onto a base variable: bits {@code [lsb, msb]} (inclusive) of variable
 * {@code varId} in domain {@code domainType} ({@code 0} = player varp).
 *
 * <p>Decoding lives consumer-side — the producer exposes only raw variable
 * reads, and {@link com.botwithus.bot.api.domain.VariableAPI#getVarbit(int)}
 * reads the base variable then shifts/masks with these fields.</p>
 *
 * @param id         the varbit type ID
 * @param varId      the base variable id the bits are read from
 * @param domainType the variable domain ({@code 0} = player varp)
 * @param lsb        least-significant bit index, inclusive
 * @param msb        most-significant bit index, inclusive
 * @see com.botwithus.bot.api.domain.VariableAPI#getVarbit(int)
 */
public record VarbitType(
        int id,
        int varId,
        int domainType,
        int lsb,
        int msb
) {}
