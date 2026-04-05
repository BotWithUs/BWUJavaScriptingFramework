package com.botwithus.bot.api.domain;

import com.botwithus.bot.api.model.VarbitValue;

import java.util.List;

/**
 * Game variable access: varps, varbits, and client variables.
 *
 * @see com.botwithus.bot.api.GameAPI
 */
public interface VariableAPI {

    /**
     * Returns the value of a player variable (varp).
     *
     * @param varId the variable ID
     * @return the variable value
     */
    int getVarp(int varId);

    /**
     * Returns the value of a variable bit (varbit).
     *
     * @param varbitId the varbit ID
     * @return the varbit value
     */
    int getVarbit(int varbitId);

    /**
     * Returns the value of an integer client variable (varc).
     *
     * @param varcId the varc ID
     * @return the varc value
     */
    int getVarcInt(int varcId);

    /**
     * Returns the value of a string client variable (varc).
     *
     * @param varcId the varc ID
     * @return the varc string value
     */
    String getVarcString(int varcId);

    /**
     * Batch-queries multiple varbit values at once.
     *
     * @param varbitIds the varbit IDs to query
     * @return a list of varbit values
     */
    List<VarbitValue> queryVarbits(List<Integer> varbitIds);
}
