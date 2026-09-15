package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.component.ComponentNode;
import com.botwithus.bot.api.diag.StubGuard;
import com.botwithus.bot.api.gameval.GamevalEntry;
import com.botwithus.bot.api.gameval.GamevalIndex;
import com.botwithus.bot.api.gameval.GamevalType;
import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.api.model.ComponentTreeNode;
import com.botwithus.bot.core.rpc.RpcClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Gameval filtering on {@link com.botwithus.bot.api.component.ComponentQuery} —
 * the component half of "query by gameval name". Distinct from
 * {@code Components.get(name)}, which addresses one detached component: a query
 * filter composes with the other filters and yields tree-attached nodes, so
 * {@code parent()} / {@code children()} still work.
 */
class ComponentQueryGamevalTest {

    private static final int BANK = 517;
    private static final int SUB_INTERFACE = 518;
    private static final int INV_BUTTON = 39;
    private static final int WORN_BUTTON = 40;

    private GameAPIImpl build(List<ComponentTreeNode> tree) {
        RpcClient rpc = mock(RpcClient.class);
        GamevalIndex gamevals = stubIndex(Map.of(
                "BANK__BANK_INV_BUTTON", packed(BANK, INV_BUTTON),
                "BANK__BANK_WORN_BUTTON", packed(BANK, WORN_BUTTON),
                // Same component id as INV_BUTTON but on a mounted sub-interface:
                // the pair, not the component half, is what must match.
                "BANK_SUB__DECOY", packed(SUB_INTERFACE, INV_BUTTON)));
        return new GameAPIImpl(rpc, null, () -> null, new StubGuard(), event -> {}, gamevals) {
            @Override public List<ComponentTreeNode> getInterfaceTree(int iface, int comp) {
                return tree;
            }
        };
    }

    @Test
    void filtersTreeNodesByGamevalName() {
        GameAPIImpl api = build(List.of(
                node(BANK, 0, "root", -1),
                node(BANK, INV_BUTTON, "Inventory", 0),
                node(BANK, WORN_BUTTON, "Worn", 0)));

        ComponentNode inv = api.components().in(BANK)
                .withGameval("BANK__BANK_INV_BUTTON")
                .first();
        assertNotNull(inv);
        assertEquals(INV_BUTTON, inv.componentId());
        assertEquals("Inventory", inv.text());

        // Attached, not detached — the whole point of filtering over addressing.
        assertNotNull(inv.parent());
        assertEquals(0, inv.parent().componentId());
    }

    @Test
    void matchesAnyOfSeveralNames() {
        GameAPIImpl api = build(List.of(
                node(BANK, 0, "root", -1),
                node(BANK, INV_BUTTON, "Inventory", 0),
                node(BANK, WORN_BUTTON, "Worn", 0)));

        assertEquals(2, api.components().in(BANK)
                .withGameval("BANK__BANK_INV_BUTTON", "BANK__BANK_WORN_BUTTON")
                .count());
    }

    @Test
    void matchesTheInterfaceAndComponentPairNotJustTheComponentId() {
        // A materialized tree is cross-mount aware: a mounted sub-interface can
        // contribute a node with the same component id. Filtering on the id
        // alone would return both.
        GameAPIImpl api = build(List.of(
                node(BANK, 0, "root", -1),
                node(BANK, INV_BUTTON, "Inventory", 0),
                node(SUB_INTERFACE, INV_BUTTON, "Decoy", 0)));

        List<ComponentNode> hits = api.components().in(BANK)
                .withGameval("BANK__BANK_INV_BUTTON")
                .all();
        assertEquals(1, hits.size());
        assertEquals(BANK, hits.getFirst().interfaceId());
        assertEquals("Inventory", hits.getFirst().text());

        // withId, by contrast, is component-id only and sees both.
        assertEquals(2, api.components().in(BANK).withId(INV_BUTTON).count());
    }

