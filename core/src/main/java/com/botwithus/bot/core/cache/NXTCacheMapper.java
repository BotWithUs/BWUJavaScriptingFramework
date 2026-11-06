package com.botwithus.bot.core.cache;

import com.botwithus.bot.api.model.EnumType;
import com.botwithus.bot.api.model.ItemType;
import com.botwithus.bot.api.model.LocationType;
import com.botwithus.bot.api.model.NpcType;
import com.botwithus.bot.api.model.QuestType;
import com.botwithus.bot.api.model.SequenceType;
import com.botwithus.bot.api.model.StructType;
import com.botwithus.bot.api.model.VarbitType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.botwithus.bot.core.impl.MapHelper.getBool;
import static com.botwithus.bot.core.impl.MapHelper.getInt;
import static com.botwithus.bot.core.impl.MapHelper.getIntOr;
import static com.botwithus.bot.core.impl.MapHelper.getIntList;
import static com.botwithus.bot.core.impl.MapHelper.getMapList;
import static com.botwithus.bot.core.impl.MapHelper.getObjectMap;
import static com.botwithus.bot.core.impl.MapHelper.getString;
import static com.botwithus.bot.core.impl.MapHelper.getStringList;

/**
 * Translation between the cache JSON shape (camelCase, RuneScape NXT
 * naming) and the {@code com.botwithus.bot.api.model} record shape
 * (Java naming, narrower field set).
 *
 * <p>Field mapping is largely 1:1; the fixups are noted inline. The
 * cache exposes more fields than the records (full model/render data,
 * member templates, etc.) — extend the records if scripts need them.</p>
 *
 * <p>rule-exception: {@code {rule:no-instanceof}} — this file decodes
 * cache JSON into typed records, recovering shape with {@code instanceof}
 * patterns where the JSON gives us {@code List<?>} or {@code Object}. The
 * type discrimination lives outside Java's compile-time reach (it is
 * encoded by the cache producer). Mirrors {@link com.botwithus.bot.core.impl.MapHelper}'s
 * wire-decode boundary justification.</p>
 */
final class NXTCacheMapper {

    /** Right-click slots an item type carries, on the ground and in an inventory. */
    private static final int ITEM_OPTION_SLOTS = 5;
    /** Ground op 3 (0-based 2): the client shows "Take" there when the cache leaves it unset. */
    private static final int GROUND_TAKE_SLOT = 2;
    /** Inventory op 5 (0-based 4): the client shows "Drop" there when the cache leaves it unset. */
    private static final int INVENTORY_DROP_SLOT = 4;
    private static final String DEFAULT_GROUND_OPTION = "Take";
    private static final String DEFAULT_INVENTORY_OPTION = "Drop";

    private NXTCacheMapper() {}

    static ItemType toItemType(Map<String, Object> j) {
        return new ItemType(
                getInt(j, "id"),
                getString(j, "name"),
                getBool(j, "isMembers"),
                getBool(j, "isStackable"),
                getInt(j, "shopPrice"),
                getInt(j, "geBuyLimit"),
                getInt(j, "category"),
                getInt(j, "notedID"),
                getInt(j, "wearpos"),
                // Extra slots the item blocks while worn; a two-handed weapon
                // carries the shield slot here. Absent on older dumps.
                getIntOr(j, "wearpos2", ItemType.NO_WEARPOS),
                getIntOr(j, "wearpos3", ItemType.NO_WEARPOS),
                getBool(j, "isAllowedOnGE"),
                withClientDefault(getStringList(j, "groundOptions"),
                        GROUND_TAKE_SLOT, DEFAULT_GROUND_OPTION),
                // The cache's "componentOptions" carries the inventory/UI
                // right-click set; "groundOptions" stays distinct.
                withClientDefault(getStringList(j, "componentOptions"),
                        INVENTORY_DROP_SLOT, DEFAULT_INVENTORY_OPTION),
                getObjectMap(j, "params")
        );
    }

    /**
     * The option list the game's menu shows, not the one the cache stores. An item
     * definition starts with "Take" in ground op 3 and "Drop" in inventory op 5, and
     * the cache only <em>overrides</em> slots, so a slot the cache never set reads
     * {@code ""} here but shows the default in game. Fills {@code slot} with
     * {@code fallback} only when it is unset; an op the cache does set, including
     * a different one in that slot, is kept. A short or absent list is padded to
     * {@link #ITEM_OPTION_SLOTS} first, since the client's item has all five.
     */
    private static List<String> withClientDefault(List<String> options, int slot, String fallback) {
        List<String> filled = new ArrayList<>(options);
        while (filled.size() < ITEM_OPTION_SLOTS) {
            filled.add("");
        }
        if (filled.get(slot).isEmpty()) {
            filled.set(slot, fallback);
        }
        return List.copyOf(filled);
    }

