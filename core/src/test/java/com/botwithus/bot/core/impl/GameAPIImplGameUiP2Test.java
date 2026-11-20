package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.gameui.Button;
import com.botwithus.bot.api.gameui.CanvasOp;
import com.botwithus.bot.api.gameui.ButtonProps;
import com.botwithus.bot.api.gameui.Check;
import com.botwithus.bot.api.gameui.CheckProps;
import com.botwithus.bot.api.gameui.ComboField;
import com.botwithus.bot.api.gameui.ComboFieldProps;
import com.botwithus.bot.api.gameui.GameUi;
import com.botwithus.bot.api.gameui.Grid;
import com.botwithus.bot.api.gameui.GridProps;
import com.botwithus.bot.api.gameui.InputField;
import com.botwithus.bot.api.gameui.InputFieldProps;
import com.botwithus.bot.api.gameui.JagexComponent;
import com.botwithus.bot.api.gameui.ListBox;
import com.botwithus.bot.api.gameui.ListBoxProps;
import com.botwithus.bot.api.gameui.Panel;
import com.botwithus.bot.api.gameui.PanelProps;
import com.botwithus.bot.api.gameui.Slider;
import com.botwithus.bot.api.gameui.SliderProps;
import com.botwithus.bot.api.gameui.UiEntry;
import com.botwithus.bot.api.gameui.UiHook;
import com.botwithus.bot.api.gameui.UiHookEvent;
import com.botwithus.bot.api.gameui.UiOrder;
import com.botwithus.bot.api.gameui.UiRefusedException;
import com.botwithus.bot.api.gameui.UiSubscription;
import com.botwithus.bot.api.gameui.UiType;
import com.botwithus.bot.api.gameui.UiWidgetState;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.rpc.RpcException;
import com.botwithus.bot.core.rpc.RpcRemoteException;
import com.botwithus.bot.core.runtime.ScriptGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The interactive (P2) half of the plugin-UI binding against a mocked
 * {@link RpcClient}: the new types and their readback, {@code ui.subscribe} /
 * {@code ui.unsubscribe} / {@code ui.blur}, typed delivery on the owner's own event
 * lane, and that a stopped script's or a gone component's subscriptions end without
 * a stray {@code ui.unsubscribe}.
 */
class GameAPIImplGameUiP2Test {

    private static final String ALPHA = "Alpha";
    private static final String BETA = "Beta";
    private static final long JAGEX_HANDLE = 10L;
    private static final long FIRST_CREATED = 100L;
    private static final int IFACE = 1477;
    private static final int COMP = 61;
    private static final long AWAIT_SECONDS = 5L;
    private static final long QUIET_MS = 300L;
    private static final long SLIDER_VALUE = 40L;
    private static final long ENTRY_ID = 7L;
    private static final String SUBSCRIBE = "ui.subscribe";
    private static final String UNSUBSCRIBE = "ui.unsubscribe";
    private static final String SELECTION = "onselectionchanged";
    /** How many methods GameUiAPI declares; noDebugVerbs calls each one. */
    private static final int GAME_UI_API_METHODS = 14;

