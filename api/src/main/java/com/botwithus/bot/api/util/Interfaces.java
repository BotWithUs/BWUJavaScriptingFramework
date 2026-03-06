package com.botwithus.bot.api.util;

/**
 * Common RS3 interface ID constants and component hash utilities.
 */
public final class Interfaces {

    private Interfaces() {}

    public static final int BACKPACK = 1473;
    public static final int BANK = 517;
    public static final int EQUIPMENT = 1464;
    public static final int GRAND_EXCHANGE = 105;
    public static final int CHAT_BOX = 137;
    public static final int DIALOGUE = 1184;
    public static final int SKILLS = 1466;
    public static final int PRAYER = 1458;
    public static final int MAGIC = 1461;
    public static final int COMBAT = 1460;
    public static final int MINIMAP = 1465;
    public static final int WORLD_MAP = 1587;
    public static final int LOGOUT = 182;
    public static final int SETTINGS = 1433;

    /** Packs into the format used by the game client for component addressing. */
    public static int componentHash(int interfaceId, int componentId) {
        return (interfaceId << 16) | componentId;
    }

    public static int interfaceIdFromHash(int hash) {
        return hash >>> 16;
    }

    public static int componentIdFromHash(int hash) {
        return hash & 0xFFFF;
    }
}
