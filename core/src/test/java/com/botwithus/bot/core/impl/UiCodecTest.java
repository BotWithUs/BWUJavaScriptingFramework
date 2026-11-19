package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.gameui.CanvasOp;
import com.botwithus.bot.api.gameui.CanvasProps;
import com.botwithus.bot.api.gameui.LineProps;
import com.botwithus.bot.api.gameui.RectangleProps;
import com.botwithus.bot.api.gameui.TextProps;
import com.botwithus.bot.api.gameui.UiEvent;
import com.botwithus.bot.api.gameui.UiHandleInfo;
import com.botwithus.bot.api.gameui.UiOrder;
import com.botwithus.bot.api.gameui.UiPlacement;
import com.botwithus.bot.api.gameui.UiReadback;
import com.botwithus.bot.api.gameui.UiType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code ui.*} wire shapes, pinned against NXTLibrary's
 * {@code docs/PUBLIC_PROTOCOL.md} §6.12 / §7.3 and the worked requests in
 * {@code tests/scenarios/plugin-ui-create-draw.json}. Every key spelled here is
 * one the agent's parser matches by name, and an unknown key fails the whole call.
 */
class UiCodecTest {

    private static final long PARENT = 7L;
    private static final long HANDLE = 42L;
    private static final int GAME_AREA_IFACE = 1477;
    private static final int GAME_AREA_COMP = 61;
    private static final int DYNAMIC_KEY = 3;
    private static final int CATEGORY = 9;
    private static final int DYNAMIC = 12;
    private static final int X = 40;
    private static final int Y = 60;
    private static final int W = 320;
    private static final int H = 200;
    private static final int THICK = 3;
    /** {@code 0xFF0000FF}: negative as an int, so it proves the colour goes out unsigned. */
    private static final int OPAQUE_RED = 0xFF0000FF;
    private static final long OPAQUE_RED_WIRE = 4_278_190_335L;
    private static final double HALF = 0.5;
    private static final int CANVAS_TYPE = 8;
    private static final int NUM_ELEMENTS = 5;

    @Test
    @DisplayName("ui.get_component sends key only for a dynamic child")
    void getComponent_keyOnlyWhenDynamic() {
        assertEquals(Map.of("iface", GAME_AREA_IFACE, "comp", GAME_AREA_COMP),
                UiCodec.getComponentParams(GAME_AREA_IFACE, GAME_AREA_COMP, UiHandleInfo.STATIC_KEY));
        assertEquals(Map.of("iface", GAME_AREA_IFACE, "comp", GAME_AREA_COMP, "key", DYNAMIC_KEY),
                UiCodec.getComponentParams(GAME_AREA_IFACE, GAME_AREA_COMP, DYNAMIC_KEY));
    }

    @Test
    @DisplayName("ui.create carries parent, type and props; category and dynamic only when chosen")
    void create_autoPlacement_sendsNoKey() {
        Map<String, Object> params = UiCodec.createParams(PARENT, UiType.CANVAS, UiPlacement.auto(),
                new CanvasProps().at(X, Y).size(W, H));

        assertEquals(Map.of("parent", PARENT, "type", "canvas",
                "props", Map.of("x", X, "y", Y, "width", W, "height", H)), params);
    }

    @Test
    void create_explicitKey_sendsCategoryAndDynamic() {
        Map<String, Object> params = UiCodec.createParams(PARENT, UiType.LAYER,
                UiPlacement.key(CATEGORY, DYNAMIC), new TextProps());

        assertAll(
                () -> assertEquals(CATEGORY, params.get("category")),
                () -> assertEquals(DYNAMIC, params.get("dynamic")),
                () -> assertFalse(params.containsKey("props"), "empty props are omitted, not sent empty"));
    }

    @Test
    @DisplayName("category 0 cannot be built, so it can never be sent")
    void placement_categoryZero_isUnrepresentable() {
        assertThrows(IllegalArgumentException.class, () -> UiPlacement.category(0));
    }

    @Test
    @DisplayName("props: rgba goes out unsigned, and a percentage travels with its pixel value")
    void props_wireValues() {
        Map<String, Object> rect = new RectangleProps().rgba(OPAQUE_RED).fill(true).outlineThickness(2)
                .wireValues();
        Map<String, Object> line = new LineProps().x(X, HALF, -HALF).lineWidth(THICK).invert(true)
                .wireValues();
        Map<String, Object> text = new TextProps().text("BotWithUs plugin-ui").clickthrough(true)
                .wireValues();

        assertAll(
                () -> assertEquals(Map.of("rgba", OPAQUE_RED_WIRE, "fill", true, "outline_thickness", 2), rect),
                () -> assertEquals(Map.of("x", X, "x_parent_pc", HALF, "x_self_pc", -HALF,
                        "line_width", THICK, "line_invert", true), line),
                () -> assertEquals(Map.of("text", "BotWithUs plugin-ui", "clickthrough", true), text));
    }

