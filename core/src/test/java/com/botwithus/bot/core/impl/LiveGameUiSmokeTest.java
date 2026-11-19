package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.diag.StubGuard;
import com.botwithus.bot.api.gameui.Canvas;
import com.botwithus.bot.api.gameui.CanvasProps;
import com.botwithus.bot.api.gameui.GameUi;
import com.botwithus.bot.api.gameui.JagexComponent;
import com.botwithus.bot.api.gameui.Layer;
import com.botwithus.bot.api.gameui.LayerProps;
import com.botwithus.bot.api.gameui.Rectangle;
import com.botwithus.bot.api.gameui.RectangleProps;
import com.botwithus.bot.api.gameui.Text;
import com.botwithus.bot.api.gameui.TextProps;
import com.botwithus.bot.api.gameui.UiHandleInfo;
import com.botwithus.bot.api.gameui.UiReadback;
import com.botwithus.bot.api.gameval.GamevalIndex;
import com.botwithus.bot.api.model.ComponentRef;
import com.botwithus.bot.core.gameval.SqliteGamevalIndex;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plugin UI end to end against a real client: two scripts share one connection,
 * one of them draws, reads back and stops, and a <b>separate</b> connection proves
 * its components are gone from the game while the other script's survive.
 *
 * <p>Why each piece is here:</p>
 * <ul>
 *   <li><b>Two scripts on one pipe.</b> The agent cleans up per connection, so a
 *       single script whose connection closes would pass even with no host
 *       cleanup at all. The first connection stays open across the stop, and the
 *       second script's rectangle must still be in the game afterwards: that is
 *       what shows the host destroyed by handle and did not send
 *       {@code ui.destroy_all}.</li>
 *   <li><b>A second connection for the proof.</b> {@code ui.list} only shows the
 *       caller's own handles, so it cannot see across connections. The check is a
 *       raw lookup through the game's own registry ({@code ui.get_component} with
 *       the child's key), as NXTLibrary's {@code plugin-ui-create-draw} scenario
 *       does. It is made <em>before</em> the stop too, so the not-found after it is
 *       not a lookup that could never succeed.</li>
 * </ul>
 *
 * <p>Needs a logged-in character: {@code TOPLEVEL_V2__GAME_AREA} exists only in
 * the world. Opt in with {@code -Dbotwithus.smoke.live=true}; the harness runs it
 * as {@code -HostTests "*LiveGameUi*"}. Draws on screen for a few seconds and
 * clicks nothing.</p>
 */
class LiveGameUiSmokeTest {

    private static final Logger log = LoggerFactory.getLogger(LiveGameUiSmokeTest.class);

    private static final String GAME_AREA = "TOPLEVEL_V2__GAME_AREA";
    /** {@code TOPLEVEL_V2__GAME_AREA}, as the agent's scenarios pin it; used when no gameval index is deployed. */
    private static final ComponentRef GAME_AREA_FALLBACK = new ComponentRef(1477, 61);
    private static final String DRAWER = "Plugin UI Smoke Drawer";
    private static final String KEEPER = "Plugin UI Smoke Keeper";
    private static final long AWAIT_SECONDS = 20L;
    private static final long STOP_TIMEOUT_MS = 10_000L;
    private static final int LOOP_DELAY_MS = 100;

    private static final int LAYER_X = 40;
    private static final int LAYER_Y = 60;
    private static final int LAYER_W = 360;
    private static final int LAYER_H = 240;
    private static final int CANVAS_W = 320;
    private static final int CANVAS_H = 160;
    private static final int CANVAS_Y = 40;
    private static final int TEXT_W = 300;
    private static final int TEXT_H = 24;
    private static final int OPS_DRAWN = 6;
    private static final int THICK = 3;
    private static final int TRANSLUCENT_BLUE = 0x2000_0080;
    private static final int CYAN = 0x00FF_FFFF;
    private static final int RED = 0xFF00_00FF;
    private static final int WHITE = 0xFFFF_FFFF;
    private static final int KEEPER_X = 420;
    private static final int KEEPER_SIZE = 60;
    private static final String LABEL = "BotWithUs Java plugin-ui";
    private static final String NOT_FOUND = "component not found";

    /** What the drawing script saw, handed back to the test thread. */
    private record Drawn(UiHandleInfo layer, UiHandleInfo text, UiHandleInfo canvas,
                         UiReadback layerBack, UiReadback canvasBack, String content, int afterUndo) {
    }

    @ScriptManifest(name = DRAWER, version = "1.0", author = "test", description = "draws, then waits")
    private static final class Drawer implements BotScript {
        private final ComponentRef gameArea;
        private final CompletableFuture<Drawn> drawn = new CompletableFuture<>();

