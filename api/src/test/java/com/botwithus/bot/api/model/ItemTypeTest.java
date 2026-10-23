package com.botwithus.bot.api.model;

import com.botwithus.bot.api.inventory.Equipment;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemTypeTest {

    /** Camel staff: cache wearpos 3, wearpos2 5 (two-handed). */
    private static final int CAMEL_STAFF = 36021;
    /** Dragon mace: cache wearpos 3, wearpos2 -1 (one-handed). */
    private static final int DRAGON_MACE = 1434;
    /** Mirror shield: cache wearpos 5, wearpos2 -1. */
    private static final int MIRROR_SHIELD = 4156;

    private static final int WEAPON = Equipment.Slot.WEAPON.index;
    private static final int SHIELD = Equipment.Slot.SHIELD.index;
    private static final int NONE = ItemType.NO_WEARPOS;

    @Test
    void isTwoHanded_shieldInWearpos2_isTrue() {
        assertTrue(item(CAMEL_STAFF, WEAPON, SHIELD, NONE).isTwoHanded());
    }

    @Test
    void isTwoHanded_shieldInWearpos3_isTrue() {
        assertTrue(item(CAMEL_STAFF, WEAPON, NONE, SHIELD).isTwoHanded());
    }

    @Test
    void isTwoHanded_noExtraSlots_isFalse() {
        assertFalse(item(DRAGON_MACE, WEAPON, NONE, NONE).isTwoHanded());
    }

    @Test
    void isTwoHanded_shieldWornInItsOwnSlot_isFalse() {
        assertFalse(item(MIRROR_SHIELD, SHIELD, NONE, NONE).isTwoHanded());
    }

    @Test
    void compatConstructor_noExtraSlots_readsBackNoWearpos() {
        ItemType type = new ItemType(DRAGON_MACE, "Dragon mace", true, false, 0, 0, 0, NONE,
                WEAPON, true, List.of(), List.of(), Map.of());

        assertEquals(NONE, type.wearpos2());
        assertEquals(NONE, type.wearpos3());
        assertFalse(type.isTwoHanded());
    }

    private static ItemType item(int id, int wearpos, int wearpos2, int wearpos3) {
        return new ItemType(id, "", true, false, 0, 0, 0, NONE, wearpos, wearpos2, wearpos3,
                true, List.of(), List.of(), Map.of());
    }
}
