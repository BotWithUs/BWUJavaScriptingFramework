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

    /** Bit-position used by the game client to pack the interface ID into a component hash. */
    private static final int INTERFACE_ID_SHIFT = 16;
    /** Mask for the component ID half of a packed component hash. */
    private static final int COMPONENT_ID_MASK = 0xFFFF;

    /** Packs into the format used by the game client for component addressing. */
    public static int componentHash(int interfaceId, int componentId) {
        return (interfaceId << INTERFACE_ID_SHIFT) | componentId;
    }

    public static int interfaceIdFromHash(int hash) {
        return hash >>> INTERFACE_ID_SHIFT;
    }

    public static int componentIdFromHash(int hash) {
        return hash & COMPONENT_ID_MASK;
    }
}