    @Test
    void composesWithTheOtherFilters() {
        GameAPIImpl api = build(List.of(
                node(BANK, 0, "root", -1),
                hiddenNode(BANK, INV_BUTTON, "Inventory", 0),
                node(BANK, WORN_BUTTON, "Worn", 0)));

        assertEquals(0, api.components().in(BANK)
                .withGameval("BANK__BANK_INV_BUTTON").visible().count());
        assertEquals(1, api.components().in(BANK)
                .withGameval("BANK__BANK_INV_BUTTON").hidden().count());
        assertTrue(api.components().in(BANK)
                .withGameval("BANK__BANK_WORN_BUTTON").visible().exists());
    }

    @Test
    void unresolvedNameMatchesNothingRatherThanEverything() {
        GameAPIImpl api = build(List.of(
                node(BANK, 0, "root", -1),
                node(BANK, INV_BUTTON, "Inventory", 0)));

        assertEquals(0, api.components().in(BANK).withGameval("NOT_A_REAL_NAME").count());
        assertNull(api.components().in(BANK).withGameval("NOT_A_REAL_NAME").first());
        // A partially-resolvable set keeps the names that did resolve.
        assertEquals(1, api.components().in(BANK)
                .withGameval("NOT_A_REAL_NAME", "BANK__BANK_INV_BUTTON").count());
    }

    @Test
    void resolvesNothingWithoutAnIndex() {
        RpcClient rpc = mock(RpcClient.class);
        List<ComponentTreeNode> tree = List.of(
                node(BANK, 0, "root", -1), node(BANK, INV_BUTTON, "Inventory", 0));
        GameAPIImpl api = new GameAPIImpl(rpc, null, () -> null, new StubGuard(), event -> {},
                GamevalIndex.empty()) {
            @Override public List<ComponentTreeNode> getInterfaceTree(int iface, int comp) {
                return tree;
            }
        };
        assertEquals(0, api.components().in(BANK).withGameval("BANK__BANK_INV_BUTTON").count());
    }

    @Test
    void emptyGamevalListMatchesNothing() {
        GameAPIImpl api = build(List.of(
                node(BANK, 0, "root", -1),
                node(BANK, INV_BUTTON, "Inventory", 0)));
        assertEquals(0, api.components().in(BANK).withGameval().count());
    }

    // ---------------------------------------------------------------- helpers

    /** Packs a gameval component id the way the cache does. */
    private static int packed(int interfaceId, int componentId) {
        return (interfaceId << GamevalIndex.INTERFACE_ID_SHIFT) | componentId;
    }

    private static ComponentTreeNode node(int iface, int comp, String text, int parentIndex) {
        return treeNode(iface, comp, text, parentIndex, 0);
    }

    private static ComponentTreeNode hiddenNode(int iface, int comp, String text, int parentIndex) {
        return treeNode(iface, comp, text, parentIndex, 1);
    }

    private static ComponentTreeNode treeNode(int iface, int comp, String text,
                                              int parentIndex, int hidden) {
        return new ComponentTreeNode(new Component(
                iface, comp, -1, 8, 1,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                text, hidden, -1, -1, -1, List.of()), parentIndex);
    }

    private static GamevalIndex stubIndex(Map<String, Integer> componentsByName) {
        return new GamevalIndex() {
            @Override public OptionalInt id(GamevalType type, String gameval) {
                if (type != GamevalType.COMPONENT) {
                    return OptionalInt.empty();
                }
                Integer found = componentsByName.get(gameval);
                return found == null ? OptionalInt.empty() : OptionalInt.of(found);
            }

            @Override public Optional<String> gameval(GamevalType type, int id) {
                return Optional.empty();
            }

            @Override public List<GamevalEntry> startingWith(GamevalType t, String p, int n) {
                return List.of();
            }

            @Override public boolean isAvailable() { return true; }

            @Override public Optional<String> meta(String key) { return Optional.empty(); }
        };
    }
}
