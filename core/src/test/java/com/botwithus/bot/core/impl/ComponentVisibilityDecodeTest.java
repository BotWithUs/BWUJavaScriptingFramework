package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.component.ComponentNode;
import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.api.model.ComponentRef;
import com.botwithus.bot.api.model.ComponentScroll;
import com.botwithus.bot.api.model.ComponentTreeNode;
import com.botwithus.bot.api.model.ComponentVisibility;
import com.botwithus.bot.core.rpc.RpcClient;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The v24 component map keys ({@code visible}, {@code visible_global}, {@code slot_flags},
 * {@code parent_global}, {@code scrollable}, {@code scroll_*}) and the redefined {@code hidden},
 * through {@link GameAPIImpl}'s decoder and the {@code components()} query.
 */
class ComponentVisibilityDecodeTest {

    private static final int RUN_ORB = 1465;
    private static final int RUN_ORB_TEXT = 20;
    private static final int HOST_IFACE = 1477;
    private static final int HOST_COMP = 857;
    private static final int PACKED_HOST = (HOST_IFACE << 16) | HOST_COMP;
    private static final int LAYER = 4;
    private static final int SCROLL_Y = 120;
    private static final int CONTENT_H = 900;
    private static final int CONTENT_W = 300;

    @Test
    void v24Keys_decodeIntoVisibilityScrollAndParent() {
        Map<String, Object> map = componentMap(RUN_ORB, RUN_ORB_TEXT, 0, true, true, PACKED_HOST);
        map.put("scrollable", true);
        map.put("scroll_x", 0);
        map.put("scroll_y", SCROLL_Y);
        map.put("scroll_w", CONTENT_W);
        map.put("scroll_h", CONTENT_H);

        Component c = apiReturning(map).getComponent(RUN_ORB, RUN_ORB_TEXT);

        assertTrue(c.isVisible());
        assertTrue(c.isVisibleGlobal());
        assertFalse(c.isHidden(), "the run-orb text is shown; v23 read hidden=1 here");
        assertEquals(0, c.visibility().slotFlags());
        assertEquals(new ComponentRef(HOST_IFACE, HOST_COMP), c.parentGlobalRef());
        assertEquals(new ComponentScroll(true, 0, SCROLL_Y, CONTENT_W, CONTENT_H), c.scroll());
    }

    @Test
    void nonLayer_reportsNoScrollAndNoParent() {
        Map<String, Object> map = componentMap(RUN_ORB, RUN_ORB_TEXT, 1, false, false, Component.NO_PARENT);
        map.put("scrollable", false);

        Component c = apiReturning(map).getComponent(RUN_ORB, RUN_ORB_TEXT);

        assertTrue(c.isHidden());
        assertFalse(c.isVisibleGlobal());
        assertNull(c.parentGlobalRef());
        assertEquals(ComponentScroll.NONE, c.scroll());
    }

    /**
     * An agent that predates the keys sends only {@code hidden}. Reading the absent booleans as
     * false would make every component off screen; the decoder derives them from hidden instead.
     */
    @Test
    void absentVisibilityKeys_fallBackToHidden_ratherThanReadingFalse() {
        Map<String, Object> legacy = new HashMap<>(componentMap(RUN_ORB, RUN_ORB_TEXT, -1, true, true, 0));
        legacy.remove("visible");
        legacy.remove("visible_global");
        legacy.remove("slot_flags");
        legacy.remove("parent_global");

        Component c = apiReturning(legacy).getComponent(RUN_ORB, RUN_ORB_TEXT);

        assertTrue(c.isVisibleGlobal(), "hidden=-1 from an old agent stays on screen");
        assertEquals(ComponentVisibility.NO_SLOT, c.visibility().slotFlags());
        assertNull(c.parentGlobalRef());
    }

