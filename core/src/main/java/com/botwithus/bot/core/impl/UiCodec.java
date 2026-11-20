package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.gameui.CanvasOp;
import com.botwithus.bot.api.gameui.ComboFieldProps;
import com.botwithus.bot.api.gameui.InputFilter;
import com.botwithus.bot.api.gameui.ListEntry;
import com.botwithus.bot.api.gameui.UiEntry;
import com.botwithus.bot.api.gameui.UiEvent;
import com.botwithus.bot.api.gameui.UiHandleInfo;
import com.botwithus.bot.api.gameui.UiHook;
import com.botwithus.bot.api.gameui.UiHookEvent;
import com.botwithus.bot.api.gameui.UiOrder;
import com.botwithus.bot.api.gameui.UiPlacement;
import com.botwithus.bot.api.gameui.UiProps;
import com.botwithus.bot.api.gameui.UiReadback;
import com.botwithus.bot.api.gameui.UiType;
import com.botwithus.bot.api.gameui.UiWidgetState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static com.botwithus.bot.core.impl.MapHelper.getBool;
import static com.botwithus.bot.core.impl.MapHelper.getIntList;
import static com.botwithus.bot.core.impl.MapHelper.getIntOr;
import static com.botwithus.bot.core.impl.MapHelper.getLong;
import static com.botwithus.bot.core.impl.MapHelper.getMapList;
import static com.botwithus.bot.core.impl.MapHelper.getObjectList;
import static com.botwithus.bot.core.impl.MapHelper.getString;
import static com.botwithus.bot.core.impl.MapHelper.getStringNullable;
import static com.botwithus.bot.core.impl.MapHelper.intAt;
import static com.botwithus.bot.core.impl.MapHelper.stringAt;

/**
 * Encodes the {@code ui.*} params and decodes their replies and the
 * {@code ui.event} push, mirroring NXTLibrary's {@code src/rpc/UiHandlers.cpp}
 * and {@code docs/PUBLIC_PROTOCOL.md} §6.12 and §7.3. Every key spelled here is a
 * wire name; change it there and here together.
 *
 * <p>Pure: no I/O, so {@code UiCodecTest} pins the bytes without a transport.</p>
 */
final class UiCodec {

    static final String GET_COMPONENT = "ui.get_component";
    static final String CREATE = "ui.create";
    static final String SET = "ui.set";
    static final String GET = "ui.get";
    static final String CANVAS = "ui.canvas";
    static final String ORDER = "ui.order";
    static final String DESTROY = "ui.destroy";
    static final String LIST = "ui.list";
    static final String SUBSCRIBE = "ui.subscribe";
    static final String UNSUBSCRIBE = "ui.unsubscribe";
    static final String BLUR = "ui.blur";

    /** The push topic the agent announces handle events on. */
    static final String EVENT_TOPIC = "ui.event";

    /** The only {@code ui.get} key that changes the reply. */
    static final String CONTENT_KEY = "content";

    private static final String HANDLE = "handle";
    private static final String HOOK = "hook";
    private static final String ENTRIES = "entries";
    private static final String SELECTED = "selected";
    /** What an int readback key that the agent left out decodes to. */
    private static final int ABSENT = UiReadback.NOT_APPLICABLE;

    private UiCodec() {
    }

