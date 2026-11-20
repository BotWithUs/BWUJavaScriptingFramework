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
 * <p>Interactive components (buttons, checks, lists, input fields, sliders, combo
 * fields, panels, grids) report the user's actions as typed events:</p>
 *
 * <pre>{@code
 * Slider volume = ui.createSlider(panel, new SliderProps().range(0, 100, 5, 20).value(50)
 *         .at(8, 40).size(200, 16));
 * volume.onValueChanged(e -> level = e.value());
 * InputField name = ui.createInputField(panel, new InputFieldProps().maxLength(12));
 * name.onContentChanged(e -> {
 *     if (e.reason() == ContentChangeReason.SUBMIT) {
 *         submitted = e.content().orElse("");
 *     }
 * });
 * }</pre>
 *
 * <p>Everything a script creates is destroyed when the script stops, so a script
 * does not have to clean up after itself; its subscriptions go with it. See {@link GameUiAPI} for the ownership
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

    public Button createButton(UiParent parent, ButtonProps props) {
        return createButton(parent, props, UiPlacement.auto());
    }

    public Button createButton(UiParent parent, ButtonProps props, UiPlacement placement) {
        return new Button(create(parent, UiType.BUTTON, placement, props));
    }

    public Check createCheck(UiParent parent, CheckProps props) {
        return createCheck(parent, props, UiPlacement.auto());
    }

    public Check createCheck(UiParent parent, CheckProps props, UiPlacement placement) {
        return new Check(create(parent, UiType.CHECK, placement, props));
    }

    public ListBox createListBox(UiParent parent, ListBoxProps props) {
        return createListBox(parent, props, UiPlacement.auto());
    }

    public ListBox createListBox(UiParent parent, ListBoxProps props, UiPlacement placement) {
        return new ListBox(create(parent, UiType.LIST, placement, props));
    }

    public InputField createInputField(UiParent parent, InputFieldProps props) {
        return createInputField(parent, props, UiPlacement.auto());
    }

    public InputField createInputField(UiParent parent, InputFieldProps props, UiPlacement placement) {
        return new InputField(create(parent, UiType.INPUT_FIELD, placement, props));
    }

    public Slider createSlider(UiParent parent, SliderProps props) {
        return createSlider(parent, props, UiPlacement.auto());
    }

    public Slider createSlider(UiParent parent, SliderProps props, UiPlacement placement) {
        return new Slider(create(parent, UiType.SLIDER, placement, props));
    }

    public ComboField createComboField(UiParent parent, ComboFieldProps props) {
        return createComboField(parent, props, UiPlacement.auto());
    }

    public ComboField createComboField(UiParent parent, ComboFieldProps props, UiPlacement placement) {
        return new ComboField(create(parent, UiType.COMBO_FIELD, placement, props));
    }

    public Panel createPanel(UiParent parent, PanelProps props) {
        return createPanel(parent, props, UiPlacement.auto());
    }

    public Panel createPanel(UiParent parent, PanelProps props, UiPlacement placement) {
        return new Panel(create(parent, UiType.PANEL, placement, props));
    }

    public Grid createGrid(UiParent parent, GridProps props) {
        return createGrid(parent, props, UiPlacement.auto());
    }

    public Grid createGrid(UiParent parent, GridProps props, UiPlacement placement) {
        return new Grid(create(parent, UiType.GRID, placement, props));
    }

    /** The calling script's own handles, as the agent lists them (quarantined ones included). */
    public List<UiHandleInfo> list() {
        return api.uiList();
    }

    /** Destroys everything the calling script created; returns how many the agent removed. */
    public int destroyAll() {
        return api.uiDestroyOwned();
    }

    /**
     * Listens for {@code ui.event} pushes about the calling script's handles, and for
     * the host's own {@link UiEvent#REASON_CONNECTION_RESET}.
     *
     * <p><b>Threading.</b> The listener runs on a host thread dedicated to the calling
     * script ({@code ui-event-<script>}), never on the script's own thread, so it runs
     * <b>concurrently with {@code onLoop}</b>: guard any state the two share. The
     * script's events arrive one at a time, in order. Calls the listener makes are
     * attributed to the script, as if made from {@code onLoop}. Other scripts' events
     * run on their own threads, so a slow listener delays only its own script; past
     * 256 queued events, more are dropped with a WARN in the log. The listener is
     * removed when the subscription is closed or the script stops.</p>
     *
     * <p><b>A listener that never returns</b> keeps its script's event thread busy for
     * good: that script's later events queue behind it and are then dropped, and
     * nothing escalates it, because the script watchdog watches {@code onLoop}, not
     * listeners. Stopping the script stops delivery, but cannot free that thread.
     * Keep listeners short, and hand slow work to {@code onLoop}.</p>
     *
     * @throws UiRefusedException when the agent refuses the {@code ui.event} subscription
     */
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
