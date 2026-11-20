package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.gameui.ButtonProps;
import com.botwithus.bot.api.gameui.CheckProps;
import com.botwithus.bot.api.gameui.ComboFieldProps;
import com.botwithus.bot.api.gameui.ContentChangeReason;
import com.botwithus.bot.api.gameui.GridProps;
import com.botwithus.bot.api.gameui.InputFieldProps;
import com.botwithus.bot.api.gameui.InputFilter;
import com.botwithus.bot.api.gameui.ListBoxProps;
import com.botwithus.bot.api.gameui.ListEntry;
import com.botwithus.bot.api.gameui.PanelProps;
import com.botwithus.bot.api.gameui.SelectionChange;
import com.botwithus.bot.api.gameui.SliderProps;
import com.botwithus.bot.api.gameui.UiEntry;
import com.botwithus.bot.api.gameui.UiHook;
import com.botwithus.bot.api.gameui.UiHookEvent;
import com.botwithus.bot.api.gameui.UiReadback;
import com.botwithus.bot.api.gameui.UiType;
import com.botwithus.bot.api.gameui.UiWidgetState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.Arrays;
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
 * The P2 half of the plugin-UI codec, pinned against NXTLibrary's
 * {@code docs/PUBLIC_PROTOCOL.md} §6.12 / §7.3 and {@code src/rpc/UiHandlers.cpp},
 * {@code UiRegistry.cpp} ({@code PublishHookEvent}) and {@code UiWidgetParse.cpp}:
 * the interactive types' wire names, every props builder's keys, every type's
 * readback, and the typed decode of every hook's {@code args}.
 */
class UiCodecP2Test {

    private static final long HANDLE = 0x55L;
    private static final int X = 12;
    private static final int Y = 34;
    private static final int SLIDER_VALUE = 40;
    private static final int ENTRY_ID = 7;
    private static final int SUBMIT = 2;
    private static final int MAX_SELECTED = 2;

    /** A {@code ui.get} reply for {@code typeName}, plus the given extra keys. */
    private static Map<String, Object> readback(String typeName, Object... keyValues) {
        Map<String, Object> m = UiCodecTest.handleMap(HANDLE, typeName, false);
        m.put("x", 1L);
        m.put("enabled", true);
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            m.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return m;
    }

