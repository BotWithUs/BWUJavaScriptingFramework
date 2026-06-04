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
 */
final class NXTCacheMapper {

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
                getBool(j, "isAllowedOnGE"),
                getStringList(j, "groundOptions"),
                // The cache's "componentOptions" carries the inventory/UI
                // right-click set; "groundOptions" stays distinct.
                getStringList(j, "componentOptions"),
                getObjectMap(j, "params")
        );
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
