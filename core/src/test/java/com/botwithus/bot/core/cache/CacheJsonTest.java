package com.botwithus.bot.core.cache;

import com.botwithus.bot.api.model.EnumType;
import com.botwithus.bot.api.model.QuestType;
import com.botwithus.bot.api.model.StructType;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The cache JSON decode. Every fixture value is non-default so a parser that drops or
 * re-types a number cannot pass by accident.
 *
 * <p>Failure meaning: under the old {@code new Gson()} decode every number below arrived as a
 * {@code Double}, so {@code Integer.valueOf(55).equals(params.get("2212"))} was false and a
 * caller reading the map with an {@code Integer} expectation saw nothing.</p>
 */
class CacheJsonTest {

    /** A wood-box capacity struct as NXTCache.dll emits it: one int param, one string param. */
    private static final String STRUCT_JSON =
            "{\"id\":7731,\"params\":{\"2212\":55,\"2213\":\"Willow\",\"9001\":-3}}";
    private static final String ENUM_JSON = "{\"id\":13199,\"inputTypeId\":0,\"outputTypeId\":26,"
            + "\"intDefault\":77,\"stringDefault\":\"\",\"entryCount\":2,"
            + "\"entries\":{\"1\":10147,\"17\":16973}}";
    private static final String QUEST_JSON = "{\"id\":42,\"name\":\"Cook's Assistant\","
            + "\"skillRequirements\":[{\"skill\":7,\"level\":12}],"
            + "\"progressVarps\":[[29,1,2]],\"progressVarbits\":[],\"params\":{\"861\":4}}";

    @Test
    void structParams_wholeNumbersDecodeAsInteger_notDouble() {
        StructType struct = NXTCacheMapper.toStructType(CacheJson.parseObject(STRUCT_JSON));

        assertAll(
                () -> assertEquals(Integer.valueOf(55), struct.params().get("2212")),
                () -> assertEquals(Integer.valueOf(-3), struct.params().get("9001")),
                () -> assertEquals("Willow", struct.params().get("2213")),
                () -> assertEquals(OptionalInt.of(55), struct.intParam(2212)),
                () -> assertEquals("55", String.valueOf(struct.params().get("2212"))));
    }

    @Test
    void enumEntries_decodeAsInteger() {
        EnumType type = NXTCacheMapper.toEnumType(CacheJson.parseObject(ENUM_JSON));

        assertEquals(Integer.valueOf(16973), type.entries().get("17"));
        assertEquals(Map.of(1, 10147, 17, 16973), type.intEntries());
        assertEquals(77, type.intDefault());
    }

    @Test
    void questSkillRequirements_decodeAsInteger() {
        QuestType quest = NXTCacheMapper.toQuestType(CacheJson.parseObject(QUEST_JSON));

        assertEquals(Map.of("skill", 7, "level", 12), quest.skillRequirements().getFirst());
        assertEquals(OptionalInt.of(4), quest.intParam(861));
    }

    @Test
    void numbersOutsideIntRange_widenRatherThanTruncate() {
        Map<String, Object> parsed =
                CacheJson.parseObject("{\"big\":4294967296,\"frac\":1.5,\"whole\":3.0}");

        assertAll(
                () -> assertEquals(Long.valueOf(4294967296L), parsed.get("big")),
                () -> assertEquals(Double.valueOf(1.5), parsed.get("frac")),
                () -> assertEquals(Integer.valueOf(3), parsed.get("whole")));
    }
}
