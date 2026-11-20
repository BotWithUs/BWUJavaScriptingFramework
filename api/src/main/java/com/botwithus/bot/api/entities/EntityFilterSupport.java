package com.botwithus.bot.api.entities;

/** Small helpers the entity filters share. */
final class EntityFilterSupport {

    private EntityFilterSupport() {
    }

    /** Whether {@code ids} holds {@code id}. */
    static boolean containsId(int[] ids, int id) {
        for (int candidate : ids) {
            if (candidate == id) {
                return true;
            }
        }
        return false;
    }
}
