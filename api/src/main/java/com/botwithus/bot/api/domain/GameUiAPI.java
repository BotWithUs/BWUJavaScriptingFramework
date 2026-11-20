package com.botwithus.bot.api.domain;

import com.botwithus.bot.api.gameui.CanvasOp;
import com.botwithus.bot.api.gameui.GameUi;
import com.botwithus.bot.api.gameui.UiEvent;
import com.botwithus.bot.api.gameui.UiHandleInfo;
import com.botwithus.bot.api.gameui.UiHook;
import com.botwithus.bot.api.gameui.UiHookEvent;
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
 * <p>{@link #gameUi()} is the typed entry point and <b>the supported surface</b>: its
 * types make the agent's hard refusals unspellable (no setter on a Jagex component,
 * no interaction configs, no category 0). The {@code ui*} methods below are the verbs
 * it is built on, one per wire call, and take bare handle numbers, so they check none
 * of that. In particular a handle from {@link #uiGetComponent} is a <b>Jagex
 * component</b>: the agent accepts it only as a {@code parent} for {@link #uiCreate}
 * and as the target of {@link #uiGet}, and refuses it for {@link #uiSet},
 * {@link #uiCanvas}, {@link #uiOrder} and {@link #uiDestroy} ({@code ui: handle is a
 * Jagex component: read-only, ...}).</p>
 *
 * <p><b>Handles are opaque.</b> After the host's connection to the agent is replaced,
 * every earlier handle is refused for good, even if a restarted agent issues the same
 * number again; see {@link UiEvent#REASON_CONNECTION_RESET}.</p>
 *
 * <p><b>Ownership is per script.</b> The agent owns handles per pipe connection,
 * and every script on a client shares that connection, so the host keeps its own
 * record of which script made which handle. A script can act only on its own
 * handles; when it stops, crashes, is unloaded or is revoked, the host destroys
 * exactly those, one by one, and a create still in flight at that moment is destroyed
 * as soon as it returns. The host never sends {@code ui.destroy_all}, which would take
 * every other script's UI with it. A management script is an owner of its own too,
 * on every connection it draws on, and is released the same way when it stops. Only
 * host code (no script at all) may act on any handle; what it makes is left for the
 * agent to destroy when the connection closes.</p>
 *
 * <p><b>Refusals</b> raise {@link UiRefusedException} carrying the agent's message
 * verbatim. A transport failure is not a refusal and propagates as the transport's
 * own exception.</p>
 */
public interface GameUiAPI {

    /** The typed plugin-UI facade. */
    GameUi gameUi();

    /**
     * {@code ui.get_component}: a handle on a Jagex component. The agent accepts it
     * only as a {@link #uiCreate} parent and as a {@link #uiGet} target.
     */
    UiHandleInfo uiGetComponent(int iface, int comp, int key);

    /** {@code ui.create}: a new agent component under {@code parent}. */
    UiHandleInfo uiCreate(long parent, UiType type, UiPlacement placement, UiProps<?> props);

    /** {@code ui.set}: applies {@code props}, all or nothing. Refused by the agent for a Jagex handle. */
    void uiSet(long handle, UiProps<?> props);

    /** {@code ui.get}: reads the component back; the text content only when asked for. */
    UiReadback uiGet(long handle, boolean isContentRequested);

    /**
     * {@code ui.canvas}: runs up to {@link com.botwithus.bot.api.gameui.UiLimits#MAX_CANVAS_OPS}
     * ops in one call and returns the canvas's element count afterwards.
     *
     * Refused by the agent for any handle but one of the caller's own canvases.
     *
     * @throws IllegalArgumentException for an empty or over-size list; a
     *                                  {@link com.botwithus.bot.api.gameui.CanvasBatch} splits instead
     */
    int uiCanvas(long handle, List<CanvasOp> ops);

    /** {@code ui.order}: moves the component among its siblings. Refused by the agent for a Jagex handle. */
    void uiOrder(long handle, UiOrder to);

    /**
     * {@code ui.destroy}. The result is the agent's {@code freed}: whether the
     * component's last strong reference is gone. It is reported, not acted on.
     * Refused by the agent for a Jagex handle.
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
     * handles. See {@link GameUi#onEvent} for the thread it runs on.
     */
    UiSubscription uiOnEvent(Consumer<UiEvent> listener);

    /**
     * Listens for {@code hook} on one of the calling script's own components: the
     * first listener for a (handle, hook) sends {@code ui.subscribe}, and closing the
     * last one sends {@code ui.unsubscribe}. Events are decoded by the component's
     * type and delivered as {@link GameUi#onEvent} describes. Refused by the agent for
     * a Jagex handle and for a hook the type does not have.
     */
    UiSubscription uiSubscribe(long handle, UiHook hook, Consumer<UiHookEvent> listener);

    /**
     * Drops every listener the calling script has for {@code hook} on {@code handle}
     * and sends {@code ui.unsubscribe}. Returns the agent's {@code unsubscribed}:
     * whether the hook was subscribed. A handle the agent no longer knows answers
     * {@code false} rather than a refusal; its subscriptions went with it.
     */
    boolean uiUnsubscribe(long handle, UiHook hook);

    /**
     * {@code ui.blur}: clears the client's input focus from one of the calling
     * script's input fields. Returns whether it had focus. Refused by the agent for
     * any other type.
     */
    boolean uiBlur(long handle);
}