    private RpcClient rpc;
    private ScriptGate gate;
    private GameAPIImpl api;
    private GameUi ui;
    private final AtomicLong nextHandle = new AtomicLong(FIRST_CREATED);
    private final BlockingQueue<String> heard = new LinkedBlockingQueue<>();
    private final AtomicReference<Runnable> reset = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        rpc = mock(RpcClient.class);
        gate = new ScriptGate();
        api = new GameAPIImpl(rpc);
        api.setScriptGate(gate);
        ui = api.gameUi();
        when(rpc.callSync(eq("ui.get_component"), anyMap()))
                .thenReturn(UiCodecTest.handleMap(JAGEX_HANDLE, "layer", true));
        when(rpc.callSync(eq("ui.create"), anyMap())).thenAnswer(inv -> {
            Map<String, Object> params = inv.getArgument(1);
            return UiCodecTest.handleMap(nextHandle.getAndIncrement(), String.valueOf(params.get("type")), false);
        });
        when(rpc.callSync(eq("ui.destroy"), anyMap())).thenReturn(Map.of("freed", true));
        when(rpc.callSync(eq(SUBSCRIBE), anyMap())).thenReturn(Map.of("subscribed", true));
        when(rpc.callSync(eq(UNSUBSCRIBE), anyMap())).thenReturn(Map.of("unsubscribed", true));
        doAnswer(inv -> {
            reset.set(inv.getArgument(0));
            return null;
        }).when(rpc).addConnectionResetListener(any());
    }

    @AfterEach
    void tearDown() {
        gate.exit();
    }

    private <T> T as(String script, Supplier<T> action) {
        gate.enter(script);
        try {
            return action.get();
        } finally {
            gate.exit();
        }
    }

    private JagexComponent area() {
        return ui.component(IFACE, COMP);
    }

    private Slider sliderOf(String script) {
        return as(script, () -> ui.createSlider(area(), new SliderProps().range(0, 100, 5, 20)));
    }

    private ListBox listOf(String script) {
        return as(script, () -> ui.createListBox(area(), new ListBoxProps()));
    }

    private Consumer<Map<String, Object>> push() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<Map<String, Object>>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(rpc).subscribeTopic(eq("ui.event"), captor.capture(), any());
        return captor.getValue();
    }

    private static Map<String, Object> hookPush(long handle, String hook, Object... args) {
        return Map.of("handle", handle, "hook", hook, "args", List.of(args));
    }

    private static Map<String, Object> hookParams(long handle, String hook) {
        return Map.of("handle", handle, "hook", hook);
    }

    private String next() throws InterruptedException {
        return heard.poll(AWAIT_SECONDS, TimeUnit.SECONDS);
    }

    private void assertNothingMore() throws InterruptedException {
        assertNull(heard.poll(QUIET_MS, TimeUnit.MILLISECONDS), "nothing else may be delivered");
    }

    /** A listener that records the event with the thread it ran on and the gate tag it ran under. */
    private Consumer<UiHookEvent> recorder(String label) {
        return e -> heard.add(label + "|" + e + "|" + Thread.currentThread().getName() + "|" + gate.current());
    }

    @Nested
    @DisplayName("types and readback")
    class Types {

        @Test
        @DisplayName("every interactive type is created with its wire name and its props")
        void createsEveryType() {
            as(ALPHA, () -> {
                ui.createButton(area(), new ButtonProps().label("Go"));
                ui.createCheck(area(), new CheckProps().checked(true));
                ui.createListBox(area(), new ListBoxProps().selectionLimit(1));
                ui.createInputField(area(), new InputFieldProps().maxLength(8));
                ui.createSlider(area(), new SliderProps().range(0, 10, 1, 10));
                ui.createComboField(area(), new ComboFieldProps().entries(List.of(new UiEntry(1, "a"))));
                ui.createPanel(area(), new PanelProps().vertical(true));
                return ui.createGrid(area(), new GridProps());
            });

            for (String type : List.of("button", "check", "list", "input_field", "slider", "combo_field",
                    "panel", "grid")) {
                verify(rpc).callSync(eq("ui.create"), argThat(p -> type.equals(p.get("type"))));
            }
            verify(rpc).callSync(eq("ui.create"), argThat(p -> Map.of("label", "Go").equals(p.get("props"))));
            verify(rpc).callSync(eq("ui.create"), argThat(p -> Map.of("min", 0, "max", 10, "step", 1, "ticks", 10)
                    .equals(p.get("props"))));
        }

        @Test
        @DisplayName("each component reads its own state back, and set sends ui.set")
        void stateReadback() {
            Slider slider = sliderOf(ALPHA);
            Map<String, Object> reply = UiCodecTest.handleMap(slider.handle(), "slider", false);
            reply.putAll(Map.of("min", 0L, "max", 100L, "value", SLIDER_VALUE, "step", 5L, "ticks", 20L,
                    "enabled", false));
            when(rpc.callSync("ui.get", Map.of("handle", slider.handle()))).thenReturn(reply);

            as(ALPHA, () -> {
                slider.set(new SliderProps().value((int) SLIDER_VALUE));
                return null;
            });

            assertAll(
                    () -> assertEquals(new UiWidgetState.SliderState(0, 100, (int) SLIDER_VALUE, 5, 20),
                            as(ALPHA, slider::state)),
                    () -> assertEquals((int) SLIDER_VALUE, as(ALPHA, slider::value)),
                    () -> assertFalse(as(ALPHA, slider::isEnabled)),
                    () -> assertEquals(UiType.SLIDER, slider.info().type()));
            verify(rpc).callSync("ui.set", Map.of("handle", slider.handle(), "props", Map.of("value",
                    (int) SLIDER_VALUE)));
        }

        @Test
        @DisplayName("an input field reports renders false honestly, and its content on request")
        void inputFieldRendersAndContent() {
            InputField field = as(ALPHA, () -> ui.createInputField(area(), new InputFieldProps()));
            Map<String, Object> reply = UiCodecTest.handleMap(field.handle(), "input_field", false);
            reply.putAll(Map.of("focused", false, "filter", "none", "max_length", 80L, "font_id", 494L,
                    "sprite_id", -1L, "renders", false, "content", "typed"));
            when(rpc.callSync(eq("ui.get"), anyMap())).thenReturn(reply);

            assertFalse(as(ALPHA, field::isRendering));
            assertEquals("typed", as(ALPHA, field::content));
            verify(rpc).callSync("ui.get", Map.of("handle", field.handle(), "keys", List.of("content")));
        }

        @Test
        @DisplayName("blur sends ui.blur and returns whether the field had focus; a refusal is verbatim")
        void blur() {
            InputField field = as(ALPHA, () -> ui.createInputField(area(), new InputFieldProps()));
            Button button = as(ALPHA, () -> ui.createButton(area(), new ButtonProps()));
            when(rpc.callSync("ui.blur", Map.of("handle", field.handle()))).thenReturn(Map.of("blurred", true));
            when(rpc.callSync("ui.blur", Map.of("handle", button.handle())))
                    .thenThrow(new RpcRemoteException("ui.blur", "ui: blur applies to input_field components only"));

            assertTrue(as(ALPHA, field::blur));
            UiRefusedException e = assertThrows(UiRefusedException.class,
                    () -> as(ALPHA, () -> api.uiBlur(button.handle())));
            assertEquals("ui: blur applies to input_field components only", e.getMessage());
            assertEquals("ui.blur", e.method());
        }

        @Test
        @DisplayName("an agent refusal of a P2 prop reaches the script verbatim")
        void propRefusal_isVerbatim() {
            when(rpc.callSync(eq("ui.create"), argThat(p -> "check".equals(p.get("type")))))
                    .thenThrow(new RpcRemoteException("ui.create",
                            "ui prop not supported yet: label on check (its text sub-object is not mapped)"));

            UiRefusedException e = assertThrows(UiRefusedException.class,
                    () -> as(ALPHA, () -> ui.createCheck(area(), new CheckProps())));
            assertTrue(e.getMessage().startsWith("ui prop not supported yet: label on check"));
        }
    }

    @Nested
    @DisplayName("subscribe and unsubscribe")
    class Subscriptions {

        @Test
        @DisplayName("the first listener subscribes, the last one closed unsubscribes, and not before")
        void firstSubscribesLastUnsubscribes() {
            Slider slider = sliderOf(ALPHA);
            UiSubscription one = as(ALPHA, () -> slider.onValueChanged(e -> { }));
            UiSubscription two = as(ALPHA, () -> slider.subscribe(UiHook.ON_SELECTION_CHANGED, e -> { }));

            verify(rpc, times(1)).callSync(SUBSCRIBE, hookParams(slider.handle(), SELECTION));
            one.close();
            verify(rpc, never()).callSync(eq(UNSUBSCRIBE), anyMap());
            two.close();
            two.close();
            verify(rpc, times(1)).callSync(UNSUBSCRIBE, hookParams(slider.handle(), SELECTION));
        }

        @Test
        @DisplayName("unsubscribe(hook) drops every listener and returns the agent's answer")
        void unsubscribeHook() throws InterruptedException {
            Slider slider = sliderOf(ALPHA);
            as(ALPHA, () -> slider.subscribe(UiHook.ON_SELECTION_CHANGED, recorder("x")));
            when(rpc.callSync(UNSUBSCRIBE, hookParams(slider.handle(), SELECTION)))
                    .thenReturn(Map.of("unsubscribed", true));

            assertTrue(as(ALPHA, () -> slider.unsubscribe(UiHook.ON_SELECTION_CHANGED)));
            push().accept(hookPush(slider.handle(), SELECTION, SLIDER_VALUE, 1L));
            assertNothingMore();
        }

        @Test
        @DisplayName("unknown handle on unsubscribe is success: false, no throw")
        void unsubscribeUnknownHandle_isFalse() {
            Slider slider = sliderOf(ALPHA);
            when(rpc.callSync(eq(UNSUBSCRIBE), anyMap())).thenThrow(new RpcRemoteException(UNSUBSCRIBE,
                    "ui: unknown handle (destroyed, invalidated or not yours)"));

            assertFalse(as(ALPHA, () -> slider.unsubscribe(UiHook.ON_SELECTION_CHANGED)));
        }

        @Test
        @DisplayName("a refused subscribe registers nothing, so the next one asks the agent again")
        void refusedSubscribe_registersNothing() {
            Button button = as(ALPHA, () -> ui.createButton(area(), new ButtonProps()));
            when(rpc.callSync(SUBSCRIBE, hookParams(button.handle(), "oncontentchanged")))
                    .thenThrow(new RpcRemoteException(SUBSCRIBE, "ui: hook not valid for this type"));

            UiRefusedException e = assertThrows(UiRefusedException.class,
                    () -> as(ALPHA, () -> button.subscribe(UiHook.ON_CONTENT_CHANGED, ev -> { })));
            assertEquals("ui: hook not valid for this type", e.getMessage());
            assertThrows(UiRefusedException.class,
                    () -> as(ALPHA, () -> button.subscribe(UiHook.ON_CONTENT_CHANGED, ev -> { })));
            verify(rpc, times(2)).callSync(SUBSCRIBE, hookParams(button.handle(), "oncontentchanged"));
        }

        @Test
        @DisplayName("a transport failure on subscribe propagates as itself")
        void transportFailure_propagates() {
            Slider slider = sliderOf(ALPHA);
            when(rpc.callSync(eq(SUBSCRIBE), anyMap())).thenThrow(new RpcException("pipe closed"));

            assertThrows(RpcException.class, () -> as(ALPHA, () -> slider.onValueChanged(e -> { })));
        }

        @Test
        @DisplayName("a Jagex handle's subscribe is the agent's to refuse, verbatim")
        void jagexSubscribe_refusedByAgent() {
            when(rpc.callSync(SUBSCRIBE, hookParams(JAGEX_HANDLE, "onclick"))).thenThrow(new RpcRemoteException(
                    SUBSCRIBE, "ui: handle is a Jagex component: read-only"));

            UiRefusedException e = assertThrows(UiRefusedException.class,
                    () -> as(ALPHA, () -> api.uiSubscribe(area().handle(), UiHook.ON_CLICK, ev -> { })));
            assertTrue(e.getMessage().contains("Jagex component"));
        }

        @Test
        @DisplayName("another script's component cannot be subscribed to, and nothing is sent")
        void otherScriptsHandle_isRefusedWithoutACall() {
            Slider beta = sliderOf(BETA);

            UiRefusedException e = assertThrows(UiRefusedException.class,
                    () -> as(ALPHA, () -> beta.subscribe(UiHook.ON_CLICK, ev -> { })));
            assertEquals(GameUiBinding.NOT_YOURS, e.getMessage());
            verify(rpc, never()).callSync(eq(SUBSCRIBE), anyMap());
        }

        @Test
        @DisplayName("every GameUiAPI verb sends only ui.* methods, never a _debug.* one")
        void noDebugVerbs() {
            when(rpc.callSync(eq("ui.get"), anyMap())).thenAnswer(inv ->
                    UiCodecTest.handleMap(FIRST_CREATED, "slider", false));
            when(rpc.callSync(eq("ui.canvas"), anyMap())).thenReturn(Map.of("num_elements", 1L));
            when(rpc.callSync(eq("ui.list"), anyMap())).thenReturn(Map.of("handles", List.of()));
            when(rpc.callSync(eq("ui.blur"), anyMap())).thenReturn(Map.of("blurred", false));
            // One call per GameUiAPI method, in declaration order. Adding a method to
            // GameUiAPI means adding it here; the count below is the reminder.
            int verbs = as(ALPHA, () -> {
                long parent = api.uiGetComponent(IFACE, COMP, -1).handle();
                long made = api.uiCreate(parent, UiType.SLIDER, null, new SliderProps()).handle();
                api.uiSet(made, new SliderProps().value(0));
                api.uiGet(made, true);
                api.uiCanvas(made, List.of(new CanvasOp.Clear()));
                api.uiOrder(made, UiOrder.FRONT);
                api.uiOnEvent(e -> { }).close();
                api.uiSubscribe(made, UiHook.ON_CLICK, e -> { }).close();
                api.uiUnsubscribe(made, UiHook.ON_CLICK);
                api.uiBlur(made);
                api.uiList();
                api.uiDestroy(made);
                api.uiDestroyOwned();
                return api.gameUi() == ui ? GAME_UI_API_METHODS : 0;
            });

            assertEquals(GAME_UI_API_METHODS, verbs);
            ArgumentCaptor<String> methods = ArgumentCaptor.forClass(String.class);
            verify(rpc, atLeastOnce()).callSync(methods.capture(), anyMap());
            assertTrue(methods.getAllValues().stream().allMatch(m -> m.startsWith("ui.")),
                    "non-ui.* method sent: " + methods.getAllValues());
        }
    }

    @Nested
    @DisplayName("typed delivery")
    class Delivery {

        @Test
        @DisplayName("each hook arrives typed, on the owner's own lane, tagged as the owner")
        void everyHookTyped() throws InterruptedException {
            Slider slider = sliderOf(ALPHA);
            ListBox list = listOf(ALPHA);
            InputField field = as(ALPHA, () -> ui.createInputField(area(), new InputFieldProps()));
            Check check = as(ALPHA, () -> ui.createCheck(area(), new CheckProps()));
            ComboField combo = as(ALPHA, () -> ui.createComboField(area(), new ComboFieldProps()));
            as(ALPHA, () -> {
                slider.onValueChanged(e -> heard.add("slider " + e.value() + " " + gate.current() + " "
                        + Thread.currentThread().getName()));
                list.onSelectionChanged(e -> heard.add("list " + e.entryId() + " " + e.kind()));
                field.onContentChanged(e -> heard.add("field " + e.reason() + " " + e.content().orElse("-")));
                check.onPressed(e -> heard.add("check " + e.isOn()));
                combo.onSelectionChanged(e -> heard.add("combo " + e.entryId() + " " + e.kind()));
                return slider.subscribe(UiHook.ON_CLICK, e -> heard.add("click " + e));
            });
            Consumer<Map<String, Object>> push = push();

            push.accept(hookPush(slider.handle(), SELECTION, SLIDER_VALUE, 1L));
            assertEquals("slider 40 " + ALPHA + " ui-event-" + ALPHA, next());
            push.accept(hookPush(list.handle(), SELECTION, ENTRY_ID, 1L));
            assertEquals("list 7 SELECTED", next());
            push.accept(hookPush(field.handle(), "oncontentchanged", 0L, "hello"));
            assertEquals("field CONTENT_CHANGE hello", next());
            push.accept(hookPush(check.handle(), "onbuttonpressed", 1L, 0L));
            assertEquals("check true", next());
            push.accept(hookPush(combo.handle(), SELECTION, ENTRY_ID, 0L));
            assertEquals("combo 7 DESELECTED", next());
            push.accept(hookPush(slider.handle(), "onclick", 3L, 4L));
            assertEquals("click " + new UiHookEvent.Pointer(slider.handle(), UiHook.ON_CLICK, 3, 4), next());
            assertNothingMore();
        }

        @Test
        @DisplayName("an event reaches only the owning script, never another's listener")
        void routedToOwnerOnly() throws InterruptedException {
            Slider alpha = sliderOf(ALPHA);
            Slider beta = sliderOf(BETA);
            as(ALPHA, () -> alpha.subscribe(UiHook.ON_SELECTION_CHANGED, recorder(ALPHA)));
            as(BETA, () -> beta.subscribe(UiHook.ON_SELECTION_CHANGED, recorder(BETA)));

            push().accept(hookPush(beta.handle(), SELECTION, SLIDER_VALUE, 1L));

            String got = next();
            assertTrue(got.startsWith(BETA + "|"), got);
            assertTrue(got.endsWith("|ui-event-" + BETA + "|" + BETA), got);
            assertNothingMore();
        }

        @Test
        @DisplayName("an unsubscribed hook, an unknown handle and malformed args deliver nothing")
        void nothingWithoutAListener() throws InterruptedException {
            Slider slider = sliderOf(ALPHA);
            as(ALPHA, () -> slider.subscribe(UiHook.ON_SELECTION_CHANGED, recorder("x")));
            Consumer<Map<String, Object>> push = push();

            push.accept(hookPush(slider.handle(), "onclick", 1L, 1L));
            push.accept(hookPush(999L, SELECTION, 1L, 1L));
            push.accept(hookPush(slider.handle(), SELECTION, "bad"));

            assertNothingMore();
        }

        @Test
        @DisplayName("a hook this host does not know still reaches onEvent as a UiEvent")
        void unknownHook_reachesOnEvent() throws InterruptedException {
            Slider slider = sliderOf(ALPHA);
            as(ALPHA, () -> ui.onEvent(e -> heard.add("event " + e.hook())));

            push().accept(Map.of("handle", slider.handle(), "hook", "onfuture", "args", List.of()));

            assertEquals("event onfuture", next());
        }

        @Test
        @DisplayName("a throwing listener does not stop the next one")
        void throwingListener_isContained() throws InterruptedException {
            Slider slider = sliderOf(ALPHA);
            as(ALPHA, () -> slider.subscribe(UiHook.ON_SELECTION_CHANGED, e -> {
                throw new IllegalStateException("boom");
            }));
            as(ALPHA, () -> slider.subscribe(UiHook.ON_SELECTION_CHANGED, recorder("after")));

            push().accept(hookPush(slider.handle(), SELECTION, SLIDER_VALUE, 1L));

            assertTrue(next().startsWith("after|"));
        }
    }

    @Nested
    @DisplayName("cleanup")
    class Cleanup {

        @Test
        @DisplayName("a stopped script's subscriptions end with it: no events, and closing sends nothing")
        void release_endsSubscriptionsWithoutUnsubscribe() throws InterruptedException {
            Slider slider = sliderOf(ALPHA);
            UiSubscription sub = as(ALPHA, () -> slider.subscribe(UiHook.ON_SELECTION_CHANGED, recorder("x")));
            Consumer<Map<String, Object>> push = push();

            api.releaseGameUi(ALPHA);
            push.accept(hookPush(slider.handle(), SELECTION, SLIDER_VALUE, 1L));
            sub.close();

            assertNothingMore();
            verify(rpc).callSync("ui.destroy", Map.of("handle", slider.handle()));
            verify(rpc, never()).callSync(eq(UNSUBSCRIBE), anyMap());
        }

        @Test
        @DisplayName("a component announced destroyed drops its listeners without an unsubscribe")
        void destroyedEvent_dropsListeners() throws InterruptedException {
            Slider slider = sliderOf(ALPHA);
            UiSubscription sub = as(ALPHA, () -> slider.subscribe(UiHook.ON_SELECTION_CHANGED, recorder("x")));
            Consumer<Map<String, Object>> push = push();

            push.accept(Map.of("handle", slider.handle(), "hook", "destroyed", "reason", "parent_gone"));
            push.accept(hookPush(slider.handle(), SELECTION, SLIDER_VALUE, 1L));
            sub.close();

            assertNothingMore();
            verify(rpc, never()).callSync(eq(UNSUBSCRIBE), anyMap());
        }

        @Test
        @DisplayName("a component the script destroyed drops its listeners without an unsubscribe")
        void explicitDestroy_dropsListeners() {
            Slider slider = sliderOf(ALPHA);
            UiSubscription sub = as(ALPHA, () -> slider.onValueChanged(e -> { }));

            as(ALPHA, slider::destroy);
            sub.close();

            verify(rpc, never()).callSync(eq(UNSUBSCRIBE), anyMap());
        }

        @Test
        @DisplayName("after a reset, an old listener never hears a new component that reuses the agent number")
        void reset_dropsListeners() throws InterruptedException {
            Slider old = sliderOf(ALPHA);
            UiSubscription sub = as(ALPHA, () -> old.subscribe(UiHook.ON_SELECTION_CHANGED, recorder("old")));

            reset.get().run();
            nextHandle.set(FIRST_CREATED);
            Slider reused = sliderOf(ALPHA);
            push().accept(hookPush(FIRST_CREATED, SELECTION, SLIDER_VALUE, 1L));
            sub.close();

            assertEquals(FIRST_CREATED, UiHandleSpace.agentOf(reused.handle()), "the agent reused the number");
            assertNothingMore();
            assertFalse(as(ALPHA, () -> old.unsubscribe(UiHook.ON_SELECTION_CHANGED)),
                    "a pre-reset handle answers false, not a refusal");
            verify(rpc, never()).callSync(eq(UNSUBSCRIBE), anyMap());
        }

        @Test
        @DisplayName("a release run on its own does not touch another script's subscriptions")
        void release_leavesOthers() throws InterruptedException {
            Slider alpha = sliderOf(ALPHA);
            Slider beta = sliderOf(BETA);
            as(ALPHA, () -> alpha.subscribe(UiHook.ON_SELECTION_CHANGED, recorder(ALPHA)));
            as(BETA, () -> beta.subscribe(UiHook.ON_SELECTION_CHANGED, recorder(BETA)));

            api.releaseGameUi(ALPHA);
            push().accept(hookPush(beta.handle(), SELECTION, SLIDER_VALUE, 1L));

            assertTrue(next().startsWith(BETA + "|"));
            verify(rpc, never()).callSync("ui.destroy", Map.of("handle", beta.handle()));
        }
    }

    @Nested
    @DisplayName("review fixes")
    class ReviewFixes {

        @Test
        @DisplayName("M1: a hook event queued before a release never runs once the release has begun")
        void release_queuedEventNeverRuns() throws Exception {
            Slider slider = sliderOf(ALPHA);
            CountDownLatch laneHeld = new CountDownLatch(1);
            CountDownLatch openLane = new CountDownLatch(1);
            CountDownLatch destroyStarted = new CountDownLatch(1);
            CountDownLatch finishDestroy = new CountDownLatch(1);
            AtomicInteger runs = new AtomicInteger();
            as(ALPHA, () -> slider.onValueChanged(e -> {
                if (runs.incrementAndGet() == 1) {
                    laneHeld.countDown();
                    awaitQuietly(openLane);
                }
            }));
            when(rpc.callSync("ui.destroy", Map.of("handle", slider.handle()))).thenAnswer(inv -> {
                destroyStarted.countDown();
                awaitQuietly(finishDestroy);
                return Map.of("freed", true);
            });
            Consumer<Map<String, Object>> push = push();

            push.accept(hookPush(slider.handle(), SELECTION, SLIDER_VALUE, 1L));
            assertTrue(laneHeld.await(AWAIT_SECONDS, TimeUnit.SECONDS), "the first event never ran");
            push.accept(hookPush(slider.handle(), SELECTION, SLIDER_VALUE, 1L));
            Thread release = Thread.ofPlatform().start(() -> api.releaseGameUi(ALPHA));
            assertTrue(destroyStarted.await(AWAIT_SECONDS, TimeUnit.SECONDS), "the release never reached destroy");
            openLane.countDown();
            Thread.sleep(QUIET_MS);

            assertEquals(1, runs.get(), "the queued event ran after the script was released");
            finishDestroy.countDown();
            release.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));
        }

        @Test
        @DisplayName("M2: a revoked script's subscribe is refused before any lock or call")
        void revoked_isRefusedWithoutACall() {
            Slider slider = sliderOf(ALPHA);
            gate.revoke(ALPHA);

            UiRefusedException e = assertThrows(UiRefusedException.class,
                    () -> as(ALPHA, () -> slider.onValueChanged(ev -> { })));
            assertEquals(GameUiBinding.REVOKED, e.getMessage());
            verify(rpc, never()).callSync(eq(SUBSCRIBE), anyMap());
        }

        @Test
        @DisplayName("M2: one owner's slow subscribe does not hold up another owner's")
        void slowSubscribe_doesNotBlockAnotherHandle() throws Exception {
            Slider alpha = sliderOf(ALPHA);
            Slider beta = sliderOf(BETA);
            CountDownLatch alphaInCall = new CountDownLatch(1);
            CountDownLatch releaseAlpha = new CountDownLatch(1);
            when(rpc.callSync(SUBSCRIBE, hookParams(alpha.handle(), SELECTION))).thenAnswer(inv -> {
                alphaInCall.countDown();
                awaitQuietly(releaseAlpha);
                return Map.of("subscribed", true);
            });
            Thread slow = Thread.ofPlatform().start(() -> as(ALPHA, () -> alpha.onValueChanged(e -> { })));
            assertTrue(alphaInCall.await(AWAIT_SECONDS, TimeUnit.SECONDS));

            CompletableFuture<UiSubscription> fast = CompletableFuture.supplyAsync(
                    () -> as(BETA, () -> beta.onValueChanged(e -> { })));
            try {
                assertNotNull(fast.get(QUIET_MS, TimeUnit.MILLISECONDS), "Beta's subscribe waited on Alpha's call");
            } finally {
                releaseAlpha.countDown();
                slow.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));
            }
        }

        @Test
        @DisplayName("L1a: host code cannot subscribe to a script's component, and nothing is sent")
        void hostOnScriptHandle_isRefused() {
            Slider slider = sliderOf(ALPHA);

            UiRefusedException e = assertThrows(UiRefusedException.class,
                    () -> api.uiSubscribe(slider.handle(), UiHook.ON_CLICK, ev -> { }));
            assertEquals(GameUiBinding.NOT_YOURS, e.getMessage());
            verify(rpc, never()).callSync(eq(SUBSCRIBE), anyMap());
        }

        @Test
        @DisplayName("L1b: a reset while the subscribe is in flight refuses it, and the listener never lands")
        void resetDuringSubscribe_isRefused() throws InterruptedException {
            Slider old = sliderOf(ALPHA);
            when(rpc.callSync(SUBSCRIBE, hookParams(FIRST_CREATED, SELECTION))).thenAnswer(inv -> {
                reset.get().run();
                nextHandle.set(FIRST_CREATED);
                sliderOf(ALPHA);
                return Map.of("subscribed", true);
            });

            UiRefusedException e = assertThrows(UiRefusedException.class,
                    () -> as(ALPHA, () -> old.subscribe(UiHook.ON_SELECTION_CHANGED, recorder("old"))));
            assertEquals(GameUiBinding.STALE, e.getMessage());
            push().accept(hookPush(FIRST_CREATED, SELECTION, SLIDER_VALUE, 1L));
            assertNothingMore();
        }

        @Test
        @DisplayName("L2: a reset drops every hook record, whatever reuses the numbers later")
        void reset_clearsHookRecords() {
            GameUiBinding binding = new GameUiBinding(rpc, () -> gate, () -> null, "host", 1);
            gate.enter(ALPHA);
            long parent = binding.getComponent(IFACE, COMP, -1).handle();
            long made = binding.create(parent, UiType.SLIDER, null, new SliderProps()).handle();
            binding.subscribe(made, UiHook.ON_CLICK, e -> { });
            gate.exit();
            assertEquals(1, binding.hookedHandleCount());

            binding.onConnectionReset();

            assertEquals(0, binding.hookedHandleCount());
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(AWAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