    @Test
    @DisplayName("every canvas op encodes to the names the agent's op table expects")
    void canvasOps_encodeEveryVariant() {
        List<CanvasOp> ops = List.of(
                new CanvasOp.Rect(0, 0, W, H, OPAQUE_RED),
                new CanvasOp.Outline(0, 0, W, H, 2, OPAQUE_RED),
                new CanvasOp.Line(X, Y, W, H, THICK, OPAQUE_RED),
                new CanvasOp.GradientH(X, Y, W, H, OPAQUE_RED, 0),
                new CanvasOp.GradientV(X, Y, W, H, 0, OPAQUE_RED),
                new CanvasOp.Triangle(0, 0, X, Y, W, H, OPAQUE_RED),
                new CanvasOp.Undo(),
                new CanvasOp.Clear());

        Map<String, Object> params = UiCodec.canvasParams(HANDLE, ops);

        assertEquals(List.of(
                Map.of("op", "rect", "x", 0, "y", 0, "width", W, "height", H, "rgba", OPAQUE_RED_WIRE),
                Map.of("op", "outline", "x", 0, "y", 0, "width", W, "height", H, "thickness", 2,
                        "rgba", OPAQUE_RED_WIRE),
                Map.of("op", "line", "x", X, "y", Y, "end_x", W, "end_y", H, "thickness", THICK,
                        "rgba", OPAQUE_RED_WIRE),
                Map.of("op", "gradient_h", "x", X, "y", Y, "width", W, "height", H,
                        "rgba_start", OPAQUE_RED_WIRE, "rgba_end", 0L),
                Map.of("op", "gradient_v", "x", X, "y", Y, "width", W, "height", H,
                        "rgba_start", 0L, "rgba_end", OPAQUE_RED_WIRE),
                Map.of("op", "triangle", "x0", 0, "y0", 0, "x1", X, "y1", Y, "x2", W, "y2", H,
                        "rgba", OPAQUE_RED_WIRE),
                Map.of("op", "undo"),
                Map.of("op", "clear")), params.get("ops"));
        assertEquals(HANDLE, params.get("handle"));
    }

    @Test
    void get_order_set_params() {
        assertAll(
                () -> assertEquals(Map.of("handle", HANDLE, "keys", List.of("content")),
                        UiCodec.getParams(HANDLE, true)),
                () -> assertEquals(Map.of("handle", HANDLE), UiCodec.getParams(HANDLE, false)),
                () -> assertEquals(Map.of("handle", HANDLE, "to", "front"),
                        UiCodec.orderParams(HANDLE, UiOrder.FRONT)),
                () -> assertEquals(Map.of("handle", HANDLE, "props", Map.of("text", "changed")),
                        UiCodec.setParams(HANDLE, new TextProps().text("changed"))));
    }

    static Map<String, Object> handleMap(long handle, String typeName, boolean isJagex) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("handle", handle);
        m.put("type", (long) CANVAS_TYPE);
        m.put("type_name", typeName);
        m.put("iface", (long) GAME_AREA_IFACE);
        m.put("comp", (long) GAME_AREA_COMP);
        m.put("key", isJagex ? -1L : (long) DYNAMIC_KEY);
        m.put("parent", isJagex ? 0L : PARENT);
        m.put("jagex", isJagex);
        m.put("quarantined", false);
        return m;
    }

    @Test
    @DisplayName("a handle map decodes field for field")
    void decodeHandle() {
        UiHandleInfo info = UiCodec.decodeHandle(handleMap(HANDLE, "canvas", false));

        assertEquals(new UiHandleInfo(HANDLE, UiType.CANVAS, GAME_AREA_IFACE, GAME_AREA_COMP,
                DYNAMIC_KEY, PARENT, false, false), info);
        assertEquals(UiType.OTHER, UiCodec.decodeHandle(handleMap(HANDLE, "sprite", true)).type(),
                "a type the host does not name is OTHER, not an error");
    }

    @Test
    void decodeHandle_withoutHandle_isAProtocolError() {
        assertThrows(IllegalStateException.class, () -> UiCodec.decodeHandle(Map.of("type_name", "canvas")));
    }

    @Test
    @DisplayName("readback: geometry, flags, unsigned rgba and the not-applicable sentinel")
    void decodeReadback() {
        Map<String, Object> m = handleMap(HANDLE, "canvas", false);
        m.putAll(Map.of("x", (long) X, "y", (long) Y, "width", (long) W, "height", (long) H,
                "rgba", OPAQUE_RED_WIRE, "visible", true, "num_elements", (long) NUM_ELEMENTS,
                "line_width", -1L, "op_flags_read", true, "op_target", -1L));
        m.put("op_mask", 0L);

        UiReadback r = UiCodec.decodeReadback(m);

        assertAll(
                () -> assertEquals(X, r.x()), () -> assertEquals(H, r.height()),
                () -> assertEquals(OPAQUE_RED, r.rgba()),
                () -> assertTrue(r.isVisible()), () -> assertTrue(r.isOpFlagsRead()),
                () -> assertEquals(NUM_ELEMENTS, r.numElements()),
                () -> assertEquals(UiReadback.NOT_APPLICABLE, r.lineWidth()),
                () -> assertEquals(-1, r.opTarget()),
                () -> assertEquals(Optional.empty(), r.content(), "content only when asked for"));
    }

    @Test
    void decodeList_andEvent() {
        Map<String, Object> list = Map.of("handles", List.of(handleMap(HANDLE, "text", false)));
        assertEquals(List.of(HANDLE), UiCodec.decodeList(list).stream().map(UiHandleInfo::handle).toList());

        assertEquals(Optional.of(new UiEvent(HANDLE, "destroyed", Optional.of("server_events"))),
                UiCodec.decodeEvent(Map.of("handle", HANDLE, "hook", "destroyed", "reason", "server_events")));
        assertEquals(Optional.of(new UiEvent(HANDLE, "a_later_hook", Optional.empty())),
                UiCodec.decodeEvent(Map.of("handle", HANDLE, "hook", "a_later_hook")),
                "an unknown hook passes through rather than being dropped");
        assertEquals(Optional.empty(), UiCodec.decodeEvent(Map.of("hook", "destroyed")));
    }
}
