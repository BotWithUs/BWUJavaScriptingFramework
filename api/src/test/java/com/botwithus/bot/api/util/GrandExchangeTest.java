package com.botwithus.bot.api.util;

import com.botwithus.bot.api.gameval.GamevalEntry;
import com.botwithus.bot.api.gameval.GamevalIndex;
import com.botwithus.bot.api.gameval.GamevalRef;
import com.botwithus.bot.api.gameval.GamevalType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@link GrandExchange}'s button refs to the live 951 layout. The names and
 * ids below were read from a gameval index built from the live 951-1 cache, and
 * agree with the cache's own interface definitions: 651 has six components, of
 * which 0 is "To Backpack" and 1 is "To Bank", and each offer slot's
 * "Abort Offer" sprite sits at 105:25 plus 14 per slot.
 */
class GrandExchangeTest {

    private static final int COLLECT_ALL = 651;
    private static final int STOCKMARKET = 105;

    /** Every row of the live index these refs depend on, by name. */
    private static final Map<String, Integer> LIVE_951 = Map.ofEntries(
            Map.entry("STOCKMARKET_COLLECTALL__SEND_TO_INV", Interfaces.componentHash(COLLECT_ALL, 0)),
            Map.entry("STOCKMARKET_COLLECTALL__SEND_TO_BANK", Interfaces.componentHash(COLLECT_ALL, 1)),
            Map.entry("STOCKMARKET__ABORT0", Interfaces.componentHash(STOCKMARKET, 25)),
            Map.entry("STOCKMARKET__ABORT1", Interfaces.componentHash(STOCKMARKET, 39)),
            Map.entry("STOCKMARKET__ABORT2", Interfaces.componentHash(STOCKMARKET, 53)),
            Map.entry("STOCKMARKET__ABORT3", Interfaces.componentHash(STOCKMARKET, 67)),
            Map.entry("STOCKMARKET__ABORT4", Interfaces.componentHash(STOCKMARKET, 81)),
            Map.entry("STOCKMARKET__ABORT5", Interfaces.componentHash(STOCKMARKET, 95)),
            Map.entry("STOCKMARKET__ABORT6", Interfaces.componentHash(STOCKMARKET, 109)),
            Map.entry("STOCKMARKET__ABORT7", Interfaces.componentHash(STOCKMARKET, 123)));

    private static final GamevalIndex LIVE_INDEX = new ComponentIndex(LIVE_951);

    @Test
    void collectToInventory_targetsSendToInv() {
        assertRef("STOCKMARKET_COLLECTALL__SEND_TO_INV", COLLECT_ALL, 0, GrandExchange.COLLECT_TO_INVENTORY);
    }

    @Test
    void collectToBank_targetsSendToBank() {
        assertRef("STOCKMARKET_COLLECTALL__SEND_TO_BANK", COLLECT_ALL, 1, GrandExchange.COLLECT_TO_BANK);
    }

    @Test
    void abortButtons_oneRefPerSlot_atTwentyFivePlusFourteenPerSlot() {
        List<Integer> liveComponents = List.of(25, 39, 53, 67, 81, 95, 109, 123);
        assertEquals(GrandExchange.SLOT_COUNT, GrandExchange.ABORT_BUTTONS.size());
        for (int slot = 0; slot < GrandExchange.SLOT_COUNT; slot++) {
            assertRef("STOCKMARKET__ABORT" + slot, STOCKMARKET, liveComponents.get(slot),
                    GrandExchange.ABORT_BUTTONS.get(slot));
        }
    }

    @Test
    void everyRef_resolvesTheSameWithOrWithoutTheLiveIndex() {
        for (GamevalRef ref : allRefs()) {
            assertEquals(ref.resolve(LIVE_INDEX), ref.resolve(GamevalIndex.empty()), ref.gameval());
        }
    }

    @Test
    void everyRef_isNamedInTheLiveIndex() {
        for (GamevalRef ref : allRefs()) {
            assertEquals(OptionalInt.of(ref.fallbackId()), LIVE_INDEX.id(GamevalType.COMPONENT, ref.gameval()),
                    ref.gameval());
        }
    }

    private static List<GamevalRef> allRefs() {
        return Stream.concat(
                        Stream.of(GrandExchange.COLLECT_TO_INVENTORY, GrandExchange.COLLECT_TO_BANK),
                        GrandExchange.ABORT_BUTTONS.stream())
                .toList();
    }

    private static void assertRef(String name, int interfaceId, int componentId, GamevalRef ref) {
        assertEquals(GamevalType.COMPONENT, ref.type(), name);
        assertEquals(name, ref.gameval());
        assertEquals(Interfaces.componentHash(interfaceId, componentId), ref.fallbackId(), name);
    }

    /** A component-only index over a fixed name-to-id table. */
    private record ComponentIndex(Map<String, Integer> ids) implements GamevalIndex {

        @Override
        public OptionalInt id(GamevalType type, String gameval) {
            Integer id = type == GamevalType.COMPONENT ? ids.get(gameval) : null;
            return id == null ? OptionalInt.empty() : OptionalInt.of(id);
        }

        @Override
        public Optional<String> gameval(GamevalType type, int id) {
            return Optional.empty();
        }

        @Override
        public List<GamevalEntry> startingWith(GamevalType type, String prefix, int limit) {
            return List.of();
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public Optional<String> meta(String key) {
            return Optional.empty();
        }
    }
}
