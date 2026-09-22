package com.botwithus.bot.core.cache;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code nxt_varp_info} mirror, pinned to the literals NXTCacheLibrary's header pins with
 * static_asserts, and the meaning of every result code. No native library is needed.
 */
class VarpInfoAbiTest {

    private static final int BASE_STRING = 2;
    private static final int DEFAULT_NONE = 0;
    private static final int INT_DEFAULT = 0;
    private static final int DOMAIN_DEFAULT = -1;

    @Test
    void layoutMatchesTheHeaderPins() {
        assertEquals(40, VarpInfoAbi.LAYOUT.byteSize(), "sizeof(nxt_varp_info)");
        assertEquals(0, offset("struct_size"));
        assertEquals(4, offset("version"));
        assertEquals(8, offset("id"));
        assertEquals(12, offset("type_id"));
        assertEquals(16, offset("base_type"));
        assertEquals(20, offset("default_rule"));
        assertEquals(24, offset("default_value"));
        assertEquals(32, offset("op7_absent"));
        assertEquals(33, offset("op8_present"));
        assertEquals(34, offset("has_op4"));
        assertEquals(35, offset("op4"));
        assertEquals(36, offset("has_op5"));
        assertEquals(37, offset("op5"));
        assertEquals(38, offset("op110"));
    }

    @Test
    void prepare_setsTheInSizeTheLibraryRequires() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment info = arena.allocate(VarpInfoAbi.LAYOUT);
            VarpInfoAbi.prepare(info);
            assertEquals(40, info.get(JAVA_INT, 0));
        }
    }

    @Test
    void ok_withATypeDefault_isThatDefault() {
        assertEquals(new VarpCacheInfo.Default(INT_DEFAULT),
                classifyOk(VarpInfoAbi.BASE_INTEGER, VarpInfoAbi.DEFAULT_TYPE, INT_DEFAULT));
    }

    /** BOOLEAN with opcode 7 absent: the domain's -1. */
    @Test
    void ok_withADomainDefault_isMinusOne() {
        assertEquals(new VarpCacheInfo.Default(DOMAIN_DEFAULT),
                classifyOk(VarpInfoAbi.BASE_INTEGER, VarpInfoAbi.DEFAULT_DOMAIN, DOMAIN_DEFAULT));
    }

    @Test
    void ok_longBase_keepsTheFullWidthDefault() {
        long wide = 1L << 40;
        assertEquals(new VarpCacheInfo.Default(wide),
                classifyOk(VarpInfoAbi.BASE_LONG, VarpInfoAbi.DEFAULT_TYPE, wide));
    }

    @Test
    void ok_withNoComputableDefault_orAStringBase_existsWithTheDefaultUnknown() {
        assertEquals(new VarpCacheInfo.ExistsDefaultUnknown(),
                classifyOk(VarpInfoAbi.BASE_INTEGER, DEFAULT_NONE, 0));
        assertEquals(new VarpCacheInfo.ExistsDefaultUnknown(),
                classifyOk(BASE_STRING, VarpInfoAbi.DEFAULT_TYPE, 0));
    }

    @Test
    void ok_withAVersionOrSizeThisHostDoesNotUnderstand_isUnknown_notAGuess() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment info = filled(arena, VarpInfoAbi.BASE_INTEGER, VarpInfoAbi.DEFAULT_TYPE, 0);
            info.set(JAVA_INT, VarpInfoAbi.VERSION_OFFSET, VarpInfoAbi.VERSION + 1);
            assertEquals(new VarpCacheInfo.Unknown(), VarpInfoAbi.classify(NXTCache.NXT_OK, info));

            MemorySegment shortFill = filled(arena, VarpInfoAbi.BASE_INTEGER, VarpInfoAbi.DEFAULT_TYPE, 0);
            shortFill.set(JAVA_INT, VarpInfoAbi.STRUCT_SIZE_OFFSET, 32);
            assertEquals(new VarpCacheInfo.Unknown(), VarpInfoAbi.classify(NXTCache.NXT_OK, shortFill));
        }
    }

    @Test
    void errorCodes_mapToNoSuchVarp_existsUnknown_orUnknown_andIoIsNeverNoSuchVarp() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment untouched = arena.allocate(VarpInfoAbi.LAYOUT);
            assertEquals(new VarpCacheInfo.NoSuchVarp(),
                    VarpInfoAbi.classify(NXTCache.NXT_ERR_NOT_FOUND, untouched));
            assertEquals(new VarpCacheInfo.ExistsDefaultUnknown(),
                    VarpInfoAbi.classify(NXTCache.NXT_ERR_DECODE, untouched));
            for (int rc : new int[]{ NXTCache.NXT_ERR_IO, NXTCache.NXT_ERR_INTERNAL,
                    NXTCache.NXT_ERR_INVALID }) {
                assertEquals(new VarpCacheInfo.Unknown(), VarpInfoAbi.classify(rc, untouched));
            }
        }
    }

    @Test
    void onlyOkAndNotFound_areMemoised() {
        assertTrue(VarpInfoAbi.isMemoisable(NXTCache.NXT_OK, new VarpCacheInfo.Default(0)));
        assertTrue(VarpInfoAbi.isMemoisable(NXTCache.NXT_OK, new VarpCacheInfo.ExistsDefaultUnknown()));
        assertTrue(VarpInfoAbi.isMemoisable(NXTCache.NXT_ERR_NOT_FOUND, new VarpCacheInfo.NoSuchVarp()));
        assertFalse(VarpInfoAbi.isMemoisable(NXTCache.NXT_OK, new VarpCacheInfo.Unknown()),
                "an OK this host could not read is retried");
        for (int rc : new int[]{ NXTCache.NXT_ERR_DECODE, NXTCache.NXT_ERR_IO,
                NXTCache.NXT_ERR_INTERNAL, NXTCache.NXT_ERR_INVALID }) {
            assertFalse(VarpInfoAbi.isMemoisable(rc, new VarpCacheInfo.Unknown()), "rc=" + rc);
        }
    }

    private static VarpCacheInfo classifyOk(int baseType, int rule, long defaultValue) {
        try (Arena arena = Arena.ofConfined()) {
            return VarpInfoAbi.classify(NXTCache.NXT_OK, filled(arena, baseType, rule, defaultValue));
        }
    }

    /** A struct as the library fills it on NXT_OK. */
    private static MemorySegment filled(Arena arena, int baseType, int rule, long defaultValue) {
        MemorySegment info = arena.allocate(VarpInfoAbi.LAYOUT);
        info.set(JAVA_INT, VarpInfoAbi.STRUCT_SIZE_OFFSET, (int) VarpInfoAbi.LAYOUT.byteSize());
        info.set(JAVA_INT, VarpInfoAbi.VERSION_OFFSET, VarpInfoAbi.VERSION);
        info.set(JAVA_INT, VarpInfoAbi.BASE_TYPE_OFFSET, baseType);
        info.set(JAVA_INT, VarpInfoAbi.DEFAULT_RULE_OFFSET, rule);
        info.set(JAVA_LONG, VarpInfoAbi.DEFAULT_VALUE_OFFSET, defaultValue);
        return info;
    }

    private static long offset(String field) {
        return VarpInfoAbi.LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement(field));
    }
}
