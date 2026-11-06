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
    /** An untradeable item with its own op 5 ("Destroy"), which must survive. */
    private static final int DESTROY_ITEM = 49136;
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
    void toItemType_unsetOps_getTheClientDefaults() {
        Map<String, Object> json = itemJson(BRONZE_ARROW);
        json.put("groundOptions", NO_OPS);
        json.put("componentOptions", List.of("", "Wield", "", "", ""));

        ItemType type = NXTCacheMapper.toItemType(json);

        assertEquals(List.of("", "", "Take", "", ""), type.groundOptions());
        assertEquals(List.of("", "Wield", "", "", "Drop"), type.inventoryOptions());
    }

    @Test
    void toItemType_ownOps_areKeptInTheDefaultSlots() {
        Map<String, Object> json = itemJson(DESTROY_ITEM);
        json.put("groundOptions", List.of("", "", "Collect", "", ""));
        json.put("componentOptions", List.of("Read", "Put in pocket", "", "", "Destroy"));

        ItemType type = NXTCacheMapper.toItemType(json);

        assertEquals(List.of("", "", "Collect", "", ""), type.groundOptions());
        assertEquals(List.of("Read", "Put in pocket", "", "", "Destroy"), type.inventoryOptions());
    }

    @Test
    void toItemType_absentOps_arePaddedToFiveSlotsWithTheDefaults() {
        ItemType type = NXTCacheMapper.toItemType(itemJson(CAMEL_STAFF));

        assertEquals(List.of("", "", "Take", "", ""), type.groundOptions());
        assertEquals(List.of("", "", "", "", "Drop"), type.inventoryOptions());
    }

    private static Map<String, Object> itemJson(int id) {
        Map<String, Object> json = new HashMap<>();
        json.put("id", id);
        json.put("name", "Camel staff");
        json.put("wearpos", WEAPON_SLOT);
        return json;
    }
}
