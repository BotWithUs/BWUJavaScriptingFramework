package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.model.VarKind;
import com.botwithus.bot.api.model.VarbitRead;
import com.botwithus.bot.api.model.VarbitType;
import com.botwithus.bot.api.model.VarpRead;
import com.botwithus.bot.api.model.VarpState;
import com.botwithus.bot.core.cache.VarpCacheInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.IntFunction;

/**
 * Composes the agent's per-id varp state with the cache's varp definitions into
 * {@link VarpRead}s and {@link VarbitRead}s.
 *
 * <p><b>Predicate and message.</b> The agent's {@code states[]} (through
 * {@link VarBatchReply.Presence}) decides; the value is only ever the message. PRESENT is
 * {@link VarpState#SET SET} with the stored value. ABSENT is resolved against the cache: a
 * known default gives {@link VarpState#DEFAULT_NOT_SET_CLIENTSIDE} with that default, "no
 * such varp" gives {@link VarpState#NO_SUCH_VARP}, and a cache that cannot say gives
 * DEFAULT_NOT_SET_CLIENTSIDE with the fallback {@code 0} and {@code defaultVerified} false.
 * UNAVAILABLE, including a slot the reply did not answer, is
 * {@link VarpState#UNAVAILABLE}.</p>
 *
 * <p>A varbit takes its base's state and decodes the base's value, which for an unset base
 * is its default: for a varp whose default is {@code -1}, every bit of the range reads as
 * set, because that is what the game reads. A varbit over a client variable takes presence
 * from the {@code found[]} flags alone, since the agent sends no state for varcs: found is
 * SET, not found is at its default {@code 0}, unverified.</p>
 *
 * <p>Requests are split at {@link #BATCH_CAP} ids, the agent's per-request limit, so no id is
 * ever silently dropped.</p>
 */
final class VarpReader {

    /** The agent's cap on ids per batch request; ids past it are dropped by the agent. */
    static final int BATCH_CAP = 256;

    /** What a default reads when the cache cannot supply one: the engine's int default. */
    static final int FALLBACK_DEFAULT = 0;

    /** {@code VarbitType.domainType} for a base in the player-varp domain. */
    private static final int DOMAIN_PLAYER = 0;
    /** A varbit packs into [lsb, msb] of a 32-bit int; a wider range is malformed. */
    private static final int MAX_BIT_WIDTH = 32;

    /** The wire's {@code kind} numbers. */
    private static final int KIND_INT = 0;
    private static final int KIND_LONG = 1;
    private static final int KIND_STRING = 2;

    private static final String GET_VARPS = "get_varps";
    private static final String GET_VARCS_INT = "get_varcs_int";
    private static final String IDS = "ids";

    private final BiFunction<String, Map<String, Object>, Map<String, Object>> call;
    private final IntFunction<VarbitType> varbitTypes;
    private final IntFunction<VarpCacheInfo> varpInfo;

    /**
     * @param call        issues one RPC and returns its reply
     * @param varbitTypes the cache's varbit definition for an id, or {@code null}
     * @param varpInfo    the cache's varp definition for an id
     */
    VarpReader(BiFunction<String, Map<String, Object>, Map<String, Object>> call,
               IntFunction<VarbitType> varbitTypes, IntFunction<VarpCacheInfo> varpInfo) {
        this.call = call;
        this.varbitTypes = varbitTypes;
        this.varpInfo = varpInfo;
    }

    // ------------------------------------------------------------------ varps

    List<VarpRead> readVarps(List<Integer> ids) {
        List<VarpRead> out = new ArrayList<>(ids.size());
        for (List<Integer> chunk : chunks(ids)) {
            VarBatchReply reply = VarBatchReply.parse(call.apply(GET_VARPS, Map.of(IDS, chunk)));
            for (int i = 0; i < chunk.size(); i++) {
                out.add(toRead(chunk.get(i), reply, i));
            }
        }
        return out;
    }

    private VarpRead toRead(int id, VarBatchReply reply, int slot) {
        return switch (reply.presenceAt(slot)) {
            case PRESENT -> new VarpRead(id, VarpState.SET, reply.value(slot),
                    reply.value64(slot), kindOf(reply.kindAt(slot)), true);
            case ABSENT -> atDefault(id);
            case UNAVAILABLE -> unavailable(id);
        };
    }

    private VarpRead atDefault(int id) {
        return switch (varpInfo.apply(id)) {
            case VarpCacheInfo.Default known -> new VarpRead(id, VarpState.DEFAULT_NOT_SET_CLIENTSIDE,
                    (int) known.defaultValue(), known.defaultValue(), VarKind.UNKNOWN, true);
            case VarpCacheInfo.NoSuchVarp ignored -> new VarpRead(id, VarpState.NO_SUCH_VARP,
                    VarpRead.NO_VALUE, VarpRead.NO_VALUE, VarKind.UNKNOWN, true);
            case VarpCacheInfo.ExistsDefaultUnknown ignored -> unverifiedDefault(id);
            case VarpCacheInfo.Unknown ignored -> unverifiedDefault(id);
        };
    }

