package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.diag.StubGuard;
import com.botwithus.bot.api.gameui.Button;
import com.botwithus.bot.api.gameui.ButtonProps;
import com.botwithus.bot.api.gameui.Check;
import com.botwithus.bot.api.gameui.CheckProps;
import com.botwithus.bot.api.gameui.ContentChangeReason;
import com.botwithus.bot.api.gameui.GameUi;
import com.botwithus.bot.api.gameui.InputField;
import com.botwithus.bot.api.gameui.InputFieldProps;
import com.botwithus.bot.api.gameui.JagexComponent;
import com.botwithus.bot.api.gameui.ListBox;
import com.botwithus.bot.api.gameui.ListBoxProps;
import com.botwithus.bot.api.gameui.ListEntry;
import com.botwithus.bot.api.gameui.Panel;
import com.botwithus.bot.api.gameui.PanelProps;
import com.botwithus.bot.api.gameui.SelectionChange;
import com.botwithus.bot.api.gameui.Slider;
import com.botwithus.bot.api.gameui.SliderProps;
import com.botwithus.bot.api.gameui.UiEntry;
import com.botwithus.bot.api.gameui.UiHandleInfo;
import com.botwithus.bot.api.gameui.UiHookEvent;
import com.botwithus.bot.api.gameui.UiWidgetState;
import com.botwithus.bot.api.gameval.GamevalIndex;
import com.botwithus.bot.api.model.ComponentRef;
import com.botwithus.bot.core.pipe.AgentSelection;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.rpc.RpcRemoteException;
import com.botwithus.bot.core.runtime.ConnectionContext;
import com.botwithus.bot.core.runtime.ScriptGate;
import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.core.runtime.ScriptRuntime;
import com.botwithus.bot.core.test.LiveAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Interactive plugin UI (P2) end to end against a real client: a script builds a
 * panel holding a button, a check, a slider, a list and an input field, sets and
 * reads them back, subscribes, and receives typed events; then it stops and a
 * <b>separate</b> connection proves its components are gone from the game.
 *
 * <p>What raises which event, as the agent documents it and this test pins:</p>
 * <ul>
 *   <li>The script's own {@code content} set raises {@code oncontentchanged}: the
 *       client does that itself, so the script's listener gets a typed
 *       {@code ContentChanged} from a pure script-API call.</li>
 *   <li>The script's own slider {@code value} and list {@code selected} sets raise
 *       <b>nothing</b>: the agent applies them without the client's notify flag. The
 *       test waits and asserts that.</li>
 *   <li>To still show a real {@code onselectionchanged} reach the script's listeners
 *       typed, the <b>test</b> (as the host, never the script, and never through the
 *       API) sends the agent's test verb {@code _debug.ui_set_notify} on the same
 *       connection. That runs the client's own notify path, exactly as the agent's
 *       {@code plugin-ui-p2-selection-events} scenario does. Clicks stay out of
 *       scope.</li>
 * </ul>
 *
 * <p>Needs a logged-in character. Opt in with {@code -Dbotwithus.smoke.live=true};
 * the harness runs it as {@code -HostTests "*LiveGameUiP2*"}. Draws on screen for a
 * few seconds and clicks nothing.</p>
 */
class LiveGameUiP2SmokeTest {

    private static final Logger log = LoggerFactory.getLogger(LiveGameUiP2SmokeTest.class);

    /** {@code TOPLEVEL_V2__GAME_AREA}, as the agent's scenarios pin it. */
    private static final ComponentRef GAME_AREA = new ComponentRef(1477, 61);
    private static final String SCRIPT = "Plugin UI P2 Smoke";
    private static final String DEBUG_SET_NOTIFY = "_debug.ui_set_notify";
    private static final String NOT_FOUND = "component not found";
    private static final long AWAIT_SECONDS = 20L;
    private static final long EVENT_SECONDS = 5L;
    /** How long the test waits for an event the agent says a script's own set does not raise. */
    private static final long QUIET_MS = 2_000L;
    private static final long STOP_TIMEOUT_MS = 10_000L;
    private static final int LOOP_DELAY_MS = 100;

