package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.component.ComponentNode;
import com.botwithus.bot.api.component.ComponentTree;
import com.botwithus.bot.api.component.ComponentType;
import com.botwithus.bot.api.component.Components;
import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.api.model.ComponentTreeNode;
import com.botwithus.bot.api.util.Interfaces;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.RpcClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live smoke test for the scripter component-query API ({@code api.components()})
 * end-to-end against a running, injected client. Stands up the real
 * {@code PipeClient → RpcClient → GameAPIImpl} stack — the same path a BotScript
 * takes — and exercises the fluent query, the materialized {@link ComponentTree}
 * + {@link ComponentNode} navigation, the stable {@link ComponentType} decode,
 * and the {@code get_interface_tree} primitive over the actual pipe.
 *
 * <p>Disabled by default — opt in with {@code -Dbotwithus.smoke.live=true}
 * (the {@code :core:liveSmokeTest} Gradle task sets it). Requires the rebuilt
 * NXTLibrary DLL injected into a running client with the <b>backpack (1473)
 * open</b>; the class self-skips (assumption) when no pipe is visible or the
 * backpack isn't loaded. Read-only — no {@code interact} is fired.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "botwithus.smoke.live", matches = "true")
class LiveComponentApiSmokeTest {

    private static final Logger log = LoggerFactory.getLogger(LiveComponentApiSmokeTest.class);
    private static final int BACKPACK = Interfaces.BACKPACK;
    private static final int LIMIT_CAP = 3;

    private RpcClient rpc;
    private GameAPI api;
    private ComponentTree tree;

    @BeforeAll
    void connect() {
        List<String> pipes = PipeClient.scanPipes(PipeClient.NAME_PREFIX);
        Assumptions.assumeFalse(pipes.isEmpty(),
                "no BotWithUs_<pid> pipe visible — inject the DLL into a running client");

        PipeClient pipe = new PipeClient(pipes.getFirst());
        rpc = new RpcClient(pipe);
        api = new GameAPIImpl(rpc);

        tree = api.components().in(BACKPACK).tree();
        Assumptions.assumeTrue(tree.root() != null,
                "backpack (1473) not loaded — open it and rerun");
        log.info("connected to {}; backpack tree has {} nodes", pipe.getPipePath(), tree.nodes().size());
    }

    @AfterAll
    void disconnect() {
        if (rpc != null) {
            rpc.close();
        }
    }

    @Test
    void isOpenReportsBackpackLoaded() {
        assertTrue(api.components().isOpen(BACKPACK),
                "isOpen(BACKPACK) must be true with the backpack open");
    }

    @Test
    void rootIsALayerWithNoParent() {
        ComponentNode root = tree.root();
        assertEquals(BACKPACK, root.interfaceId());
        assertEquals(0, root.componentId());
        assertEquals(ComponentType.LAYER, root.type(), "the backpack root is a LAYER container");
        assertTrue(root.isRoot());
        assertNull(root.parent());
        assertSame(root, tree.node(BACKPACK, 0), "node() lookup resolves the root");
    }

    @Test
    void treeLinkageIsConsistent() {
        List<ComponentNode> nodes = tree.nodes();
        assertFalse(nodes.isEmpty());
        ComponentNode root = tree.root();
        for (ComponentNode node : nodes) {
            if (node != root) {
                assertNotNull(node.parent(),
                        "non-root node " + node.componentId() + " must have a parent");
            }
        }
        assertEquals(nodes.size() - 1, root.descendants().size(),
                "every non-root node descends from the root");
        for (ComponentNode child : root.children()) {
            assertSame(root, child.parent(), "child.parent() points back at the root");
        }
    }

    @Test
    void categoriesDecodeFromStableWireCode() {
        for (ComponentNode node : tree.nodes()) {
            assertNotNull(node.type(), "every node decodes to a ComponentType");
            assertEquals(ComponentType.fromCode(node.data().category()), node.type(),
                    "node.type() reflects the wire category code");
        }
        long layers = tree.nodes().stream().filter(n -> n.type() == ComponentType.LAYER).count();
        assertTrue(layers >= 1, "the backpack tree has at least one LAYER");
        log.info("category histogram: {}", categoryHistogram());
    }

    @Test
    void fluentFiltersNarrowResults() {
        Components ui = api.components();
        int total = tree.nodes().size();

        int visible = ui.in(BACKPACK).visible().count();
        int hidden = ui.in(BACKPACK).hidden().count();
        assertEquals(total, visible + hidden, "visible() and hidden() partition the tree");

        assertTrue(ui.in(BACKPACK).limit(LIMIT_CAP).all().size() <= LIMIT_CAP, "limit caps results");

        int knownComp = tree.root().children().getFirst().componentId();
        ComponentNode byId = ui.in(BACKPACK).withId(knownComp).first();
        assertNotNull(byId, "withId finds a known child");
        assertEquals(knownComp, byId.componentId());

        List<ComponentNode> sprites = ui.in(BACKPACK).withType(ComponentType.SPRITE).all();
        for (ComponentNode sprite : sprites) {
            assertEquals(ComponentType.SPRITE, sprite.type());
        }
        log.info("withType(SPRITE) -> {}", sprites);
    }

    @Test
    void getReturnsDetachedButNavigableNode() {
        Components ui = api.components();
        ComponentNode detached = ui.get(BACKPACK, 0);
        assertNotNull(detached, "get(1473, 0) resolves");
        assertEquals(0, detached.componentId());
        assertEquals(ComponentType.LAYER, detached.type());
        assertNull(detached.parent(), "a get() node is detached — no parent");
        // children() on a detached node does a one-shot subtree fetch.
        assertEquals(tree.root().children().size(), detached.children().size(),
                "detached children() matches the attached root's children");
    }

    @Test
    void pickAtFarOffscreenReturnsNull() {
        // No on-screen geometry guess required — we just exercise the
        // wire roundtrip and the iface == -1 sentinel decode. Any HWND
        // would (-32000, -32000) below its client rect, so the producer
        // walks every open iface, finds no hit, and returns iface = -1.
        ComponentNode miss = api.components().pickAt(-32000, -32000);
        assertNull(miss, "pickAt far off-screen must decode the producer's iface=-1 sentinel as null");
    }

    @Test
    void lowLevelTreeMatchesFacade() {
        List<ComponentTreeNode> raw = api.getInterfaceTree(BACKPACK, 0);
        assertEquals(tree.nodes().size(), raw.size(), "facade tree size matches the raw primitive");
        assertEquals(-1, raw.getFirst().parentIndex(), "the first raw node is the root (parent -1)");

        Component rootComp = api.getComponent(BACKPACK, 0);
        assertNotNull(rootComp);
        assertEquals(tree.root().data().category(), rootComp.category(),
                "get_component and get_interface_tree agree on the root category");
    }

    private Map<ComponentType, Long> categoryHistogram() {
        EnumMap<ComponentType, Long> histogram = new EnumMap<>(ComponentType.class);
        for (ComponentNode node : tree.nodes()) {
            histogram.merge(node.type(), 1L, Long::sum);
        }
        return histogram;
    }
}