    static Map<String, Object> getComponentParams(int iface, int comp, int key) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("iface", iface);
        params.put("comp", comp);
        if (key != UiHandleInfo.STATIC_KEY) {
            params.put("key", key);
        }
        return params;
    }

    static Map<String, Object> createParams(long parent, UiType type, UiPlacement placement,
                                            UiProps<?> props) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("parent", parent);
        params.put("type", type.wireName());
        placement.category().ifPresent(c -> params.put("category", c));
        placement.dynamic().ifPresent(d -> params.put("dynamic", d));
        if (!props.isEmpty()) {
            params.put("props", props.wireValues());
        }
        return params;
    }

    static Map<String, Object> setParams(long handle, UiProps<?> props) {
        Map<String, Object> params = handleParams(handle);
        params.put("props", props.wireValues());
        return params;
    }

    static Map<String, Object> getParams(long handle, boolean isContentRequested) {
        Map<String, Object> params = handleParams(handle);
        if (isContentRequested) {
            params.put("keys", List.of(CONTENT_KEY));
        }
        return params;
    }

    static Map<String, Object> canvasParams(long handle, List<CanvasOp> ops) {
        Map<String, Object> params = handleParams(handle);
        params.put("ops", ops.stream().map(UiCodec::encodeOp).toList());
        return params;
    }

    static Map<String, Object> orderParams(long handle, UiOrder to) {
        Map<String, Object> params = handleParams(handle);
        params.put("to", to.wireName());
        return params;
    }

    /** {@code ui.subscribe} and {@code ui.unsubscribe}. */
    static Map<String, Object> hookParams(long handle, UiHook hook) {
        Map<String, Object> params = handleParams(handle);
        params.put(HOOK, hook.wireName());
        return params;
    }

    static Map<String, Object> handleParams(long handle) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(HANDLE, handle);
        return params;
    }

    static Map<String, Object> encodeOp(CanvasOp op) {
        return switch (op) {
            case CanvasOp.Line l -> op("line", "x", l.x(), "y", l.y(), "end_x", l.endX(),
                    "end_y", l.endY(), "thickness", l.thickness()).color("rgba", l.rgba()).map();
            case CanvasOp.Outline o -> op("outline", "x", o.x(), "y", o.y(), "width", o.width(),
                    "height", o.height(), "thickness", o.thickness()).color("rgba", o.rgba()).map();
            case CanvasOp.Rect r -> op("rect", "x", r.x(), "y", r.y(), "width", r.width(),
                    "height", r.height()).color("rgba", r.rgba()).map();
            case CanvasOp.GradientH g -> op("gradient_h", "x", g.x(), "y", g.y(), "width", g.width(),
                    "height", g.height()).color("rgba_start", g.rgbaStart())
                    .color("rgba_end", g.rgbaEnd()).map();
            case CanvasOp.GradientV g -> op("gradient_v", "x", g.x(), "y", g.y(), "width", g.width(),
                    "height", g.height()).color("rgba_start", g.rgbaStart())
                    .color("rgba_end", g.rgbaEnd()).map();
            case CanvasOp.Triangle t -> op("triangle", "x0", t.x0(), "y0", t.y0(), "x1", t.x1(),
                    "y1", t.y1(), "x2", t.x2(), "y2", t.y2()).color("rgba", t.rgba()).map();
            case CanvasOp.Clear _ -> op("clear").map();
            case CanvasOp.Undo _ -> op("undo").map();
        };
    }

    /** An op map under construction: the name first, then named int arguments in order. */
    private record OpMap(Map<String, Object> map) {
        OpMap color(String key, int rgba) {
            map.put(key, Integer.toUnsignedLong(rgba));
            return this;
        }
    }

    private static OpMap op(String name, Object... namedInts) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("op", name);
        for (int i = 0; i + 1 < namedInts.length; i += 2) {
            map.put(String.valueOf(namedInts[i]), namedInts[i + 1]);
        }
        return new OpMap(map);
    }

    static UiHandleInfo decodeHandle(Map<String, Object> map) {
        long handle = getLong(map, HANDLE);
        if (handle == 0) {
            throw new IllegalStateException("ui reply carried no handle: " + map.keySet());
        }
        String typeName = getStringNullable(map, "type_name");
        return new UiHandleInfo(handle, UiType.fromWireName(typeName),
                getIntOr(map, "iface", UiReadback.NOT_APPLICABLE),
                getIntOr(map, "comp", UiReadback.NOT_APPLICABLE),
                getIntOr(map, "key", UiHandleInfo.STATIC_KEY),
                getLong(map, "parent"),
                getBool(map, "jagex"),
                getBool(map, "quarantined"));
    }

    static UiReadback decodeReadback(Map<String, Object> map) {
        int none = UiReadback.NOT_APPLICABLE;
        UiHandleInfo info = decodeHandle(map);
        return new UiReadback(info,
                getIntOr(map, "x", 0), getIntOr(map, "y", 0),
                getIntOr(map, "width", 0), getIntOr(map, "height", 0),
                (int) getLong(map, "rgba"),
                getBool(map, "visible"), getBool(map, "restricted"), getBool(map, "clickthrough"),
                getIntOr(map, "num_elements", none),
                getIntOr(map, "line_width", none), getIntOr(map, "line_invert", none),
                getIntOr(map, "fill", none), getIntOr(map, "outline_thickness", none),
                getIntOr(map, "use_count", none),
                getBool(map, "op_flags_read"), getLong(map, "op_mask"),
                getIntOr(map, "op_target", none),
                Optional.ofNullable(getStringNullable(map, CONTENT_KEY)),
                !map.containsKey("enabled") || getBool(map, "enabled"),
                decodeWidget(info.type(), map));
    }

    /** The interactive type's own readback keys; empty for a type that has none. */
    static Optional<UiWidgetState> decodeWidget(UiType type, Map<String, Object> map) {
        return Optional.ofNullable(switch (type) {
            case BUTTON -> new UiWidgetState.ButtonState(getStringNullable(map, "label"),
                    getBool(map, "can_toggle"), getBool(map, "toggled"));
            case CHECK -> new UiWidgetState.CheckState(getBool(map, "checked"));
            case INPUT_FIELD -> new UiWidgetState.InputFieldState(getBool(map, "focused"),
                    InputFilter.fromWireName(getStringNullable(map, "filter")),
                    getIntOr(map, "max_length", ABSENT), getIntOr(map, "font_id", ABSENT),
                    getIntOr(map, "sprite_id", ABSENT), getBool(map, "renders"));
            case SLIDER -> new UiWidgetState.SliderState(getIntOr(map, "min", 0), getIntOr(map, "max", 0),
                    getIntOr(map, "value", 0), getIntOr(map, "step", 0), getIntOr(map, "ticks", 0));
            case COMBO_FIELD -> new UiWidgetState.ComboFieldState(decodeEntries(map),
                    getIntOr(map, SELECTED, ComboFieldProps.NO_SELECTION));
            case LIST -> new UiWidgetState.ListBoxState(decodeListEntries(map), getIntList(map, SELECTED),
                    getIntOr(map, "selection_limit", ABSENT));
            case PANEL -> new UiWidgetState.PanelState(getBool(map, "vertical"));
            case LINE, RECTANGLE, TEXT, CANVAS, LAYER, GRID, OTHER -> null;
        });
    }

    private static List<UiEntry> decodeEntries(Map<String, Object> map) {
        return getMapList(map, ENTRIES).stream()
                .map(e -> new UiEntry(getIntOr(e, "id", ABSENT), getString(e, "text")))
                .toList();
    }

    private static List<ListEntry> decodeListEntries(Map<String, Object> map) {
        return getMapList(map, ENTRIES).stream()
                .map(e -> new ListEntry(getIntOr(e, "id", ABSENT), getString(e, "text"),
                        getBool(e, "hidden"), getBool(e, "disabled"), getBool(e, SELECTED)))
                .toList();
    }

    static List<UiHandleInfo> decodeList(Map<String, Object> reply) {
        return getMapList(reply, "handles").stream().map(UiCodec::decodeHandle).toList();
    }

    /** A hook push before it is decoded: the type that decides its shape is the binding's to look up. */
    record HookPush(long handle, UiHook hook, List<Object> args) {
    }

    /**
     * A hook push ({@code {handle, hook, args}}) for a hook this host knows; empty for
     * {@code destroyed}, for a hook it does not know (both go through
     * {@link #decodeEvent}) and for one with no handle.
     */
    static Optional<HookPush> decodeHookPush(Map<String, Object> data) {
        long handle = getLong(data, HANDLE);
        Optional<UiHook> hook = UiHook.fromWireName(getStringNullable(data, HOOK));
        if (handle == 0 || hook.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new HookPush(handle, hook.get(), getObjectList(data, "args")));
    }

    /**
     * A push's {@code args} as the typed event for a component of {@code type}, about
     * {@code handle} (the number the script sees). Empty when the args do not have the
     * shape the hook promises, or the hook does not fit the type.
     */
    static Optional<UiHookEvent> decodeHookEvent(long handle, UiType type, UiHook hook, List<Object> args) {
        OptionalInt first = intAt(args, 0);
        if (first.isEmpty()) {
            return Optional.empty();
        }
        int arg0 = first.getAsInt();
        if (hook == UiHook.ON_CONTENT_CHANGED) {
            return Optional.of(new UiHookEvent.ContentChanged(handle, arg0, stringAt(args, 1)));
        }
        OptionalInt second = intAt(args, 1);
        if (second.isEmpty()) {
            return Optional.empty();
        }
        int arg1 = second.getAsInt();
        return switch (hook) {
            case ON_CLICK, ON_RELEASE, ON_MOUSE_OVER, ON_MOUSE_LEAVE ->
                    Optional.of(new UiHookEvent.Pointer(handle, hook, arg0, arg1));
            case ON_BUTTON_PRESSED -> Optional.of(new UiHookEvent.ButtonPressed(handle, arg0, arg1));
            case ON_SELECTION_CHANGED -> selectionEvent(handle, type, arg0, arg1);
            case ON_CONTENT_CHANGED -> Optional.empty();
        };
    }

    /** {@code onselectionchanged}: a slider's {@code [value, 1]}, an entry list's {@code [id, event]}. */
    private static Optional<UiHookEvent> selectionEvent(long handle, UiType type, int arg0, int arg1) {
        return switch (type) {
            case SLIDER -> Optional.of(new UiHookEvent.SliderChanged(handle, arg0));
            case COMBO_FIELD, LIST -> Optional.of(new UiHookEvent.SelectionChanged(handle, arg0, arg1));
            default -> Optional.empty();
        };
    }

    /** The push's data as an event; empty for one with no handle or no hook, which is dropped. */
    static Optional<UiEvent> decodeEvent(Map<String, Object> data) {
        long handle = getLong(data, HANDLE);
        String hook = getStringNullable(data, HOOK);
        if (handle == 0 || hook == null) {
            return Optional.empty();
        }
        return Optional.of(new UiEvent(handle, hook,
                Optional.ofNullable(getStringNullable(data, "reason"))));
    }
}
