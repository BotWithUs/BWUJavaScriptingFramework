package com.botwithus.bot.api.gameui;

import com.botwithus.bot.api.domain.GameUiAPI;
import com.botwithus.bot.api.gameval.GamevalIndex;
import com.botwithus.bot.api.model.ComponentRef;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Native game components a script creates and the client draws, reached through
 * {@code api.gameUi()}.
 *
 * <pre>{@code
 * GameUi ui = api.gameUi();
 * JagexComponent gameArea = ui.component("TOPLEVEL_V2__GAME_AREA").orElseThrow();
 * Layer panel = ui.createLayer(gameArea, new LayerProps().at(40, 60).size(320, 200));
 * Canvas canvas = ui.createCanvas(panel, new CanvasProps().size(320, 200));
 * canvas.draw().rect(0, 0, 320, 200, 0x20000080).line(10, 10, 310, 190, 3, 0xFF0000FF).submit();
 * Text label = ui.createText(panel, new TextProps().text("hello").at(8, 8).size(200, 20)
 *         .rgba(0xFFFFFFFF));
 * }</pre>
 *
 * <p>Everything a script creates is destroyed when the script stops, so a script
 * does not have to clean up after itself. See {@link GameUiAPI} for the ownership
 * rules and for how refusals surface.</p>
 */
public final class GameUi {

    private final GameUiAPI api;
    private final GamevalIndex gamevals;

    public GameUi(GameUiAPI api, GamevalIndex gamevals) {
        if (api == null) {
            throw new IllegalArgumentException("api");
        }
        this.api = api;
        this.gamevals = gamevals != null ? gamevals : GamevalIndex.empty();
    }

    /** A static Jagex component, to create under or read. */
    public JagexComponent component(int iface, int comp) {
        return component(iface, comp, UiHandleInfo.STATIC_KEY);
    }

    /** A Jagex component, static ({@code key = -1}) or a dynamic child. */
    public JagexComponent component(int iface, int comp, int key) {
        return new JagexComponent(new UiNode(api, api.uiGetComponent(iface, comp, key)));
    }

    /**
     * A static Jagex component by gameval name, for example
     * {@code TOPLEVEL_V2__GAME_AREA}. Empty when the name does not resolve (the
     * index logs it once); a name that resolves to a component that is not open is
     * refused by the agent, as {@link #component(int, int)} would be.
     */
    public Optional<JagexComponent> component(String gameval) {
        Optional<ComponentRef> ref = gamevals.component(gameval);
        return ref.map(r -> component(r.interfaceId(), r.componentId()));
    }

    public Canvas createCanvas(UiParent parent, CanvasProps props) {
        return createCanvas(parent, props, UiPlacement.auto());
    }

    public Canvas createCanvas(UiParent parent, CanvasProps props, UiPlacement placement) {
        return new Canvas(create(parent, UiType.CANVAS, placement, props));
    }

    public Layer createLayer(UiParent parent, LayerProps props) {
        return createLayer(parent, props, UiPlacement.auto());
    }

    public Layer createLayer(UiParent parent, LayerProps props, UiPlacement placement) {
        return new Layer(create(parent, UiType.LAYER, placement, props));
    }

    public Line createLine(UiParent parent, LineProps props) {
        return createLine(parent, props, UiPlacement.auto());
    }

    public Line createLine(UiParent parent, LineProps props, UiPlacement placement) {
        return new Line(create(parent, UiType.LINE, placement, props));
    }

    public Rectangle createRectangle(UiParent parent, RectangleProps props) {
        return createRectangle(parent, props, UiPlacement.auto());
    }

    public Rectangle createRectangle(UiParent parent, RectangleProps props, UiPlacement placement) {
        return new Rectangle(create(parent, UiType.RECTANGLE, placement, props));
    }

    public Text createText(UiParent parent, TextProps props) {
        return createText(parent, props, UiPlacement.auto());
    }

    public Text createText(UiParent parent, TextProps props, UiPlacement placement) {
        return new Text(create(parent, UiType.TEXT, placement, props));
    }

    /** The calling script's own handles, as the agent lists them (quarantined ones included). */
    public List<UiHandleInfo> list() {
        return api.uiList();
    }

    /** Destroys everything the calling script created; returns how many the agent removed. */
    public int destroyAll() {
        return api.uiDestroyOwned();
    }

    /** Listens for {@code ui.event} pushes about the calling script's handles. */
    public UiSubscription onEvent(Consumer<UiEvent> listener) {
        return api.uiOnEvent(listener);
    }

    private UiNode create(UiParent parent, UiType type, UiPlacement placement, UiProps<?> props) {
        if (parent == null) {
            throw new IllegalArgumentException("parent");
        }
        if (props == null) {
            throw new IllegalArgumentException("props");
        }
        UiPlacement where = placement != null ? placement : UiPlacement.auto();
        return new UiNode(api, api.uiCreate(parent.handle(), type, where, props));
    }
}
