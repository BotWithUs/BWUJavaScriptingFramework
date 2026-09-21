package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.model.VarbitType;
import com.botwithus.bot.api.model.VarbitValue;
import com.botwithus.bot.core.rpc.RpcClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The varbit decode contract, against a mocked {@link RpcClient} that answers
 * the batch var RPCs exactly as the producer does.
 *
 * <p>The property under test is that the decode <em>fails closed</em>. A base
 * variable the client has no node for comes back as {@code found == false} with
 * a {@code -1} placeholder in the parallel values array; shifting that
 * placeholder into a varbit's bit range yields all-ones for the range, so a
 * one-bit flag read out of an absent base would report as set. The contract is
 * that it reads {@code 0} — the same answer the engine's own bit extractor
 * gives a defaulted node — while {@code -1} stays reserved for an id the cache
 * has no type config for. Scalar {@link GameAPIImpl#getVarbit(int)} and batch
 * {@link GameAPIImpl#queryVarbits(List)} must give the same answer in every
 * case, which is why the scalar path is routed through the batch RPC: the
 * single-varp RPC carries no {@code found} flag and cannot tell the two apart.
 *
 * <p>The varbit type configs come from an overridden
 * {@link GameAPIImpl#getVarbitType(int)} rather than a real cache: the cache is
 * a final class whose static initialiser loads a native library, so it can be
 * neither constructed nor mocked headlessly. Every other seam is the real
 * code path down to {@code rpc.callSync}.</p>
 */
class GameAPIImplVarbitTest {

    private static final String GET_VARPS = "get_varps";
    private static final String GET_VARCS_INT = "get_varcs_int";

    /**
     * The single-varp RPC. It carries no {@code found} flag, so it must not
     * back a varbit read.
     */
    private static final String GET_VARP = "get_varp";

    private static final int DOMAIN_PLAYER = 0;
    private static final int DOMAIN_CLIENT = 1;

    /** What a varbit read returns for an id the cache has no type config for. */
    private static final int UNKNOWN = -1;

    /** What the producer parks in {@code values[i]} for an id it did not find. */
    private static final int ABSENT_PLACEHOLDER = -1;

    /**
     * A one-bit unlock flag with a non-zero lsb — the shape that makes the
     * fail-open decode visible, since {@code (-1 >>> 17) & 1} is {@code 1}.
     * These are the real ids of a teleport-unlock varbit and its base varp.
     */
    private static final int UNLOCK_VARBIT = 45680;
    private static final int UNLOCK_VARP = 9153;
    private static final int UNLOCK_BIT = 17;
    private static final int UNLOCK_SET_BASE = 1 << UNLOCK_BIT;

    /** A four-bit varbit, so the mask is exercised as well as the shift. */
    private static final int WIDE_VARBIT = 1234;
    private static final int WIDE_VARP = 1000;
    private static final int WIDE_LSB = 4;
    private static final int WIDE_MSB = 7;
    private static final int WIDE_VALUE = 11;

    /**
     * The wide varbit's value in place, with every bit below the range and the
     * first bit above it also set — so a decode that forgot to shift, or
     * forgot to mask, reads back something other than {@link #WIDE_VALUE}.
     */
    private static final int WIDE_BASE =
            (WIDE_VALUE << WIDE_LSB) | ((1 << WIDE_LSB) - 1) | (1 << (WIDE_MSB + 1));

    /** A client-domain varbit, which resolves through {@code get_varcs_int}. */
    private static final int VARC_VARBIT = 77;
    private static final int VARC_VAR = 500;
    private static final int VARC_BIT = 3;
    private static final int VARC_SET_BASE = 1 << VARC_BIT;

    private static final int UNKNOWN_VARBIT = 999_999;

    private final Map<Integer, VarbitType> defs = new LinkedHashMap<>();
    private final Map<Integer, Integer> varps = new LinkedHashMap<>();
    private final Map<Integer, Integer> varcs = new LinkedHashMap<>();

    private RpcClient rpc;
    private GameAPIImpl api;

    @BeforeEach
    void setUp() {
        defs.put(UNLOCK_VARBIT,
                new VarbitType(UNLOCK_VARBIT, UNLOCK_VARP, DOMAIN_PLAYER, UNLOCK_BIT, UNLOCK_BIT));
        defs.put(WIDE_VARBIT,
                new VarbitType(WIDE_VARBIT, WIDE_VARP, DOMAIN_PLAYER, WIDE_LSB, WIDE_MSB));
        defs.put(VARC_VARBIT,
                new VarbitType(VARC_VARBIT, VARC_VAR, DOMAIN_CLIENT, VARC_BIT, VARC_BIT));

        rpc = mock(RpcClient.class);
        when(rpc.callSync(eq(GET_VARPS), anyMap()))
                .thenAnswer(inv -> batchReply(inv.getArgument(1), varps));
        when(rpc.callSync(eq(GET_VARCS_INT), anyMap()))
                .thenAnswer(inv -> batchReply(inv.getArgument(1), varcs));

        api = new GameAPIImpl(rpc, null) {
            @Override
            VarbitType getVarbitType(int varbitId) {
                return defs.get(varbitId);
            }
        };
    }

    /**
     * The producer's reply shape: {@code values} parallel to the requested ids
     * with a {@code -1} placeholder wherever the domain held no node, and
     * {@code found} the parallel flags. Neither array is compacted.
     */
    private static Map<String, Object> batchReply(Map<String, Object> params,
                                                  Map<Integer, Integer> present) {
        List<Integer> ids = MapHelper.toIntList(params.get("ids"));
        List<Integer> values = new ArrayList<>(ids.size());
        List<Boolean> found = new ArrayList<>(ids.size());
        for (int id : ids) {
            Integer v = present.get(id);
            values.add(v != null ? v : ABSENT_PLACEHOLDER);
            found.add(v != null);
        }
        return Map.of("values", values, "found", found);
    }

    private static List<Integer> valuesOf(List<VarbitValue> resolved) {
        return resolved.stream().map(VarbitValue::value).toList();
    }

    private int batchValue(int varbitId) {
        return api.queryVarbits(List.of(varbitId)).getFirst().value();
    }

    @Nested
    @DisplayName("a base variable the domain has no node for reads zero, not all-ones")
    class UnsetBase {

        @Test
        void getVarbit_oneBitFlagOverUnsetVarp_readsZero() {
            // varps left empty: the producer answers found=false with a -1
            // placeholder, and (-1 >>> 17) & 1 == 1. A decode that ignored the
            // flag would report this unlock flag as already unlocked.
            assertEquals(0, api.getVarbit(UNLOCK_VARBIT),
                    "an unset base varp must decode a one-bit flag as clear");
        }

        @Test
        void queryVarbits_oneBitFlagOverUnsetVarp_readsZero() {
            assertEquals(0, batchValue(UNLOCK_VARBIT),
                    "an unset base varp must decode a one-bit flag as clear");
        }

        @Test
        void getVarbit_sameFlagOverSetVarp_readsOne() {
            // Positive control for the two above: the assertion they make is
            // one this same varbit can fail, so a zero is a real answer rather
            // than a stuck one.
            varps.put(UNLOCK_VARP, UNLOCK_SET_BASE);
            assertAll(
                    () -> assertEquals(1, api.getVarbit(UNLOCK_VARBIT)),
                    () -> assertEquals(1, batchValue(UNLOCK_VARBIT)));
        }

        @Test
        void getVarbit_unsetVarcBackedVarbit_readsZero() {
            assertAll(
                    () -> assertEquals(0, api.getVarbit(VARC_VARBIT), "unset varc base"),
                    () -> assertEquals(0, batchValue(VARC_VARBIT), "unset varc base, batched"));
        }
    }

    @Nested
    @DisplayName("-1 stays reserved for an id the cache does not know")
    class UnknownId {

        @Test
        void getVarbit_idWithNoTypeConfig_readsMinusOne() {
            assertEquals(UNKNOWN, api.getVarbit(UNKNOWN_VARBIT));
        }

        @Test
        void queryVarbits_idWithNoTypeConfig_readsMinusOne() {
            assertEquals(UNKNOWN, batchValue(UNKNOWN_VARBIT));
        }

        @Test
        void getVarbit_idWithNoTypeConfig_issuesNoVarRead() {
            // An unknown id resolves entirely from the cache: there is no base
            // variable to ask the producer about, so nothing goes over the pipe.
            api.getVarbit(UNKNOWN_VARBIT);
            verify(rpc, never()).callSync(anyString(), anyMap());
        }
    }

    @Nested
    @DisplayName("a present base decodes its bits")
    class PresentBase {

        @Test
        void getVarbit_multiBitVarbit_shiftsAndMasks() {
            varps.put(WIDE_VARP, WIDE_BASE);
            assertAll(
                    () -> assertEquals(WIDE_VALUE, api.getVarbit(WIDE_VARBIT)),
                    () -> assertEquals(WIDE_VALUE, batchValue(WIDE_VARBIT)));
        }

        @Test
        void getVarbit_varcBackedVarbit_readsFromClientDomain() {
            varcs.put(VARC_VAR, VARC_SET_BASE);
            assertAll(
                    () -> assertEquals(1, api.getVarbit(VARC_VARBIT)),
                    () -> assertEquals(1, batchValue(VARC_VARBIT)));
        }

        @Test
        void getVarbit_presentBaseWithFlagClear_readsZero() {
            varps.put(UNLOCK_VARP, 0);
            assertEquals(0, api.getVarbit(UNLOCK_VARBIT));
        }
    }

    @Nested
    @DisplayName("scalar and batch agree")
    class Agreement {

        @Test
        void queryVarbits_mixedCases_matchesGetVarbitForEveryId() {
            varps.put(WIDE_VARP, WIDE_BASE);
            varcs.put(VARC_VAR, VARC_SET_BASE);
            // UNLOCK_VARP is deliberately absent and UNKNOWN_VARBIT has no def,
            // so the batch spans all three arms of the contract at once.
            List<Integer> ids = List.of(UNLOCK_VARBIT, WIDE_VARBIT, VARC_VARBIT, UNKNOWN_VARBIT);

            List<VarbitValue> batch = api.queryVarbits(ids);

            List<Executable> checks = new ArrayList<>();
            checks.add(() -> assertEquals(ids.size(), batch.size(), "one entry per input id"));
            for (int i = 0; i < ids.size(); i++) {
                int id = ids.get(i);
                VarbitValue entry = batch.get(i);
                checks.add(() -> assertEquals(id, entry.varbitId(), "ids preserved in order"));
                checks.add(() -> assertEquals(api.getVarbit(id), entry.value(),
                        "scalar and batch disagree for varbit " + id));
            }
            assertAll(checks);
        }

        @Test
        void getVarbit_anyKnownId_readsThroughTheBatchRpcOnly() {
            // Pinned explicitly rather than left to the value assertions: an
            // unstubbed mock answers a Map-returning call with an EMPTY map, so
            // a regression to the scalar RPC would decode a base of 0 and agree
            // with the unset-base expectation for the wrong reason.
            api.getVarbit(UNLOCK_VARBIT);

            assertAll(
                    () -> verify(rpc, never()).callSync(eq(GET_VARP), anyMap()),
                    () -> verify(rpc).callSync(eq(GET_VARPS), anyMap()));
        }

        @Test
        void queryVarbits_mixedCases_decodesEachPerContract() {
            varps.put(WIDE_VARP, WIDE_BASE);
            varcs.put(VARC_VAR, VARC_SET_BASE);

            List<VarbitValue> batch = api.queryVarbits(
                    List.of(UNLOCK_VARBIT, WIDE_VARBIT, VARC_VARBIT, UNKNOWN_VARBIT));

            assertEquals(List.of(0, WIDE_VALUE, 1, UNKNOWN), valuesOf(batch));
        }
    }

    @Nested
    @DisplayName("a short or flagless reply drops slots rather than mispairing them")
    class DefensivePairing {

        @Test
        void queryVarbits_valuesShorterThanRequest_keepsSurvivorAndDropsTail() {
            // One entry for a two-id request. Paired by index, slot 0 keeps its
            // own value and slot 1 is absent; a shifted pairing would give the
            // unlock flag the wide base and read it as clear.
            when(rpc.callSync(eq(GET_VARPS), anyMap())).thenReturn(
                    Map.of("values", List.of(UNLOCK_SET_BASE), "found", List.of(true)));

            List<VarbitValue> batch = api.queryVarbits(List.of(UNLOCK_VARBIT, WIDE_VARBIT));

            assertEquals(List.of(1, 0), valuesOf(batch),
                    "the surviving key keeps its own value; the dropped key is absent");
        }

        @Test
        void queryVarbits_foundShorterThanValues_boundsByTheShorterArray() {
            when(rpc.callSync(eq(GET_VARPS), anyMap())).thenReturn(
                    Map.of("values", List.of(UNLOCK_SET_BASE, WIDE_BASE),
                            "found", List.of(true)));

            List<VarbitValue> batch = api.queryVarbits(List.of(UNLOCK_VARBIT, WIDE_VARBIT));

            assertEquals(List.of(1, 0), valuesOf(batch),
                    "a value with no flag beside it is not trusted");
        }

        @Test
        void queryVarbits_replyCarriesNoFoundArray_failsClosed() {
            // A reply with values but no flags cannot be told apart from one
            // whose flags are all false, so every id reads as unset. That is a
            // deliberate choice: the alternative is trusting -1 placeholders.
            when(rpc.callSync(eq(GET_VARPS), anyMap())).thenReturn(
                    Map.of("values", List.of(UNLOCK_SET_BASE, WIDE_BASE)));

            List<VarbitValue> batch = api.queryVarbits(List.of(UNLOCK_VARBIT, WIDE_VARBIT));

            assertEquals(List.of(0, 0), valuesOf(batch));
        }

        @Test
        void queryVarbits_notFoundSlotFollowedByFoundSlot_doesNotCompact() {
            varps.put(WIDE_VARP, WIDE_BASE);
            // UNLOCK_VARP absent, WIDE_VARP present, in that request order. An
            // implementation that filtered the not-found slot out before
            // pairing would hand the unlock flag the wide base and leave the
            // wide varbit with nothing.
            List<VarbitValue> batch = api.queryVarbits(List.of(UNLOCK_VARBIT, WIDE_VARBIT));

            assertEquals(List.of(0, WIDE_VALUE), valuesOf(batch));
        }
    }

    @Nested
    @DisplayName("the raw varp/varc accessors keep their own -1 sentinel")
    class RawAccessorsUnchanged {

        @Test
        void getVarps_unsetEntry_stillReadsMinusOne() {
            varps.put(WIDE_VARP, WIDE_BASE);
            assertEquals(List.of(WIDE_BASE, ABSENT_PLACEHOLDER),
                    api.getVarps(List.of(WIDE_VARP, UNLOCK_VARP)));
        }

        @Test
        void getVarcInts_unsetEntry_stillReadsMinusOne() {
            varcs.put(VARC_VAR, VARC_SET_BASE);
            assertEquals(List.of(VARC_SET_BASE, ABSENT_PLACEHOLDER),
                    api.getVarcInts(List.of(VARC_VAR, VARC_VAR + 1)));
        }
    }
}