        Drawer(ComponentRef gameArea) {
            this.gameArea = gameArea;
        }

        @Override public void onStart(ScriptContext context) {
            try {
                drawn.complete(draw(context.getGameAPI().gameUi()));
            } catch (RuntimeException e) {
                drawn.completeExceptionally(e);
            }
        }

        private Drawn draw(GameUi ui) {
            JagexComponent area = ui.component(gameArea.interfaceId(), gameArea.componentId());
            Layer layer = ui.createLayer(area, new LayerProps().at(LAYER_X, LAYER_Y).size(LAYER_W, LAYER_H));
            Text text = ui.createText(layer, new TextProps().text(LABEL).at(0, 0).size(TEXT_W, TEXT_H)
                    .rgba(WHITE));
            Canvas canvas = ui.createCanvas(layer, new CanvasProps().at(0, CANVAS_Y).size(CANVAS_W, CANVAS_H));
            canvas.draw()
                    .rect(0, 0, CANVAS_W, CANVAS_H, TRANSLUCENT_BLUE)
                    .outline(0, 0, CANVAS_W, CANVAS_H, 2, CYAN)
                    .line(0, 0, CANVAS_W, CANVAS_H, THICK, RED)
                    .gradientH(0, 0, CANVAS_W / 2, TEXT_H, RED, CYAN)
                    .gradientV(CANVAS_W / 2, 0, TEXT_H, CANVAS_H, CYAN, RED)
                    .triangle(0, CANVAS_H, CANVAS_W / 2, 0, CANVAS_W, CANVAS_H, WHITE)
                    .submit();
            int afterUndo = canvas.draw().undo().submit();
            return new Drawn(layer.info(), text.info(), canvas.info(), layer.readback(),
                    canvas.readback(), text.content(), afterUndo);
        }

        @Override public int onLoop() {
            return LOOP_DELAY_MS;
        }

        @Override public void onStop() {}
    }

    @ScriptManifest(name = KEEPER, version = "1.0", author = "test", description = "keeps one rectangle")
    private static final class Keeper implements BotScript {
        private final ComponentRef gameArea;
        private final CompletableFuture<UiHandleInfo> made = new CompletableFuture<>();

        Keeper(ComponentRef gameArea) {
            this.gameArea = gameArea;
        }

        @Override public void onStart(ScriptContext context) {
            try {
                GameUi ui = context.getGameAPI().gameUi();
                JagexComponent area = ui.component(gameArea.interfaceId(), gameArea.componentId());
                Rectangle rect = ui.createRectangle(area, new RectangleProps().at(KEEPER_X, LAYER_Y)
                        .size(KEEPER_SIZE, KEEPER_SIZE).rgba(CYAN).fill(true));
                made.complete(rect.info());
            } catch (RuntimeException e) {
                made.completeExceptionally(e);
            }
        }

        @Override public int onLoop() {
            return LOOP_DELAY_MS;
        }

        @Override public void onStop() {}
    }

    @Test
    @EnabledIfSystemProperty(named = "botwithus.smoke.live", matches = "true")
    void stoppedScriptsComponentsAreGoneAndTheOtherScriptsAreNot() throws Exception {
        AgentSelection.Attach agent = LiveAgent.attachOrFail();
        log.info("Connected to {} (pid={})", agent.pipeName(), agent.pid());
        try (GamevalIndex gamevals = openGamevals();
             PipeClient pipe = new PipeClient(agent.pipeName());
             PipeClient observerPipe = new PipeClient(agent.pipeName())) {
            ComponentRef gameArea = resolveGameArea(gamevals);
            RpcClient rpc = new RpcClient(pipe);
            RpcClient observer = new RpcClient(observerPipe);
            rpc.start();
            observer.start();
            try {
                GameAPIImpl gameAPI = new GameAPIImpl(rpc, null, null, new StubGuard(), e -> { },
                        gamevals);
                runProbe(gameAPI, observer, gameArea);
            } finally {
                observer.close();
                rpc.close();
            }
        }
    }

    private static GamevalIndex openGamevals() {
        Optional<SqliteGamevalIndex> index = SqliteGamevalIndex.openDefault();
        return index.<GamevalIndex>map(i -> i).orElseGet(GamevalIndex::empty);
    }

    private static ComponentRef resolveGameArea(GamevalIndex gamevals) {
        Optional<ComponentRef> named = gamevals.component(GAME_AREA);
        log.info("{} resolves to {} (index available: {})", GAME_AREA, named, gamevals.isAvailable());
        named.ifPresent(ref -> assertEquals(GAME_AREA_FALLBACK, ref,
                "the gameval index and the agent's scenarios disagree about " + GAME_AREA));
        return named.orElse(GAME_AREA_FALLBACK);
    }