    private static final int PANEL_X = 40;
    private static final int PANEL_Y = 60;
    private static final int PANEL_W = 260;
    private static final int PANEL_H = 320;
    private static final int ROW_W = 200;
    private static final int ROW_H = 30;
    private static final int LIST_H = 90;
    private static final String LABEL = "BotWithUs P2";
    private static final String CONTENT = "hello p2";
    private static final int MAX_LENGTH = 40;
    private static final int SLIDER_MIN = 0;
    private static final int SLIDER_MAX = 100;
    private static final int SLIDER_STEP = 5;
    private static final int SLIDER_TICKS = 20;
    private static final int SLIDER_START = 25;
    private static final int SLIDER_SET = 60;
    private static final int SLIDER_NOTIFIED = 35;
    private static final int LIST_SET = 2;
    private static final int LIST_NOTIFIED = 3;
    private static final List<UiEntry> ENTRIES = List.of(new UiEntry(1, "one"), new UiEntry(LIST_SET, "two"),
            new UiEntry(LIST_NOTIFIED, "three"));

    /** What the script built and read, handed back to the test thread. */
    private record Built(List<UiHandleInfo> made, UiWidgetState.ButtonState button, boolean isChecked,
                         UiWidgetState.SliderState slider, UiWidgetState.ListBoxState list,
                         UiWidgetState.InputFieldState field, String content, boolean wasFocused,
                         UiWidgetState.PanelState panel) {
    }

    /** One delivered event, with the thread and script tag it was delivered under. */
    private record Delivered(UiHookEvent event, String thread) {
    }

    @ScriptManifest(name = SCRIPT, version = "1.0", author = "test", description = "builds P2 components")
    private static final class Builder implements BotScript {
        private final CompletableFuture<Built> built = new CompletableFuture<>();
        private final BlockingQueue<Delivered> events = new LinkedBlockingQueue<>();

        @Override public void onStart(ScriptContext context) {
            try {
                built.complete(build(context.getGameAPI().gameUi()));
            } catch (RuntimeException e) {
                built.completeExceptionally(e);
            }
        }

        private void record(UiHookEvent event) {
            events.add(new Delivered(event, Thread.currentThread().getName()));
        }

        private Built build(GameUi ui) {
            JagexComponent area = ui.component(GAME_AREA.interfaceId(), GAME_AREA.componentId());
            Panel panel = ui.createPanel(area, new PanelProps().vertical(true).at(PANEL_X, PANEL_Y)
                    .size(PANEL_W, PANEL_H));
            Button button = ui.createButton(panel, new ButtonProps().label(LABEL).canToggle(true).toggled(true)
                    .size(ROW_W, ROW_H));
            Check check = ui.createCheck(panel, new CheckProps().checked(true).size(ROW_H, ROW_H));
            Slider slider = ui.createSlider(panel, new SliderProps()
                    .range(SLIDER_MIN, SLIDER_MAX, SLIDER_STEP, SLIDER_TICKS).value(SLIDER_START).size(ROW_W, ROW_H));
            ListBox list = ui.createListBox(panel, new ListBoxProps().entries(ENTRIES).selectionLimit(1)
                    .size(ROW_W, LIST_H));
            InputField field = ui.createInputField(panel, new InputFieldProps().maxLength(MAX_LENGTH)
                    .size(ROW_W, ROW_H));
            slider.onValueChanged(this::record);
            list.onSelectionChanged(this::record);
            field.onContentChanged(this::record);
            field.set(new InputFieldProps().content(CONTENT));
            slider.set(new SliderProps().value(SLIDER_SET));
            list.set(new ListBoxProps().selected(LIST_SET));
            List<UiHandleInfo> made = List.of(panel.info(), button.info(), check.info(), slider.info(),
                    list.info(), field.info());
            return new Built(made, button.state(), check.isChecked(), slider.state(), list.state(),
                    field.state(), field.content(), field.blur(), panel.state());
        }

        @Override public int onLoop() {
            return LOOP_DELAY_MS;
        }

        @Override public void onStop() {}
    }

