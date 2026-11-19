package com.botwithus.bot.api.domain;

import com.botwithus.bot.api.gameui.CanvasOp;
import com.botwithus.bot.api.gameui.GameUi;
import com.botwithus.bot.api.gameui.UiEvent;
import com.botwithus.bot.api.gameui.UiHandleInfo;
import com.botwithus.bot.api.gameui.UiOrder;
import com.botwithus.bot.api.gameui.UiPlacement;
import com.botwithus.bot.api.gameui.UiProps;
import com.botwithus.bot.api.gameui.UiReadback;
import com.botwithus.bot.api.gameui.UiRefusedException;
import com.botwithus.bot.api.gameui.UiSubscription;
import com.botwithus.bot.api.gameui.UiType;

import java.util.List;
import java.util.function.Consumer;

/**
 * Plugin UI: native game components the client draws itself, created and driven
 * through the agent's {@code ui.*} verbs. An additional, opt-in route; the script
 * UI tab and the debug-draw overlay are unchanged.
 *
 * <p>{@link #gameUi()} is the typed entry point and the one a script should use.
 * The {@code ui*} methods below are the verbs it is built on, one per wire call.</p>
 *
 * <p><b>Ownership is per script.</b> The agent owns handles per pipe connection,
 * and every script on a client shares that connection, so the host keeps its own
 * record of which script made which handle. A script can act only on its own
 * handles; when it stops, crashes or is unloaded, the host destroys exactly those,
 * one by one. The host never sends {@code ui.destroy_all}, which would take every
 * other script's UI with it. A handle made by host code (no script) is left for
 * the agent to destroy when the connection closes.</p>
 *
 * <p><b>Refusals</b> raise {@link UiRefusedException} carrying the agent's message
 * verbatim. A transport failure is not a refusal and propagates as the transport's
 * own exception.</p>
 */
public interface GameUiAPI {

    /** The typed plugin-UI facade. */
    GameUi gameUi();

    /** {@code ui.get_component}: a handle on a Jagex component, usable as a parent and for reads. */
    UiHandleInfo uiGetComponent(int iface, int comp, int key);

    /** {@code ui.create}: a new agent component under {@code parent}. */
    UiHandleInfo uiCreate(long parent, UiType type, UiPlacement placement, UiProps<?> props);

    /** {@code ui.set}: applies {@code props}, all or nothing. */
    void uiSet(long handle, UiProps<?> props);

    /** {@code ui.get}: reads the component back; the text content only when asked for. */
    UiReadback uiGet(long handle, boolean isContentRequested);

    /**
     * {@code ui.canvas}: runs up to {@link com.botwithus.bot.api.gameui.UiLimits#MAX_CANVAS_OPS}
     * ops in one call and returns the canvas's element count afterwards.
     *
     * @throws IllegalArgumentException for an empty or over-size list; a
     *                                  {@link com.botwithus.bot.api.gameui.CanvasBatch} splits instead
     */
    int uiCanvas(long handle, List<CanvasOp> ops);

    /** {@code ui.order}: moves the component to the front or back of its siblings. */
    void uiOrder(long handle, UiOrder to);

    /**
     * {@code ui.destroy}. The result is the agent's {@code freed}: whether the
     * component's last strong reference is gone. It is reported, not acted on.
     */
    boolean uiDestroy(long handle);

    /**
     * Destroys every handle the calling script made, one {@code ui.destroy} each,
     * and returns how many the agent removed. Never {@code ui.destroy_all}.
     */
    int uiDestroyOwned();

    /** {@code ui.list}, narrowed to the calling script's own handles. */
    List<UiHandleInfo> uiList();

    /**
     * Registers a listener for {@code ui.event} pushes about the calling script's
     * handles. Called on the host's push thread, attributed to the script.
     */
    UiSubscription uiOnEvent(Consumer<UiEvent> listener);
}
