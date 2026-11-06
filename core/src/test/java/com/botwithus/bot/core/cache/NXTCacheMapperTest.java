package com.botwithus.bot.core.cache;

import com.botwithus.bot.api.model.ItemType;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NXTCacheMapperTest {

    /** Camel staff as the cache dumps it: wearpos 3, wearpos2 5, wearpos3 -1. */
    private static final int CAMEL_STAFF = 36021;
    private static final int WEAPON_SLOT = 3;
    private static final int SHIELD_SLOT = 5;
    /** Bronze arrow, as the cache dumps it: no ground ops, inventory op 2 "Wield". */
    private static final int BRONZE_ARROW = 882;
    /** Knife: inventory op 4 "Add to tool belt", op 5 unset. */
    private static final int KNIFE = 946;
    private static final List<String> NO_OPS = List.of("", "", "", "", "");

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

    @Test
    void toItemType_unsetGroundOp3_getsTheClientDefaultTake() {
        Map<String, Object> json = itemJson(BRONZE_ARROW);
        json.put("groundOptions", NO_OPS);

        ItemType type = NXTCacheMapper.toItemType(json);

        assertEquals(List.of("", "", "Take", "", ""), type.groundOptions());
    }

    @Test
    void toItemType_ownGroundOp3_isKept() {
        Map<String, Object> json = itemJson(BRONZE_ARROW);
        json.put("groundOptions", List.of("", "", "Collect", "", ""));

        ItemType type = NXTCacheMapper.toItemType(json);

        assertEquals(List.of("", "", "Collect", "", ""), type.groundOptions());
    }

    @Test
    void toItemType_absentGroundOps_arePaddedToFiveSlotsWithTake() {
        ItemType type = NXTCacheMapper.toItemType(itemJson(CAMEL_STAFF));

        assertEquals(List.of("", "", "Take", "", ""), type.groundOptions());
    }

    /**
     * Inventory options stay exactly as the cache stores them. The client does show
     * "Drop" in an unset op 5, but the inventory APIs send a slot's index as the
     * backpack's menu op and Drop is not op 5, so filling it would click something
     * else. This pins that the mapper does not add it.
     */
    @Test
    void toItemType_inventoryOps_areNotDefaulted() {
        Map<String, Object> json = itemJson(KNIFE);
        List<String> stored = List.of("", "", "", "Add to tool belt", "");
        json.put("componentOptions", stored);

        ItemType type = NXTCacheMapper.toItemType(json);

        assertEquals(stored, type.inventoryOptions());
    }

    private static Map<String, Object> itemJson(int id) {
        Map<String, Object> json = new HashMap<>();
        json.put("id", id);
        json.put("name", "Camel staff");
        json.put("wearpos", WEAPON_SLOT);
        return json;
    }
}