    @Test
    @EnabledIfSystemProperty(named = "botwithus.smoke.live", matches = "true")
    void interactiveComponentsRaiseTypedEventsAndGoWithTheScript() throws Exception {
        AgentSelection.Attach agent = LiveAgent.attachOrFail();
        log.info("Connected to {} (pid={})", agent.pipeName(), agent.pid());
        try (PipeClient pipe = new PipeClient(agent.pipeName());
             PipeClient observerPipe = new PipeClient(agent.pipeName())) {
            RpcClient rpc = new RpcClient(pipe);
            RpcClient observer = new RpcClient(observerPipe);
            rpc.start();
            observer.start();
            try {
                GameAPIImpl gameAPI = new GameAPIImpl(rpc, null, null, new StubGuard(), e -> { },
                        GamevalIndex.empty());
                runProbe(gameAPI, rpc, observer);
            } finally {
                observer.close();
                rpc.close();
            }
        }
    }

    private void runProbe(GameAPIImpl gameAPI, RpcClient rpc, RpcClient observer) throws Exception {
        ScriptGate gate = new ScriptGate();
        ScriptRuntime runtime = new ScriptRuntime(new ScriptContextImpl(gameAPI, new EventBusImpl(),
                new MessageBusImpl()), ConnectionContext::set, ConnectionContext::clear);
        runtime.setScriptGate(gate);
        gameAPI.setScriptGate(gate);
        runtime.setGameUiLifecycle(gameAPI.gameUiLifecycle());
        Builder builder = new Builder();
        try {
            runtime.startScript(builder);
            Built built = builder.built.get(AWAIT_SECONDS, TimeUnit.SECONDS);
            assertReadBack(built);
            assertScriptSetEvents(builder.events);
            assertNotifiedEvents(rpc, built, builder.events);
            UiHandleInfo panel = built.made().getFirst();
            List<UiHandleInfo> nested = foundNested(observer, built);
            assertFoundByObserver(observer, panel);

            ScriptRunner runner = runtime.findRunner(SCRIPT);
            runner.stop();
            assertTrue(runner.awaitStop(STOP_TIMEOUT_MS), "the script did not stop");

            assertGoneForObserver(observer, panel);
            nested.forEach(child -> assertGoneForObserver(observer, child));
            assertHostListAfterStop(gameAPI, built);
        } finally {
            runtime.stopAll();
        }
    }

    private static void assertReadBack(Built b) {
        log.info("made {}; input field {} (renders={}); content '{}'", b.made(), b.field(), b.field().isRendering(),
                b.content());
        assertAll(
                () -> assertEquals(LABEL, b.button().label()),
                () -> assertTrue(b.button().canToggle()),
                () -> assertTrue(b.button().isToggled()),
                () -> assertTrue(b.isChecked()),
                () -> assertEquals(new UiWidgetState.SliderState(SLIDER_MIN, SLIDER_MAX, SLIDER_SET, SLIDER_STEP,
                        SLIDER_TICKS), b.slider()),
                () -> assertEquals(ENTRIES, b.list().entries().stream().map(ListEntry::entry).toList()),
                () -> assertEquals(List.of(LIST_SET), b.list().selected()),
                () -> assertEquals(1, b.list().selectionLimit()),
                () -> assertEquals(MAX_LENGTH, b.field().maxLength()),
                () -> assertFalse(b.field().isFocused()),
                () -> assertEquals(CONTENT, b.content()),
                () -> assertFalse(b.wasFocused(), "nothing focused the field, so blur had nothing to clear"),
                () -> assertTrue(b.panel().isVertical()));
    }

    /** The content set raised a typed event on the script's lane; the slider and list sets raised none. */
    private static void assertScriptSetEvents(BlockingQueue<Delivered> events) throws InterruptedException {
        Delivered content = events.poll(EVENT_SECONDS, TimeUnit.SECONDS);
        assertNotNull(content, "the script's own content set should raise oncontentchanged");
        log.info("script-set event: {} on {}", content.event(), content.thread());
        UiHookEvent.ContentChanged changed = switch (content.event()) {
            case UiHookEvent.ContentChanged c -> c;
            default -> throw new AssertionError("expected ContentChanged, got " + content.event());
        };
        assertAll(
                () -> assertEquals(ContentChangeReason.CONTENT_CHANGE, changed.reason()),
                () -> assertEquals(CONTENT, changed.content().orElse(null)),
                () -> assertEquals("ui-event-" + SCRIPT, content.thread()));
        List<Delivered> more = new ArrayList<>();
        Delivered extra = events.poll(QUIET_MS, TimeUnit.MILLISECONDS);
        while (extra != null) {
            more.add(extra);
            extra = events.poll(1, TimeUnit.MILLISECONDS);
        }
        assertTrue(more.isEmpty(), "a script's own value / selected set raised " + more);
    }