    /**
     * The behaviour change: a child whose own flag says visible, under a hidden layer, is not on
     * screen. {@code visible()} drops it and {@code hidden()} keeps it, and the two partition the
     * tree.
     */
    @Test
    void visibleFilter_usesVisibleGlobal_andPartitionsTheTreeWithHidden() {
        GameAPIImpl api = apiWithTree(List.of(
                treeNode(0, -1, true, true),     // root, on screen
                treeNode(1, 0, false, false),    // layer hidden by its own flag
                treeNode(2, 1, true, false),     // own flag visible, but under the hidden layer
                treeNode(3, 0, true, true)));    // sibling, on screen

        List<ComponentNode> visible = api.components().in(RUN_ORB).visible().all();
        List<ComponentNode> hidden = api.components().in(RUN_ORB).hidden().all();

        assertEquals(List.of(0, 3), visible.stream().map(ComponentNode::componentId).toList());
        assertEquals(List.of(1, 2), hidden.stream().map(ComponentNode::componentId).toList());
        ComponentNode child = hidden.get(1);
        assertTrue(child.isVisible(), "its own flag says visible");
        assertFalse(child.isHidden(), "and isHidden() still reports the own flag only");
        assertFalse(child.isVisibleGlobal());
        assertEquals(1, api.components().in(RUN_ORB).filter(ComponentNode::isVisible)
                .withId(2).count(), "the own-flag filter is one predicate away");
    }

    @Test
    void componentNode_exposesScrollAndParentGlobal() {
        Component layer = new Component(RUN_ORB, LAYER, -1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                "", 0, -1, -1, -1, List.of(), PACKED_HOST,
                new ComponentVisibility(true, true, 0),
                new ComponentScroll(true, 0, SCROLL_Y, CONTENT_W, CONTENT_H));
        GameAPIImpl api = apiWithTree(List.of(new ComponentTreeNode(layer, -1)));

        ComponentNode node = api.components().in(RUN_ORB).first();

        assertTrue(node.isScrollable());
        assertEquals(SCROLL_Y, node.scrollY());
        assertEquals(CONTENT_H, node.scrollHeight());
        assertEquals(CONTENT_W, node.scrollWidth());
        assertEquals(0, node.scrollX());
        assertEquals(new ComponentRef(HOST_IFACE, HOST_COMP), node.parentGlobal());
        assertEquals(0, node.slotFlags());
    }

    private static GameAPIImpl apiReturning(Map<String, Object> reply) {
        RpcClient rpc = mock(RpcClient.class);
        when(rpc.callSync(eq("get_component"), anyMap())).thenReturn(reply);
        return new GameAPIImpl(rpc);
    }

    private static GameAPIImpl apiWithTree(List<ComponentTreeNode> tree) {
        return new GameAPIImpl(mock(RpcClient.class)) {
            @Override public List<ComponentTreeNode> getInterfaceTree(int iface, int comp) {
                return tree;
            }
        };
    }

    private static ComponentTreeNode treeNode(int comp, int parentIndex, boolean isVisible,
                                              boolean isVisibleGlobal) {
        return new ComponentTreeNode(new Component(RUN_ORB, comp, -1, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                "", isVisible ? 0 : 1, -1, -1, -1, List.of(), Component.NO_PARENT,
                new ComponentVisibility(isVisible, isVisibleGlobal, isVisible ? 0 : 1),
                ComponentScroll.NONE), parentIndex);
    }

    private static Map<String, Object> componentMap(int iface, int comp, int hidden, boolean isVisible,
                                                    boolean isVisibleGlobal, int parentGlobal) {
        Map<String, Object> m = new HashMap<>();
        m.put("iface", iface);
        m.put("comp", comp);
        m.put("sub", -1);
        m.put("type", 0);
        m.put("category", 0);
        m.put("text", "");
        m.put("hidden", hidden);
        m.put("sprite_id", -1);
        m.put("item_id", -1);
        m.put("item_amount", -1);
        m.put("triggers", List.of());
        m.put("visible", isVisible);
        m.put("visible_global", isVisibleGlobal);
        m.put("slot_flags", isVisible ? 0 : 1);
        m.put("parent_global", parentGlobal);
        return m;
    }
}