    private static VarpRead unverifiedDefault(int id) {
        return new VarpRead(id, VarpState.DEFAULT_NOT_SET_CLIENTSIDE,
                FALLBACK_DEFAULT, FALLBACK_DEFAULT, VarKind.UNKNOWN, false);
    }

    private static VarpRead unavailable(int id) {
        return new VarpRead(id, VarpState.UNAVAILABLE,
                VarpRead.NO_VALUE, VarpRead.NO_VALUE, VarKind.UNKNOWN, true);
    }

    private static VarKind kindOf(int wireKind) {
        return switch (wireKind) {
            case KIND_INT -> VarKind.INT;
            case KIND_LONG -> VarKind.LONG;
            case KIND_STRING -> VarKind.STRING;
            default -> VarKind.UNKNOWN;
        };
    }

    // ------------------------------------------------------------------ varbits

    List<VarbitRead> readVarbits(List<Integer> ids) {
        List<VarbitType> defs = new ArrayList<>(ids.size());
        LinkedHashSet<Integer> varpBases = new LinkedHashSet<>();
        LinkedHashSet<Integer> varcBases = new LinkedHashSet<>();
        for (int id : ids) {
            VarbitType def = varbitTypes.apply(id);
            defs.add(def);
            if (def != null) {
                (def.domainType() == DOMAIN_PLAYER ? varpBases : varcBases).add(def.varId());
            }
        }
        Map<Integer, VarpRead> varps = byId(readVarps(List.copyOf(varpBases)));
        Map<Integer, VarpRead> varcs = byId(readVarcBases(List.copyOf(varcBases)));
        List<VarbitRead> out = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            out.add(decode(ids.get(i), defs.get(i), varps, varcs));
        }
        return out;
    }

    private static VarbitRead decode(int id, VarbitType def, Map<Integer, VarpRead> varps,
                                     Map<Integer, VarpRead> varcs) {
        if (def == null || !hasValidRange(def)) {
            return new VarbitRead(id, VarpState.NO_SUCH_VARP, VarpRead.NO_VALUE, true);
        }
        VarpRead base = (def.domainType() == DOMAIN_PLAYER ? varps : varcs).get(def.varId());
        if (base == null || !base.hasValue()) {
            VarpState state = base == null ? VarpState.UNAVAILABLE : base.state();
            return new VarbitRead(id, state, VarpRead.NO_VALUE, true);
        }
        return new VarbitRead(id, base.state(), bits(def, base.value()), base.defaultVerified());
    }

    /** Client-variable bases: presence from {@code found[]} only, default 0 unverified. */
    private List<VarpRead> readVarcBases(List<Integer> ids) {
        List<VarpRead> out = new ArrayList<>(ids.size());
        for (List<Integer> chunk : chunks(ids)) {
            VarBatchReply reply = VarBatchReply.parse(call.apply(GET_VARCS_INT, Map.of(IDS, chunk)));
            for (int i = 0; i < chunk.size(); i++) {
                int id = chunk.get(i);
                out.add(switch (reply.presenceAt(i)) {
                    case PRESENT -> new VarpRead(id, VarpState.SET, reply.value(i),
                            reply.value(i), VarKind.INT, true);
                    case ABSENT -> unverifiedDefault(id);
                    case UNAVAILABLE -> unavailable(id);
                });
            }
        }
        return out;
    }

    private static boolean hasValidRange(VarbitType def) {
        int width = def.msb() - def.lsb() + 1;
        return width > 0 && width <= MAX_BIT_WIDTH;
    }

    /** Bits {@code [lsb, msb]} of {@code base}, shifted down; a logical shift, never signed. */
    static int bits(VarbitType def, int base) {
        int width = def.msb() - def.lsb() + 1;
        int mask = width == MAX_BIT_WIDTH ? -1 : (1 << width) - 1;
        return (base >>> def.lsb()) & mask;
    }

    private static Map<Integer, VarpRead> byId(List<VarpRead> reads) {
        Map<Integer, VarpRead> out = new LinkedHashMap<>(reads.size());
        for (VarpRead r : reads) {
            out.put(r.id(), r);
        }
        return out;
    }

    private static List<List<Integer>> chunks(List<Integer> ids) {
        List<List<Integer>> out = new ArrayList<>();
        for (int from = 0; from < ids.size(); from += BATCH_CAP) {
            out.add(List.copyOf(ids.subList(from, Math.min(ids.size(), from + BATCH_CAP))));
        }
        return out;
    }
}
