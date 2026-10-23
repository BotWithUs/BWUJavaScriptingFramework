package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.model.VarKind;
import com.botwithus.bot.api.model.VarbitRead;
import com.botwithus.bot.api.model.VarbitType;
import com.botwithus.bot.api.model.VarpRead;
import com.botwithus.bot.api.model.VarpState;
import com.botwithus.bot.core.cache.VarpCacheInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The composition of agent state and cache definition, driven through the same reply shape
 * the agent sends ({@code values, found, states, values64, kinds}).
 */
class VarpReaderTest {

    private static final int ABSENT = 1;
    private static final int PRESENT = 2;
    private static final int UNAVAILABLE = 0;
    private static final int KIND_INT = 0;
    private static final int KIND_LONG = 1;
    private static final int NO_KIND = -1;

    private static final int INT_VARP = 13537;
    private static final int BOOL_VARP = 300;
    private static final int MISSING_VARP = 20000;
    private static final int LONG_VARP = 12921;

    /** A long whose low 32 bits (5) differ from the value it truncates. */
    private static final long WIDE_LONG = (1L << 32) | 5L;

    private final Map<Integer, WireSlot> wire = new HashMap<>();
    private final Map<Integer, VarpCacheInfo> cache = new HashMap<>();
    private final Map<Integer, VarbitType> varbits = new HashMap<>();
    private final List<List<Integer>> requests = new ArrayList<>();
    private final List<Integer> cacheLookups = new ArrayList<>();

    private record WireSlot(int state, int value, long value64, int kind) {
    }

    private final VarpReader reader = new VarpReader(this::reply, varbits::get, id -> {
        cacheLookups.add(id);
        return cache.getOrDefault(id, new VarpCacheInfo.Unknown());
    });

    // ------------------------------------------------------------------ states

    @Test
    void present_isSet_withTheStoredValue_andNeverAsksTheCache() {
        wire.put(INT_VARP, new WireSlot(PRESENT, 2350, 2350, KIND_INT));

        VarpRead read = read(INT_VARP);

        assertEquals(new VarpRead(INT_VARP, VarpState.SET, 2350, 2350, VarKind.INT, true), read);
        assertEquals(List.of(), cacheLookups);
    }

    @Test
    void present_mayLegitimatelyHoldMinusOne() {
        wire.put(LONG_VARP, new WireSlot(PRESENT, -1, -1, KIND_LONG));

        VarpRead read = read(LONG_VARP);

        assertTrue(read.isSet(), "a stored -1 is a value, not 'unset'");
        assertEquals(VarKind.LONG, read.kind());
    }

    @Test
    void presentLong_valueIsTheLowBits_value64TheWhole() {
        wire.put(LONG_VARP, new WireSlot(PRESENT, (int) WIDE_LONG, WIDE_LONG, KIND_LONG));

        VarpRead read = read(LONG_VARP);

        assertEquals(5, read.value());
        assertEquals(WIDE_LONG, read.value64());
    }

    @Test
    void absent_withAKnownDefault_readsThatDefault_verified() {
        wire.put(INT_VARP, new WireSlot(ABSENT, 0, 0, NO_KIND));
        cache.put(INT_VARP, new VarpCacheInfo.Default(0));

        VarpRead read = read(INT_VARP);

        assertEquals(VarpState.DEFAULT_NOT_SET_CLIENTSIDE, read.state());
        assertEquals(0, read.value());
        assertTrue(read.defaultVerified());
    }

    /** The agent sends 0 for an absent varp; the cache's default replaces it. */
    @Test
    void absent_withAMinusOneDefault_readsMinusOne_notTheAgentsZero() {
        wire.put(BOOL_VARP, new WireSlot(ABSENT, 0, 0, NO_KIND));
        cache.put(BOOL_VARP, new VarpCacheInfo.Default(-1));

        VarpRead read = read(BOOL_VARP);

        assertEquals(-1, read.value());
        assertEquals(-1L, read.value64());
        assertTrue(read.defaultVerified());
    }