    private static List<Object> args(Object... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    @Nested
    @DisplayName("names")
    class Names {

        @Test
        @DisplayName("every interactive type carries the agent's type_name and client number")
        void interactiveTypes() {
            assertAll(
                    () -> assertEquals("button", UiType.BUTTON.wireName()),
                    () -> assertEquals(9, UiType.BUTTON.clientType()),
                    () -> assertEquals("check", UiType.CHECK.wireName()),
                    () -> assertEquals("list", UiType.LIST.wireName()),
                    () -> assertEquals(11, UiType.LIST.clientType()),
                    () -> assertEquals("input_field", UiType.INPUT_FIELD.wireName()),
                    () -> assertEquals("slider", UiType.SLIDER.wireName()),
                    () -> assertEquals("combo_field", UiType.COMBO_FIELD.wireName()),
                    () -> assertEquals(14, UiType.COMBO_FIELD.clientType()),
                    () -> assertEquals("panel", UiType.PANEL.wireName()),
                    () -> assertEquals("grid", UiType.GRID.wireName()),
                    () -> assertEquals(22, UiType.GRID.clientType()),
                    () -> assertEquals(UiType.SLIDER, UiType.fromWireName("slider")),
                    () -> assertTrue(UiType.GRID.isCreatable()));
        }

        @Test
        @DisplayName("hooks carry the lower-cased Jagex names and ids the agent resolves")
        void hookNames() {
            assertAll(
                    () -> assertEquals("onclick", UiHook.ON_CLICK.wireName()),
                    () -> assertEquals("onrelease", UiHook.ON_RELEASE.wireName()),
                    () -> assertEquals("onmouseover", UiHook.ON_MOUSE_OVER.wireName()),
                    () -> assertEquals("onmouseleave", UiHook.ON_MOUSE_LEAVE.wireName()),
                    () -> assertEquals("onbuttonpressed", UiHook.ON_BUTTON_PRESSED.wireName()),
                    () -> assertEquals("oncontentchanged", UiHook.ON_CONTENT_CHANGED.wireName()),
                    () -> assertEquals("onselectionchanged", UiHook.ON_SELECTION_CHANGED.wireName()),
                    () -> assertEquals(49, UiHook.ON_BUTTON_PRESSED.clientId()),
                    () -> assertEquals(50, UiHook.ON_CONTENT_CHANGED.clientId()),
                    () -> assertEquals(51, UiHook.ON_SELECTION_CHANGED.clientId()),
                    () -> assertEquals(Optional.empty(), UiHook.fromWireName("destroyed")),
                    () -> assertEquals(Optional.empty(), UiHook.fromWireName(null)));
        }

        @ParameterizedTest
        @EnumSource(UiHook.class)
        @DisplayName("every hook round-trips through its wire name")
        void hookRoundTrip(UiHook hook) {
            assertEquals(Optional.of(hook), UiHook.fromWireName(hook.wireName()));
        }

        @Test
        @DisplayName("reason and selection-event numbers name the client's constants, and unknowns survive")
        void eventCodes() {
            assertAll(
                    () -> assertEquals(ContentChangeReason.CONTENT_CHANGE, ContentChangeReason.fromCode(0)),
                    () -> assertEquals(ContentChangeReason.SUBMIT, ContentChangeReason.fromCode(SUBMIT)),
                    () -> assertEquals(ContentChangeReason.SUBMIT_ON_FOCUS_LOSS, ContentChangeReason.fromCode(4)),
                    () -> assertEquals(ContentChangeReason.UNKNOWN, ContentChangeReason.fromCode(-1)),
                    () -> assertEquals(ContentChangeReason.UNKNOWN, ContentChangeReason.fromCode(99)),
                    () -> assertEquals(SelectionChange.DESELECTED, SelectionChange.fromCode(0)),
                    () -> assertEquals(SelectionChange.ERROR_MAX_SELECTED, SelectionChange.fromCode(MAX_SELECTED)),
                    () -> assertEquals(SelectionChange.ERROR_NOT_SELECTABLE, SelectionChange.fromCode(3)),
                    () -> assertEquals(SelectionChange.UNKNOWN, SelectionChange.fromCode(-1)));
        }
    }

    @Nested
    @DisplayName("params")
    class Params {

        @Test
        @DisplayName("subscribe, unsubscribe and blur name the handle (and the hook)")
        void hookAndBlurParams() {
            assertAll(
                    () -> assertEquals(Map.of("handle", HANDLE, "hook", "onselectionchanged"),
                            UiCodec.hookParams(HANDLE, UiHook.ON_SELECTION_CHANGED)),
                    () -> assertEquals(Map.of("handle", HANDLE), UiCodec.handleParams(HANDLE)),
                    () -> assertEquals("ui.subscribe", UiCodec.SUBSCRIBE),
                    () -> assertEquals("ui.unsubscribe", UiCodec.UNSUBSCRIBE),
                    () -> assertEquals("ui.blur", UiCodec.BLUR));
        }

        @Test
        @DisplayName("button, check and panel props use the agent's keys")
        void buttonCheckPanel() {
            assertAll(
                    () -> assertEquals(Map.of("label", "Go", "can_toggle", true, "toggled", false, "enabled", false),
                            new ButtonProps().label("Go").canToggle(true).toggled(false).enabled(false)
                                    .wireValues()),
                    () -> assertEquals(Map.of("checked", true), new CheckProps().checked(true).wireValues()),
                    () -> assertEquals(Map.of("vertical", true), new PanelProps().vertical(true).wireValues()),
                    () -> assertEquals(Map.of("width", X), new GridProps().width(X).wireValues()));
        }

        @Test
        @DisplayName("input field props use the agent's keys, and readback-only OTHER cannot be sent")
        void inputField() {
            assertEquals(Map.of("content", "hi", "filter", "numbers_as_text", "max_length", X),
                    new InputFieldProps().content("hi").filter(InputFilter.NUMBERS_AS_TEXT).maxLength(X)
                            .wireValues());
            assertThrows(IllegalArgumentException.class, () -> new InputFieldProps().filter(InputFilter.OTHER));
            assertThrows(NullPointerException.class, () -> new InputFieldProps().content(null));
        }

        @Test
        @DisplayName("a slider's range is four keys from one call, then its value")
        void slider() {
            Map<String, Object> wire = new SliderProps().range(0, 100, 5, 20).value(SLIDER_VALUE).wireValues();

            assertEquals(Map.of("min", 0, "max", 100, "step", 5, "ticks", 20, "value", SLIDER_VALUE), wire);
            assertEquals(List.of("min", "max", "step", "ticks", "value"), List.copyOf(wire.keySet()),
                    "value follows the range, as the agent applies it");
        }

        @Test
        @DisplayName("entries go as [{id, text}]; combo selects an id or -1, a list an array")
        void entriesAndSelection() {
            List<UiEntry> entries = List.of(new UiEntry(1, "one"), new UiEntry(2, "two"));
            List<Map<String, Object>> wireEntries = List.of(Map.of("id", 1, "text", "one"),
                    Map.of("id", 2, "text", "two"));

            assertAll(
                    () -> assertEquals(Map.of("entries", wireEntries, "selected", 2),
                            new ComboFieldProps().entries(entries).selected(2).wireValues()),
                    () -> assertEquals(Map.of("selected", -1), new ComboFieldProps().noSelection().wireValues()),
                    () -> assertEquals(Map.of("entries", wireEntries, "selection_limit", 3, "selected", List.of(1, 2)),
                            new ListBoxProps().entries(entries).selectionLimit(3).selected(1, 2).wireValues()),
                    () -> assertEquals(Map.of("selected", List.of()), new ListBoxProps().selected(List.of())
                            .wireValues()));
        }
    }

    @Nested
    @DisplayName("readback")
    class Readback {

        @Test
        @DisplayName("button")
        void button() {
            UiReadback r = UiCodec.decodeReadback(readback("button", "label", "Go", "can_toggle", true,
                    "toggled", true));

            assertEquals(Optional.of(new UiWidgetState.ButtonState("Go", true, true)), r.widget());
            assertTrue(r.isEnabled());
        }

        @Test
        @DisplayName("check, panel")
        void checkAndPanel() {
            assertEquals(Optional.of(new UiWidgetState.CheckState(true)),
                    UiCodec.decodeReadback(readback("check", "checked", true)).widget());
            assertEquals(Optional.of(new UiWidgetState.PanelState(true)),
                    UiCodec.decodeReadback(readback("panel", "vertical", true)).widget());
        }

        @Test
        @DisplayName("input field, including renders false and the content on request")
        void inputField() {
            UiReadback r = UiCodec.decodeReadback(readback("input_field", "focused", false, "filter", "numeric",
                    "max_length", 12L, "font_id", 494L, "sprite_id", -1L, "renders", false, "content", "abc"));

            assertEquals(Optional.of(new UiWidgetState.InputFieldState(false, InputFilter.NUMERIC, 12, 494, -1,
                    false)), r.widget());
            assertEquals(Optional.of("abc"), r.content());
        }

        @Test
        @DisplayName("an input filter the agent reports as other reads OTHER")
        void inputField_otherFilter() {
            UiReadback r = UiCodec.decodeReadback(readback("input_field", "filter", "other"));

            assertEquals(InputFilter.OTHER, inputState(r).filter());
        }

        private UiWidgetState.InputFieldState inputState(UiReadback r) {
            return switch (r.widget().orElseThrow()) {
                case UiWidgetState.InputFieldState s -> s;
                default -> throw new AssertionError(r.widget());
            };
        }

        @Test
        @DisplayName("slider")
        void slider() {
            UiReadback r = UiCodec.decodeReadback(readback("slider", "min", 0L, "max", 100L, "value", 40L,
                    "step", 5L, "ticks", 20L));

            assertEquals(Optional.of(new UiWidgetState.SliderState(0, 100, SLIDER_VALUE, 5, 20)), r.widget());
        }

        @Test
        @DisplayName("combo field")
        void combo() {
            UiReadback r = UiCodec.decodeReadback(readback("combo_field",
                    "entries", List.of(Map.of("id", 1L, "text", "one"), Map.of("id", 7L, "text", "seven")),
                    "selected", 7L));

            assertEquals(Optional.of(new UiWidgetState.ComboFieldState(
                    List.of(new UiEntry(1, "one"), new UiEntry(ENTRY_ID, "seven")), ENTRY_ID)), r.widget());
        }

        @Test
        @DisplayName("list, with each entry's flags")
        void list() {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", 7L);
            entry.put("text", "seven");
            entry.put("hidden", false);
            entry.put("disabled", true);
            entry.put("selected", true);
            UiReadback r = UiCodec.decodeReadback(readback("list", "entries", List.of(entry),
                    "selected", List.of(7L), "selection_limit", 3L));

            assertEquals(Optional.of(new UiWidgetState.ListBoxState(
                    List.of(new ListEntry(ENTRY_ID, "seven", false, true, true)), List.of(ENTRY_ID), 3)),
                    r.widget());
        }

        @Test
        @DisplayName("a grid and the P1 types have no widget keys; a disabled one reads disabled")
        void noWidgetKeys() {
            assertEquals(Optional.empty(), UiCodec.decodeReadback(readback("grid")).widget());
            assertEquals(Optional.empty(), UiCodec.decodeReadback(readback("canvas")).widget());
            assertFalse(UiCodec.decodeReadback(readback("grid", "enabled", false)).isEnabled());
        }

        @Test
        @DisplayName("an agent that predates enabled reads enabled")
        void enabledAbsent_readsEnabled() {
            Map<String, Object> old = UiCodecTest.handleMap(HANDLE, "canvas", false);

            assertTrue(UiCodec.decodeReadback(old).isEnabled());
        }
    }

    @Nested
    @DisplayName("hook events")
    class HookEvents {

        @ParameterizedTest
        @EnumSource(value = UiHook.class, names = {"ON_CLICK", "ON_RELEASE", "ON_MOUSE_OVER", "ON_MOUSE_LEAVE"})
        @DisplayName("a pointer hook decodes [x, y] on any type")
        void pointer(UiHook hook) {
            assertEquals(Optional.of(new UiHookEvent.Pointer(HANDLE, hook, X, Y)),
                    UiCodec.decodeHookEvent(HANDLE, UiType.CANVAS, hook, args(12L, 34L)));
        }

        @Test
        @DisplayName("onbuttonpressed decodes [current, previous]")
        void buttonPressed() {
            Optional<UiHookEvent> e = UiCodec.decodeHookEvent(HANDLE, UiType.CHECK, UiHook.ON_BUTTON_PRESSED,
                    args(1L, 0L));

            assertEquals(Optional.of(new UiHookEvent.ButtonPressed(HANDLE, 1, 0)), e);
            assertTrue(new UiHookEvent.ButtonPressed(HANDLE, 1, 0).isOn());
        }

        @Test
        @DisplayName("oncontentchanged decodes [reason, content], and a nil content is empty")
        void contentChanged() {
            Optional<UiHookEvent> submitted = UiCodec.decodeHookEvent(HANDLE, UiType.INPUT_FIELD,
                    UiHook.ON_CONTENT_CHANGED, args(2L, "typed"));
            Optional<UiHookEvent> restricted = UiCodec.decodeHookEvent(HANDLE, UiType.INPUT_FIELD,
                    UiHook.ON_CONTENT_CHANGED, args(0L, null));

            assertEquals(Optional.of(new UiHookEvent.ContentChanged(HANDLE, SUBMIT, Optional.of("typed"))), submitted);
            assertEquals(Optional.of(new UiHookEvent.ContentChanged(HANDLE, 0, Optional.empty())), restricted);
            assertEquals(ContentChangeReason.SUBMIT, new UiHookEvent.ContentChanged(HANDLE, SUBMIT,
                    Optional.empty()).reason());
        }

        @Test
        @DisplayName("onselectionchanged decodes by type: a slider's value, an entry list's id and event")
        void selectionChanged() {
            assertAll(
                    () -> assertEquals(Optional.of(new UiHookEvent.SliderChanged(HANDLE, SLIDER_VALUE)),
                            UiCodec.decodeHookEvent(HANDLE, UiType.SLIDER, UiHook.ON_SELECTION_CHANGED,
                                    args(40L, 1L))),
                    () -> assertEquals(Optional.of(new UiHookEvent.SelectionChanged(HANDLE, ENTRY_ID, 1)),
                            UiCodec.decodeHookEvent(HANDLE, UiType.COMBO_FIELD, UiHook.ON_SELECTION_CHANGED,
                                    args(7L, 1L))),
                    () -> assertEquals(Optional.of(new UiHookEvent.SelectionChanged(HANDLE, ENTRY_ID, MAX_SELECTED)),
                            UiCodec.decodeHookEvent(HANDLE, UiType.LIST, UiHook.ON_SELECTION_CHANGED,
                                    args(7L, 2L))),
                    () -> assertEquals(SelectionChange.ERROR_MAX_SELECTED,
                            new UiHookEvent.SelectionChanged(HANDLE, ENTRY_ID, MAX_SELECTED).kind()));
        }

        @Test
        @DisplayName("args of the wrong shape, or a selection on a type without one, decode to nothing")
        void malformed() {
            assertAll(
                    () -> assertEquals(Optional.empty(), UiCodec.decodeHookEvent(HANDLE, UiType.BUTTON,
                            UiHook.ON_CLICK, args(1L))),
                    () -> assertEquals(Optional.empty(), UiCodec.decodeHookEvent(HANDLE, UiType.BUTTON,
                            UiHook.ON_CLICK, args())),
                    () -> assertEquals(Optional.empty(), UiCodec.decodeHookEvent(HANDLE, UiType.INPUT_FIELD,
                            UiHook.ON_CONTENT_CHANGED, args("x", "y"))),
                    () -> assertEquals(Optional.empty(), UiCodec.decodeHookEvent(HANDLE, UiType.BUTTON,
                            UiHook.ON_SELECTION_CHANGED, args(1L, 1L))));
        }

        @Test
        @DisplayName("a hook push is split from destroyed and from hooks this host does not know")
        void pushRouting() {
            Map<String, Object> hook = Map.of("handle", HANDLE, "hook", "onclick", "args", List.of(1L, 2L));

            assertEquals(Optional.of(new UiCodec.HookPush(HANDLE, UiHook.ON_CLICK, List.of(1L, 2L))),
                    UiCodec.decodeHookPush(hook));
            assertEquals(Optional.empty(), UiCodec.decodeHookPush(Map.of("handle", HANDLE, "hook", "destroyed")));
            assertEquals(Optional.empty(), UiCodec.decodeHookPush(Map.of("handle", HANDLE, "hook", "onfuture")));
            assertEquals(Optional.empty(), UiCodec.decodeHookPush(Map.of("hook", "onclick")));
        }

        @Test
        @DisplayName("a pointer event refuses a hook that carries no position")
        void pointerRefusesOtherHooks() {
            assertThrows(IllegalArgumentException.class,
                    () -> new UiHookEvent.Pointer(HANDLE, UiHook.ON_BUTTON_PRESSED, X, Y));
        }
    }
}