    static NpcType toNpcType(Map<String, Object> j) {
        return new NpcType(
                getInt(j, "id"),
                getString(j, "name"),
                getInt(j, "combatLevel"),
                getBool(j, "isVisible"),
                getBool(j, "isClickable"),
                getStringList(j, "options"),
                getInt(j, "varbitId"),
                getInt(j, "varpId"),
                getIntList(j, "transforms"),
                getObjectMap(j, "params")
        );
    }

    static LocationType toLocationType(Map<String, Object> j) {
        return new LocationType(
                getInt(j, "id"),
                getString(j, "name"),
                getInt(j, "sizeX"),
                getInt(j, "sizeY"),
                getInt(j, "interactType"),
                getInt(j, "solidType"),
                getBool(j, "isMembers"),
                getStringList(j, "options"),
                getInt(j, "varbitId"),
                getInt(j, "varpId"),
                getIntList(j, "transforms"),
                getInt(j, "mapSpriteId"),
                getObjectMap(j, "params")
        );
    }

    static EnumType toEnumType(Map<String, Object> j) {
        return new EnumType(
                getInt(j, "id"),
                getInt(j, "inputTypeId"),
                getInt(j, "outputTypeId"),
                getInt(j, "intDefault"),
                getString(j, "stringDefault"),
                getInt(j, "entryCount"),
                getObjectMap(j, "entries")
        );
    }

    static StructType toStructType(Map<String, Object> j) {
        return new StructType(
                getInt(j, "id"),
                getObjectMap(j, "params")
        );
    }

    static VarbitType toVarbitType(Map<String, Object> j) {
        return new VarbitType(
                getInt(j, "id"),
                getInt(j, "varId"),
                getInt(j, "domainType"),
                getInt(j, "lsb"),
                getInt(j, "msb")
        );
    }

    static SequenceType toSequenceType(Map<String, Object> j) {
        // Cache exposes the full frame ID array; the record only carries
        // lengths + count. Synthesize frameCount from the lengths list.
        List<Integer> frameLengths = getIntList(j, "frameLengths");
        return new SequenceType(
                getInt(j, "id"),
                frameLengths.size(),
                frameLengths,
                getInt(j, "loopOffset"),
                getInt(j, "priority"),
                getInt(j, "offHand"),
                getInt(j, "mainHand"),
                getInt(j, "maxLoops"),
                getInt(j, "animatingPrecedence"),
                getInt(j, "walkingPrecedence"),
                getInt(j, "replayMode"),
                getBool(j, "tweened"),
                getObjectMap(j, "params")
        );
    }

    static QuestType toQuestType(Map<String, Object> j) {
        return new QuestType(
                getInt(j, "id"),
                getString(j, "name"),
                getString(j, "listName"),
                getInt(j, "category"),
                getInt(j, "difficulty"),
                getBool(j, "membersOnly"),
                getInt(j, "questPoints"),
                getInt(j, "questPointReq"),
                getInt(j, "questItemSprite"),
                getIntList(j, "startLocations"),
                getInt(j, "alternateStartLocation"),
                getIntList(j, "dependentQuestIds"),
                getMapList(j, "skillRequirements"),
                tripleListToMaps(j.get("progressVarps")),
                tripleListToMaps(j.get("progressVarbits")),
                getObjectMap(j, "params")
        );
    }

    /**
     * Cache encodes progress varps/varbits as triples
     * {@code [varId, threshold1, threshold2]} (Types.cpp:1318-1338).
     * The record expects {@code List<Map<String, Object>>} — wrap each
     * triple into a labelled map. Keys mirror the producer-side decode
     * order: id, then the two thresholds.
     */
    private static List<Map<String, Object>> tripleListToMaps(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Object e : list) {
            if (!(e instanceof List<?> triple) || triple.size() < 3) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>(3);
            m.put("id",         triple.get(0) instanceof Number n ? n.intValue() : 0);
            m.put("threshold1", triple.get(1) instanceof Number n ? n.intValue() : 0);
            m.put("threshold2", triple.get(2) instanceof Number n ? n.intValue() : 0);
            out.add(m);
        }
        return out;
    }
}
