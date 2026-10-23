package com.botwithus.bot.core.cache;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * The {@code nxt_varp_info} C struct and the rules for reading it, mirrored from
 * NXTCacheLibrary's {@code src/c_api/nxtcache_c.h}. Every constant here must match that
 * header; {@code VarpInfoAbiTest} pins the layout the header pins with static_asserts.
 *
 * <p>The decision of what a result means lives in {@link #classify}, a pure function, so it
 * is testable without the native library.</p>
 */
final class VarpInfoAbi {

    /** {@code NXT_VARP_INFO_VERSION}. */
    static final int VERSION = 1;

    /** {@code NXT_VAR_BASE_*}: the ScriptVarType's storage type. */
    static final int BASE_INTEGER = 0;
    static final int BASE_LONG = 1;

    /** {@code NXT_VARP_DEFAULT_*}: which branch of the client's rule gave the default. */
    static final int DEFAULT_TYPE = 1;
    static final int DEFAULT_DOMAIN = 2;

    /** The 40-byte struct, field for field, no implicit padding. */
    static final StructLayout LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("struct_size"),
            JAVA_INT.withName("version"),
            JAVA_INT.withName("id"),
            JAVA_INT.withName("type_id"),
            JAVA_INT.withName("base_type"),
            JAVA_INT.withName("default_rule"),
            JAVA_LONG.withName("default_value"),
            JAVA_BYTE.withName("op7_absent"),
            JAVA_BYTE.withName("op8_present"),
            JAVA_BYTE.withName("has_op4"),
            JAVA_BYTE.withName("op4"),
            JAVA_BYTE.withName("has_op5"),
            JAVA_BYTE.withName("op5"),
            JAVA_SHORT.withName("op110"));

    static final long STRUCT_SIZE_OFFSET = offsetOf("struct_size");
    static final long VERSION_OFFSET = offsetOf("version");
    static final long BASE_TYPE_OFFSET = offsetOf("base_type");
    static final long DEFAULT_RULE_OFFSET = offsetOf("default_rule");
    static final long DEFAULT_VALUE_OFFSET = offsetOf("default_value");

    private static final VarpCacheInfo EXISTS_DEFAULT_UNKNOWN =
            new VarpCacheInfo.ExistsDefaultUnknown();
    private static final VarpCacheInfo NO_SUCH_VARP = new VarpCacheInfo.NoSuchVarp();
    private static final VarpCacheInfo UNKNOWN = new VarpCacheInfo.Unknown();

    private VarpInfoAbi() {
    }

    /** Sets the IN field the library requires before the call. */
    static void prepare(MemorySegment info) {
        info.set(JAVA_INT, STRUCT_SIZE_OFFSET, (int) LAYOUT.byteSize());
    }

    /**
     * What one {@code nxt_get_varp_info} call means.
     *
     * <ul>
     *   <li>{@code NXT_ERR_NOT_FOUND}: no such varp.</li>
     *   <li>{@code NXT_ERR_DECODE}: the varp exists but its definition is malformed, so its
     *       default is unknown.</li>
     *   <li>Any other error (I/O, internal, invalid): nothing is known. Never "no such
     *       varp".</li>
     *   <li>{@code NXT_OK} with a version or filled size this host does not understand:
     *       nothing is known, rather than a guess from a layout that moved.</li>
     *   <li>{@code NXT_OK} with a TYPE or DOMAIN default on an integer or long base: that
     *       default. Anything else: exists, default unknown.</li>
     * </ul>
     */
    static VarpCacheInfo classify(int rc, MemorySegment info) {
        return switch (rc) {
            case NXTCache.NXT_OK -> classifyFilled(info);
            case NXTCache.NXT_ERR_NOT_FOUND -> NO_SUCH_VARP;
            case NXTCache.NXT_ERR_DECODE -> EXISTS_DEFAULT_UNKNOWN;
            default -> UNKNOWN;
        };
    }

    /**
     * True for an answer that stays true for the life of the cache, which is worth
     * memoising: a successful decode, or "no such varp". A decode error and every failure
     * are retried, and so is an {@code NXT_OK} whose layout this host could not read.
     */
    static boolean isMemoisable(int rc, VarpCacheInfo result) {
        if (rc == NXTCache.NXT_ERR_NOT_FOUND) {
            return true;
        }
        return rc == NXTCache.NXT_OK && switch (result) {
            case VarpCacheInfo.Default ignored -> true;
            case VarpCacheInfo.ExistsDefaultUnknown ignored -> true;
            case VarpCacheInfo.NoSuchVarp ignored -> true;
            case VarpCacheInfo.Unknown ignored -> false;
        };
    }

    private static VarpCacheInfo classifyFilled(MemorySegment info) {
        int filled = info.get(JAVA_INT, STRUCT_SIZE_OFFSET);
        if (info.get(JAVA_INT, VERSION_OFFSET) != VERSION || filled < LAYOUT.byteSize()) {
            return UNKNOWN;
        }
        int rule = info.get(JAVA_INT, DEFAULT_RULE_OFFSET);
        int base = info.get(JAVA_INT, BASE_TYPE_OFFSET);
        boolean hasDefault = rule == DEFAULT_TYPE || rule == DEFAULT_DOMAIN;
        boolean isNumeric = base == BASE_INTEGER || base == BASE_LONG;
        if (!hasDefault || !isNumeric) {
            return EXISTS_DEFAULT_UNKNOWN;
        }
        return new VarpCacheInfo.Default(info.get(JAVA_LONG, DEFAULT_VALUE_OFFSET));
    }

    private static long offsetOf(String field) {
        return LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement(field));
    }
}