    @Test
    void absent_andTheCacheHasNoSuchVarp_isNoSuchVarp() {
        wire.put(MISSING_VARP, new WireSlot(ABSENT, 0, 0, NO_KIND));
        cache.put(MISSING_VARP, new VarpCacheInfo.NoSuchVarp());

        VarpRead read = read(MISSING_VARP);

        assertEquals(VarpState.NO_SUCH_VARP, read.state());
        assertEquals(VarpRead.NO_VALUE, read.value());
    }

    @Test
    void absent_andTheCacheCannotSay_isAnUnverifiedZeroDefault() {
        wire.put(INT_VARP, new WireSlot(ABSENT, 0, 0, NO_KIND));
        for (VarpCacheInfo info : List.of(new VarpCacheInfo.Unknown(),
                new VarpCacheInfo.ExistsDefaultUnknown())) {
            cache.put(INT_VARP, info);

            VarpRead read = read(INT_VARP);

            assertEquals(VarpState.DEFAULT_NOT_SET_CLIENTSIDE, read.state(), info.toString());
            assertEquals(VarpReader.FALLBACK_DEFAULT, read.value(), info.toString());
            assertFalse(read.defaultVerified(), info.toString());
        }
    }

    @Test
    void unavailable_isUnavailable_andNeverAsksTheCache() {
        wire.put(INT_VARP, new WireSlot(UNAVAILABLE, -1, -1, NO_KIND));
        cache.put(INT_VARP, new VarpCacheInfo.Default(0));

        VarpRead read = read(INT_VARP);

        assertEquals(VarpState.UNAVAILABLE, read.state());
        assertEquals(VarpRead.NO_VALUE, read.value());
        assertEquals(List.of(), cacheLookups, "a failed read is never turned into a default");
    }

    @Test
    void aStateNumberOutsideTheContract_isUnavailable() {
        wire.put(INT_VARP, new WireSlot(7, 42, 42, KIND_INT));

        assertEquals(VarpState.UNAVAILABLE, read(INT_VARP).state());
    }

    /** An agent with state but not values64 / kinds: sign-extended value, unknown kind. */
    @Test
    void aReplyWithoutValues64_signExtendsTheValue() {
        VarpReader older = new VarpReader((method, params) -> Map.of(
                "values", List.of(-7), "found", List.of(true), "states", List.of(PRESENT)),
                varbits::get, id -> new VarpCacheInfo.Unknown());

        VarpRead read = older.readVarps(List.of(INT_VARP)).getFirst();

        assertEquals(-7L, read.value64());
        assertEquals(VarKind.UNKNOWN, read.kind());
    }

    // ------------------------------------------------------------------ chunking

    @Test
    void moreIdsThanTheAgentCap_areSplit_andNoneIsDropped() {
        List<Integer> ids = IntStream.range(0, 600).boxed().toList();
        ids.forEach(id -> wire.put(id, new WireSlot(PRESENT, id, id, KIND_INT)));

        List<VarpRead> reads = reader.readVarps(ids);

        assertEquals(List.of(256, 256, 88), requests.stream().map(List::size).toList());
        assertEquals(ids, reads.stream().map(VarpRead::value).toList(), "in order, every id");
    }

    // ------------------------------------------------------------------ varbits

    /** Unset base whose default is -1: the game reads every bit as set, and so do we. */
    @Test
    void varbitOverADefaultedMinusOneBase_readsAllOnes() {
        varbits.put(1, new VarbitType(1, BOOL_VARP, 0, 17, 17));
        varbits.put(2, new VarbitType(2, BOOL_VARP, 0, 4, 7));
        wire.put(BOOL_VARP, new WireSlot(ABSENT, 0, 0, NO_KIND));
        cache.put(BOOL_VARP, new VarpCacheInfo.Default(-1));

        List<VarbitRead> reads = reader.readVarbits(List.of(1, 2));

        assertEquals(new VarbitRead(1, VarpState.DEFAULT_NOT_SET_CLIENTSIDE, 1, true), reads.get(0));
        assertEquals(new VarbitRead(2, VarpState.DEFAULT_NOT_SET_CLIENTSIDE, 15, true), reads.get(1));
    }

