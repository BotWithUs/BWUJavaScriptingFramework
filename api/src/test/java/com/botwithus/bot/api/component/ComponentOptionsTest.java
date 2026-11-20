package com.botwithus.bot.api.component;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.api.model.ComponentOp;
import com.botwithus.bot.api.model.ComponentOps;
import com.botwithus.bot.api.model.ComponentTreeNode;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.model.ItemType;
import com.botwithus.bot.api.util.Interfaces;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Option-name interaction and the option/item filters. The ops put "Deposit-All" on op 4 and
 * the default flag on bit 1 only, so a resolver that used a list position, or a filter that
 * read the wrong mask bit, picks something else.
 */
class ComponentOptionsTest {

    private static final int IFACE = 11;
    private static final int GRID = 5;
    private static final int CLOSE = 23;
    private static final int SLOT_SUB = 3;
    private static final int RUNE_ESSENCE = 1436;
    private static final int COINS = 995;
    private static final int RUNE_CATEGORY = 12;
    private static final int COIN_CATEGORY = 3;
    /** Op flags with op 1 and op 4 transmitting: bits 1 and 4. */
    private static final int SLOT_MASK = (1 << 1) | (1 << 4);
    /** Op flags with only op 2 transmitting. */
    private static final int CLOSE_MASK = 1 << 2;

    private final GameAPI api = mock(GameAPI.class);

    @BeforeEach
    void setUp() {
        when(api.getInterfaceTree(IFACE, 0)).thenReturn(List.of(
                new ComponentTreeNode(component(0, -1, -1, 0), -1),
                new ComponentTreeNode(component(GRID, SLOT_SUB, RUNE_ESSENCE, 27), 0),
                new ComponentTreeNode(component(GRID, SLOT_SUB + 1, COINS, 150_000), 0),
                new ComponentTreeNode(component(CLOSE, -1, -1, 0), 0)));
        when(api.getComponentOps(IFACE, GRID, SLOT_SUB)).thenReturn(new ComponentOps(SLOT_MASK,
                List.of(new ComponentOp(1, "Deposit-1", true), new ComponentOp(4, "Deposit-All", true))));
        when(api.getComponentOps(IFACE, GRID, SLOT_SUB + 1)).thenReturn(ComponentOps.NONE);
        when(api.getComponentOps(IFACE, CLOSE, -1)).thenReturn(new ComponentOps(CLOSE_MASK,
                List.of(new ComponentOp(2, "Close", true))));
        when(api.getComponentOps(IFACE, 0, -1)).thenReturn(ComponentOps.NONE);
        when(api.getItemType(RUNE_ESSENCE)).thenReturn(item(RUNE_ESSENCE, "Rune essence", RUNE_CATEGORY));
        when(api.getItemType(COINS)).thenReturn(item(COINS, "Coins", COIN_CATEGORY));
    }

    @Test
    void interact_byName_sendsTheOpNumberAndTheSlotSub() {
        ComponentNode slot = query().withItemId(RUNE_ESSENCE).first();

        assertTrue(slot.interact("deposit-all"));

        verify(api).queueAction(new GameAction(ActionTypes.COMPONENT, 4, SLOT_SUB,
                Interfaces.componentHash(IFACE, GRID)));
    }

    @Test
    void interact_unlistedOption_queuesNothing() {
        ComponentNode close = query().withId(CLOSE).first();

        assertFalse(close.interact("Deposit-All"));

        verify(api, never()).queueAction(any());
    }

    @Test
    void options_listTheLiveNamesInOpOrder() {
        ComponentNode slot = query().withItemId(RUNE_ESSENCE).first();

        assertEquals(List.of("Deposit-1", "Deposit-All"), slot.options());
        assertTrue(slot.hasOption("DEPOSIT-1"));
        assertFalse(slot.hasOption("Withdraw-1"));
    }

    @Test
    void withOption_keepsOnlyComponentsListingIt_andReadsEachNodeOnce() {
        ComponentQuery q = query().withOption("Close");

        assertEquals(List.of(CLOSE), q.all().stream().map(ComponentNode::componentId).toList());
        assertEquals(1, q.count());
        verify(api, times(1)).getComponentOps(IFACE, CLOSE, -1);
    }

    @Test
    void hasDefaultOption_readsOpOnesMaskBit() {
        List<ComponentNode> kept = query().hasDefaultOption().all();

        assertEquals(1, kept.size());
        assertEquals(RUNE_ESSENCE, kept.getFirst().itemId());
    }

    @Test
    void itemFilters_nameCategoryAndAmount() {
        assertEquals(COINS, query().withItemName("coin").first().itemId());
        assertEquals(RUNE_ESSENCE, query().withItemName(Pattern.compile("^Rune ")).first().itemId());
        assertEquals(RUNE_ESSENCE, query().withItemCategory(RUNE_CATEGORY).first().itemId());
        assertEquals(COINS, query().withItemAmount(100_000, 200_000).first().itemId());
        assertEquals(0, query().withItemAmount(0, 0).count());
    }

    @Test
    void itemNameFilter_looksUpEachItemOnce() {
        ComponentQuery q = query().withItemName("e");
        q.all();
        q.all();

        verify(api, times(1)).getItemType(RUNE_ESSENCE);
        verify(api, never()).getItemType(-1);
        verify(api, never()).getComponentOps(anyInt(), anyInt(), anyInt());
    }

    private ComponentQuery query() {
        return new ComponentQuery(api, IFACE, 0);
    }

    private static Component component(int comp, int sub, int itemId, int amount) {
        return new Component(IFACE, comp, sub, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, "", 0,
                -1, itemId, itemId == -1 ? -1 : amount, List.of());
    }

    private static ItemType item(int id, String name, int category) {
        return new ItemType(id, name, false, true, 0, 0, category, -1, -1, false, List.of(),
                List.of(), Map.of());
    }
}