    private void runProbe(GameAPIImpl gameAPI, RpcClient observer, ComponentRef gameArea) throws Exception {
        ScriptGate gate = new ScriptGate();
        ScriptRuntime runtime = new ScriptRuntime(new ScriptContextImpl(gameAPI, new EventBusImpl(),
                new MessageBusImpl()), ConnectionContext::set, ConnectionContext::clear);
        runtime.setScriptGate(gate);
        gameAPI.setScriptGate(gate);
        runtime.setGameUiReleaser(gameAPI::releaseGameUi);
        Drawer drawer = new Drawer(gameArea);
        Keeper keeper = new Keeper(gameArea);
        try {
            runtime.startScript(drawer);
            runtime.startScript(keeper);
            Drawn drawn = drawer.drawn.get(AWAIT_SECONDS, TimeUnit.SECONDS);
            UiHandleInfo kept = keeper.made.get(AWAIT_SECONDS, TimeUnit.SECONDS);
            assertDrawnAndReadBack(drawn);
            assertFoundByObserver(observer, gameArea, drawn.layer());
            assertFoundByObserver(observer, gameArea, kept);

            ScriptRunner runner = runtime.findRunner(DRAWER);
            runner.stop();
            assertTrue(runner.awaitStop(STOP_TIMEOUT_MS), "the drawing script did not stop");

            assertGoneForObserver(observer, gameArea, drawn.layer());
            assertFoundByObserver(observer, gameArea, kept);
            assertHostListAfterStop(gameAPI, drawn, kept);
        } finally {
            runtime.stopAll();
        }
        assertGoneForObserver(observer, gameArea, keeper.made.get());
    }

    private static void assertDrawnAndReadBack(Drawn d) {
        log.info("layer {} text {} canvas {}; canvas elements after undo {}; layer visible {}",
                d.layer(), d.text(), d.canvas(), d.afterUndo(), d.layerBack().isVisible());
        assertAll(
                () -> assertFalse(d.layer().isJagex()),
                () -> assertEquals(LAYER_X, d.layerBack().x()),
                () -> assertEquals(LAYER_Y, d.layerBack().y()),
                () -> assertEquals(LAYER_W, d.layerBack().width()),
                () -> assertTrue(d.layerBack().isVisible(), "the layer should be visible in the world"),
                () -> assertEquals(OPS_DRAWN - 1, d.afterUndo(), "six ops then an undo"),
                () -> assertEquals(OPS_DRAWN - 1, d.canvasBack().numElements()),
                () -> assertEquals(CANVAS_W, d.canvasBack().width()),
                () -> assertEquals(0, d.canvasBack().opMask(), "an agent component carries no server op flags"),
                () -> assertEquals(LABEL, d.content()));
    }

    private static Map<String, Object> lookup(ComponentRef gameArea, UiHandleInfo child) {
        return Map.of("iface", gameArea.interfaceId(), "comp", gameArea.componentId(), "key", child.key());
    }

    private static void assertFoundByObserver(RpcClient observer, ComponentRef gameArea, UiHandleInfo child) {
        Map<String, Object> found = observer.callSync("ui.get_component", lookup(gameArea, child));
        assertEquals(child.type().wireName(), found.get("type_name"),
                "the observer connection should find key " + child.key() + " in the game");
    }

    private static void assertGoneForObserver(RpcClient observer, ComponentRef gameArea, UiHandleInfo child) {
        RpcRemoteException e = assertThrows(RpcRemoteException.class,
                () -> observer.callSync("ui.get_component", lookup(gameArea, child)),
                "key " + child.key() + " is still in the game's registry");
        assertTrue(e.producerMessage().contains(NOT_FOUND), e.producerMessage());
    }

    /** From the host (untagged), the drawing script's handles are gone and the keeper's remains. */
    private static void assertHostListAfterStop(GameAPIImpl gameAPI, Drawn drawn, UiHandleInfo kept) {
        List<Long> live = gameAPI.uiList().stream().map(UiHandleInfo::handle).toList();
        log.info("host ui.list after the stop: {}", live);
        assertAll(
                () -> assertFalse(live.contains(drawn.layer().handle())),
                () -> assertFalse(live.contains(drawn.text().handle())),
                () -> assertFalse(live.contains(drawn.canvas().handle())),
                () -> assertTrue(live.contains(kept.handle()), "the other script's rectangle must survive"));
    }
}