    @Test
    void varbitOverAnUnverifiedDefault_readsZero_unverified() {
        varbits.put(1, new VarbitType(1, INT_VARP, 0, 17, 17));
        wire.put(INT_VARP, new WireSlot(ABSENT, 0, 0, NO_KIND));

        assertEquals(new VarbitRead(1, VarpState.DEFAULT_NOT_SET_CLIENTSIDE, 0, false),
                reader.readVarbits(List.of(1)).getFirst());
    }

    @Test
    void varbitOverASetBase_decodesTheStoredBits() {
        varbits.put(1, new VarbitType(1, INT_VARP, 0, 4, 7));
        wire.put(INT_VARP, new WireSlot(PRESENT, 11 << 4 | 0xF, 0, KIND_INT));

        assertEquals(new VarbitRead(1, VarpState.SET, 11, true),
                reader.readVarbits(List.of(1)).getFirst());
    }

    @Test
    void varbitOverAnUnavailableBase_isUnavailable_andCarriesNoValue() {
        varbits.put(1, new VarbitType(1, INT_VARP, 0, 17, 17));
        wire.put(INT_VARP, new WireSlot(UNAVAILABLE, -1, -1, NO_KIND));

        assertEquals(new VarbitRead(1, VarpState.UNAVAILABLE, VarpRead.NO_VALUE, true),
                reader.readVarbits(List.of(1)).getFirst());
    }

    @Test
    void unknownVarbit_orMissingBase_isNoSuchVarp() {
        varbits.put(1, new VarbitType(1, MISSING_VARP, 0, 0, 0));
        wire.put(MISSING_VARP, new WireSlot(ABSENT, 0, 0, NO_KIND));
        cache.put(MISSING_VARP, new VarpCacheInfo.NoSuchVarp());

        List<VarbitRead> reads = reader.readVarbits(List.of(1, 999));

        assertEquals(VarpState.NO_SUCH_VARP, reads.get(0).state());
        assertEquals(VarpState.NO_SUCH_VARP, reads.get(1).state());
        assertEquals(VarpRead.NO_VALUE, reads.get(1).value());
    }

    /** Varcs carry found only: found is set, not found is the unverified 0 default. */
    @Test
    void varcVarbit_takesPresenceFromFound() {
        varbits.put(1, new VarbitType(1, 500, 1, 3, 3));
        varbits.put(2, new VarbitType(2, 501, 1, 3, 3));
        VarpReader varcReader = new VarpReader((method, params) -> Map.of(
                "values", List.of(1 << 3, -1), "found", List.of(true, false)),
                varbits::get, id -> new VarpCacheInfo.Unknown());

        List<VarbitRead> reads = varcReader.readVarbits(List.of(1, 2));

        assertEquals(new VarbitRead(1, VarpState.SET, 1, true), reads.get(0));
        assertEquals(new VarbitRead(2, VarpState.DEFAULT_NOT_SET_CLIENTSIDE, 0, false), reads.get(1));
    }

    // ------------------------------------------------------------------ helpers

    private VarpRead read(int id) {
        cacheLookups.clear();
        return reader.readVarps(List.of(id)).getFirst();
    }

    /** Builds the agent's get_varps reply for the requested ids. */
    private Map<String, Object> reply(String method, Map<String, Object> params) {
        List<Integer> ids = MapHelper.toIntList(params.get("ids"));
        requests.add(ids);
        List<Integer> values = new ArrayList<>();
        List<Boolean> found = new ArrayList<>();
        List<Integer> states = new ArrayList<>();
        List<Long> values64 = new ArrayList<>();
        List<Integer> kinds = new ArrayList<>();
        for (int id : ids) {
            WireSlot slot = wire.getOrDefault(id, new WireSlot(UNAVAILABLE, -1, -1, NO_KIND));
            values.add(slot.value());
            found.add(slot.state() == PRESENT);
            states.add(slot.state());
            values64.add(slot.value64());
            kinds.add(slot.kind());
        }
        return Map.of("values", values, "found", found, "states", states,
                "values64", values64, "kinds", kinds);
    }
}
