package com.botwithus.bot.core.cache;

import com.botwithus.bot.api.model.ItemType;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NXTCacheMapperTest {

    /** Camel staff as the cache dumps it: wearpos 3, wearpos2 5, wearpos3 -1. */
    private static final int CAMEL_STAFF = 36021;
    private static final int WEAPON_SLOT = 3;
    private static final int SHIELD_SLOT = 5;

    @Test
    void toItemType_twoHandedStaff_mapsTheExtraWearPositions() {
        Map<String, Object> json = itemJson(CAMEL_STAFF);
        json.put("wearpos2", SHIELD_SLOT);
        json.put("wearpos3", ItemType.NO_WEARPOS);

        ItemType type = NXTCacheMapper.toItemType(json);

        assertEquals(WEAPON_SLOT, type.wearpos());
        assertEquals(SHIELD_SLOT, type.wearpos2());
        assertEquals(ItemType.NO_WEARPOS, type.wearpos3());
        assertTrue(type.isTwoHanded());
    }

    @Test
    void toItemType_extraWearPositionsAbsent_defaultToNoWearpos() {
        ItemType type = NXTCacheMapper.toItemType(itemJson(CAMEL_STAFF));

        assertEquals(ItemType.NO_WEARPOS, type.wearpos2());
        assertEquals(ItemType.NO_WEARPOS, type.wearpos3());
        assertFalse(type.isTwoHanded());
    }

    private static Map<String, Object> itemJson(int id) {
        Map<String, Object> json = new HashMap<>();
        json.put("id", id);
        json.put("name", "Camel staff");
        json.put("wearpos", WEAPON_SLOT);
        return json;
    }
}