    /** The test's notify sets (as the host, not the API) reach the script's listeners typed. */
    private static void assertNotifiedEvents(RpcClient rpc, Built built, BlockingQueue<Delivered> events)
            throws InterruptedException {
        UiHandleInfo slider = built.made().get(3);
        UiHandleInfo list = built.made().get(4);
        rpc.callSync(DEBUG_SET_NOTIFY, Map.of("handle", slider.handle(), "props", Map.of("value", SLIDER_NOTIFIED)));
        Delivered sliderEvent = events.poll(EVENT_SECONDS, TimeUnit.SECONDS);
        rpc.callSync(DEBUG_SET_NOTIFY, Map.of("handle", list.handle(), "props",
                Map.of("selected", List.of(LIST_NOTIFIED))));
        List<Delivered> listEvents = drain(events);
        log.info("notified events: {} then {}", sliderEvent, listEvents);
        assertNotNull(sliderEvent, "no onselectionchanged for the slider");
        assertEquals(new UiHookEvent.SliderChanged(slider.handle(), SLIDER_NOTIFIED), sliderEvent.event());
        assertEquals("ui-event-" + SCRIPT, sliderEvent.thread());
        assertTrue(listEvents.stream().anyMatch(d -> d.event().equals(
                new UiHookEvent.SelectionChanged(list.handle(), LIST_NOTIFIED, SelectionChange.SELECTED.code()))),
                "no SELECTED for list entry " + LIST_NOTIFIED + ": " + listEvents);
    }

    /** Every event that arrives within the event window; a list select can also deselect the old entry. */
    private static List<Delivered> drain(BlockingQueue<Delivered> events) throws InterruptedException {
        List<Delivered> out = new ArrayList<>();
        Delivered next = events.poll(EVENT_SECONDS, TimeUnit.SECONDS);
        while (next != null) {
            out.add(next);
            next = events.poll(QUIET_MS, TimeUnit.MILLISECONDS);
        }
        return out;
    }

    private static Map<String, Object> lookup(UiHandleInfo child) {
        return Map.of("iface", GAME_AREA.interfaceId(), "comp", GAME_AREA.componentId(), "key", child.key());
    }

    /**
     * The panel's children the observer can find by key before the stop, so a not-found
     * after it means something. Logged, because a nested child may not be reachable by
     * a key lookup under the game area at all.
     */
    private static List<UiHandleInfo> foundNested(RpcClient observer, Built built) {
        List<UiHandleInfo> found = new ArrayList<>();
        for (UiHandleInfo child : built.made().subList(1, built.made().size())) {
            try {
                Map<String, Object> reply = observer.callSync("ui.get_component", lookup(child));
                if (child.type().wireName().equals(reply.get("type_name"))) {
                    found.add(child);
                }
            } catch (RpcRemoteException e) {
                log.info("observer lookup of nested {} (key {}): {}", child.type(), child.key(), e.producerMessage());
            }
        }
        log.info("observer can see {} of {} nested children by key", found.size(), built.made().size() - 1);
        return found;
    }

    private static void assertFoundByObserver(RpcClient observer, UiHandleInfo child) {
        Map<String, Object> found = observer.callSync("ui.get_component", lookup(child));
        assertEquals(child.type().wireName(), found.get("type_name"),
                "the observer connection should find key " + child.key() + " in the game");
    }

    private static void assertGoneForObserver(RpcClient observer, UiHandleInfo child) {
        RpcRemoteException e = assertThrows(RpcRemoteException.class,
                () -> observer.callSync("ui.get_component", lookup(child)),
                child.type() + " key " + child.key() + " is still in the game's registry");
        assertTrue(e.producerMessage().contains(NOT_FOUND), e.producerMessage());
    }

    /** From the host (untagged), none of the script's handles is left on its connection. */
    private static void assertHostListAfterStop(GameAPIImpl gameAPI, Built built) {
        List<Long> live = gameAPI.uiList().stream().map(UiHandleInfo::handle).toList();
        log.info("host ui.list after the stop: {}", live);
        for (UiHandleInfo made : built.made()) {
            assertFalse(live.contains(made.handle()), made.type() + " survived the stop");
        }
    }
}
