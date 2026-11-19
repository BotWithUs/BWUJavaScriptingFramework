package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.gameui.CanvasOp;
import com.botwithus.bot.api.gameui.UiEvent;
import com.botwithus.bot.api.gameui.UiHandleInfo;
import com.botwithus.bot.api.gameui.UiLimits;
import com.botwithus.bot.api.gameui.UiOrder;
import com.botwithus.bot.api.gameui.UiPlacement;
import com.botwithus.bot.api.gameui.UiProps;
import com.botwithus.bot.api.gameui.UiReadback;
import com.botwithus.bot.api.gameui.UiRefusedException;
import com.botwithus.bot.api.gameui.UiSubscription;
import com.botwithus.bot.api.gameui.UiType;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.rpc.RpcException;
import com.botwithus.bot.core.rpc.RpcRemoteException;
import com.botwithus.bot.core.runtime.ScriptGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The {@code ui.*} verbs over one connection's {@link RpcClient}, with the
 * per-script ownership the agent cannot provide on a shared pipe.
 *
 * <p>The connection is shared by every script on the client, and the agent's
 * {@code ui.destroy_all} and {@code ui.list} act on the whole connection. So this
 * class never sends {@code ui.destroy_all}: a script's handles are destroyed one
 * {@code ui.destroy} at a time ({@link #destroyOwned()} for the script itself,
 * {@link #releaseOwner} when the runtime stops it). {@code ui.list} is narrowed to
 * the caller's handles, and a {@code ui.event} goes only to the script that owns
 * the handle.</p>
 *
 * <p>The caller is identified the way walks are: by the {@link ScriptGate} tag on
 * the calling thread. A thread with no tag (host code) is {@code hostOwner}; it may
 * act on any handle, and its own handles are left for the agent to destroy when
 * the connection closes.</p>
 */
final class GameUiBinding {

    private static final Logger log = LoggerFactory.getLogger(GameUiBinding.class);

    /** What the host says when a script reaches for another script's handle. */
    static final String NOT_YOURS = "ui: handle belongs to another script on this connection";

    private final RpcClient rpc;
    private final Supplier<String> callerOwner;
    private final Supplier<ScriptGate> gate;
    private final String hostOwner;
    private final UiOwnership ownership = new UiOwnership();
    private final Object subscribeLock = new Object();
    private volatile boolean isSubscribed;

    GameUiBinding(RpcClient rpc, Supplier<String> callerOwner, Supplier<ScriptGate> gate,
                  String hostOwner) {
        this.rpc = rpc;
        this.callerOwner = callerOwner;
        this.gate = gate;
        this.hostOwner = hostOwner;
    }

    UiHandleInfo getComponent(int iface, int comp, int key) {
        ensureSubscribed();
        UiHandleInfo info = UiCodec.decodeHandle(
                call(UiCodec.GET_COMPONENT, UiCodec.getComponentParams(iface, comp, key)));
        ownership.recordLookup(callerOwner.get(), info.handle());
        return info;
    }

    UiHandleInfo create(long parent, UiType type, UiPlacement placement, UiProps<?> props) {
        if (type == null || !type.isCreatable()) {
            throw new IllegalArgumentException("not a creatable type: " + type);
        }
        String owner = callerOwner.get();
        requireUsable(UiCodec.CREATE, owner, parent);
        ensureSubscribed();
        UiPlacement where = placement != null ? placement : UiPlacement.auto();
        UiHandleInfo info = UiCodec.decodeHandle(
                call(UiCodec.CREATE, UiCodec.createParams(parent, type, where, props)));
        ownership.recordCreated(owner, info.handle());
        return info;
    }

    void set(long handle, UiProps<?> props) {
        requireUsable(UiCodec.SET, callerOwner.get(), handle);
        call(UiCodec.SET, UiCodec.setParams(handle, props));
    }

    UiReadback get(long handle, boolean isContentRequested) {
        requireUsable(UiCodec.GET, callerOwner.get(), handle);
        return UiCodec.decodeReadback(call(UiCodec.GET, UiCodec.getParams(handle, isContentRequested)));
    }

    int canvas(long handle, List<CanvasOp> ops) {
        if (ops == null || ops.isEmpty() || ops.size() > UiLimits.MAX_CANVAS_OPS) {
            throw new IllegalArgumentException("ui.canvas takes 1.." + UiLimits.MAX_CANVAS_OPS
                    + " ops per call, got " + (ops == null ? 0 : ops.size()));
        }
        requireUsable(UiCodec.CANVAS, callerOwner.get(), handle);
        Map<String, Object> reply = call(UiCodec.CANVAS, UiCodec.canvasParams(handle, ops));
        return MapHelper.getIntOr(reply, "num_elements", UiReadback.NOT_APPLICABLE);
    }

    void order(long handle, UiOrder to) {
        requireUsable(UiCodec.ORDER, callerOwner.get(), handle);
        call(UiCodec.ORDER, UiCodec.orderParams(handle, to));
    }

    /**
     * Destroys one handle. Whatever the agent answers, the handle is no longer
     * tracked: success removed it, {@code unknown handle} means it was gone, and a
     * quarantined removal is the agent's to retry.
     */
    boolean destroy(long handle) {
        requireUsable(UiCodec.DESTROY, callerOwner.get(), handle);
        try {
            return MapHelper.getBool(call(UiCodec.DESTROY, UiCodec.handleParams(handle)), "freed");
        } finally {
            ownership.forget(handle);
        }
    }

    /** Destroys the caller's own agent handles, newest first; returns how many the agent removed. */
    int destroyOwned() {
        String owner = callerOwner.get();
        int removed = 0;
        for (long handle : ownership.agentHandlesOf(owner)) {
            removed += destroyTracked(owner, handle) ? 1 : 0;
        }
        return removed;
    }

    /**
     * Releases everything {@code owner} holds: drops its listeners and destroys its
     * agent handles one by one. Called by the runtime when the script stops,
     * crashes, is unloaded or is revoked. Never sends {@code ui.destroy_all}.
     *
     * @return how many handles the agent removed
     */
    int releaseOwner(String owner) {
        List<Long> handles = ownership.release(owner);
        int removed = 0;
        for (long handle : handles) {
            removed += destroyTracked(owner, handle) ? 1 : 0;
        }
        if (!handles.isEmpty()) {
            log.debug("Released {} of {} plugin-UI handle(s) for {}", removed, handles.size(), owner);
        }
        return removed;
    }

    /** {@code ui.list}, narrowed to the caller's own handles; the host sees all of them. */
    List<UiHandleInfo> list() {
        String owner = callerOwner.get();
        List<UiHandleInfo> all = UiCodec.decodeList(call(UiCodec.LIST, Map.of()));
        if (hostOwner.equals(owner)) {
            return all;
        }
        return all.stream().filter(info -> ownership.isOwnedBy(owner, info)).toList();
    }

    UiSubscription onEvent(Consumer<UiEvent> listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener");
        }
        ensureSubscribed();
        return ownership.addListener(callerOwner.get(), listener);
    }

    /** The {@code ui.event} handler; runs on the RPC client's push thread. */
    void onPush(Map<String, Object> data) {
        UiCodec.decodeEvent(data).ifPresent(event -> {
            for (UiOwnership.Delivery delivery : ownership.route(event)) {
                deliver(delivery, event);
            }
        });
    }

    private void deliver(UiOwnership.Delivery delivery, UiEvent event) {
        ScriptGate scriptGate = gate.get();
        String owner = delivery.owner();
        boolean isScript = scriptGate != null && !hostOwner.equals(owner);
        if (isScript && scriptGate.isRevoked(owner)) {
            return;
        }
        if (isScript) {
            // Calls the listener makes are the script's, not the host's.
            scriptGate.enter(owner);
        }
        try {
            delivery.listener().accept(event);
        } catch (RuntimeException e) {
            log.warn("ui.event listener of {} threw: {}", owner, e.toString());
        } finally {
            if (isScript) {
                scriptGate.exit();
            }
        }
    }

    private boolean destroyTracked(String owner, long handle) {
        try {
            call(UiCodec.DESTROY, UiCodec.handleParams(handle));
            return true;
        } catch (UiRefusedException e) {
            log.debug("ui.destroy of {}'s handle {} refused: {}", owner, handle, e.getMessage());
            return false;
        } catch (RpcException e) {
            // A dead pipe: the agent destroys the connection's components when it
            // sees the close, so nothing is leaked by stopping here.
            log.warn("ui.destroy of {}'s handle {} failed: {}", owner, handle, e.getMessage());
            return false;
        } finally {
            ownership.forget(handle);
        }
    }

    /** Refuses a handle that another script on this connection made; the host may use any. */
    private void requireUsable(String method, String owner, long handle) {
        String madeBy = ownership.agentOwnerOf(handle);
        if (madeBy != null && !madeBy.equals(owner) && !hostOwner.equals(owner)) {
            throw new UiRefusedException(method, NOT_YOURS);
        }
    }

    private void ensureSubscribed() {
        if (isSubscribed) {
            return;
        }
        synchronized (subscribeLock) {
            if (!isSubscribed) {
                rpc.subscribeTopic(UiCodec.EVENT_TOPIC, this::onPush);
                isSubscribed = true;
            }
        }
    }

    private Map<String, Object> call(String method, Map<String, Object> params) {
        try {
            return rpc.callSync(method, params);
        } catch (RpcRemoteException e) {
            throw new UiRefusedException(method, e.producerMessage(), e);
        }
    }
}
