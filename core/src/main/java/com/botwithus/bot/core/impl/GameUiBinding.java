package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.gameui.CanvasOp;
import com.botwithus.bot.api.gameui.UiEvent;
import com.botwithus.bot.api.gameui.UiHandleInfo;
import com.botwithus.bot.api.gameui.UiHook;
import com.botwithus.bot.api.gameui.UiHookEvent;
import com.botwithus.bot.api.gameui.UiLimits;
import com.botwithus.bot.api.gameui.UiOrder;
import com.botwithus.bot.api.gameui.UiPlacement;
import com.botwithus.bot.api.gameui.UiProps;
import com.botwithus.bot.api.gameui.UiReadback;
import com.botwithus.bot.api.gameui.UiRefusedException;
import com.botwithus.bot.api.gameui.UiSubscription;
import com.botwithus.bot.api.gameui.UiType;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.rpc.RpcRemoteException;
import com.botwithus.bot.core.runtime.ManagementCallerTag;
import com.botwithus.bot.core.runtime.ScriptGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The {@code ui.*} verbs over one connection's {@link RpcClient}, with the
 * per-owner lifetime the agent cannot provide on a shared pipe.
 *
 * <p><b>Owners.</b> Every script on a client shares one connection, and the
 * agent's {@code ui.destroy_all} and {@code ui.list} act on the whole connection,
 * so this class never sends {@code ui.destroy_all}. Each call is attributed to an
 * owner: the {@link ScriptGate} tag of a script thread, else the
 * {@link ManagementCallerTag} of a management-script thread, else
 * {@code hostOwner}. An owner can act only on its own handles; the host can act on
 * any. {@code ui.list} is narrowed to the caller, and a {@code ui.event} reaches
 * only its owner, on that owner's own {@link UiEventDispatcher} lane.</p>
 *
 * <p><b>Release.</b> {@link #releaseOwner} closes the owner, then destroys its
 * handles one {@code ui.destroy} at a time, newest first, <em>as the host</em>: a
 * release can run on the stopping script's own thread or after it was revoked, and
 * the gate would park and refuse each of its calls. A create whose reply arrives
 * after the owner closed is destroyed at once (see {@link #create}).</p>
 *
 * <p><b>Reset.</b> When the RPC client swaps its pipe, the agent has destroyed
 * everything the old pipe made. {@link #onConnectionReset} forgets it all, tells
 * each owner's listeners with reason {@link UiEvent#REASON_CONNECTION_RESET}, and
 * moves to a new handle epoch so old handle objects can never act on a component
 * made afterwards (see {@link UiHandleSpace}).</p>
 *
 * <p><b>Hooks.</b> {@code ui.subscribe} is sent for the first listener of a
 * (handle, hook) and {@code ui.unsubscribe} when the last one closes. Listeners and
 * each component's type live in {@link UiHookRegistry}; a hook push is decoded by
 * that type and delivered on the owner's lane like any other event. A handle that
 * goes (destroy, {@code destroyed}, release, reset) takes its listeners with it, so
 * no {@code ui.unsubscribe} is ever sent for a handle the agent already dropped.</p>
 */
final class GameUiBinding {

    private static final Logger log = LoggerFactory.getLogger(GameUiBinding.class);

    /** What the host says when an owner reaches for another owner's handle. */
    static final String NOT_YOURS = "ui: handle belongs to another script on this connection";
    /** What the host says about a handle from before a connection reset. */
    static final String STALE = "ui: handle is from before the connection was reset; look the component up "
            + "or create it again";
    /** What a create says when its owner was released while the call was in flight. */
    static final String RELEASED = "ui: the script was stopped while this component was being created; "
            + "it has been destroyed";
    /** What a subscribe says when its component went while the call was in flight. */
    static final String GONE = "ui: unknown handle (the component was destroyed while subscribing)";
    private static final String SUBSCRIBE = "_debug.subscribe";
    /** The agent's refusal for a handle that no longer exists (or is not this connection's). */
    private static final String UNKNOWN_HANDLE = "unknown handle";

    private final RpcClient rpc;
    private final Supplier<ScriptGate> gate;
    private final Supplier<ManagementCallerTag> managementTag;
    private final String hostOwner;
    private final UiOwnership ownership = new UiOwnership();
    private final UiHookRegistry hooks = new UiHookRegistry();
    /** Serialises subscribe and unsubscribe, so the first-listener and last-listener calls cannot cross. */
    private final Object hookLock = new Object();
    private final UiHandleSpace handles = new UiHandleSpace();
    private final UiEventDispatcher dispatcher;
    private final Object subscribeLock = new Object();
    private volatile boolean isSubscribed;
    private final AtomicBoolean isResetHooked = new AtomicBoolean();

    GameUiBinding(RpcClient rpc, Supplier<ScriptGate> gate, Supplier<ManagementCallerTag> managementTag,
                  String hostOwner, int eventCapacity) {
        this.rpc = rpc;
        this.gate = gate;
        this.managementTag = managementTag;
        this.hostOwner = hostOwner;
        this.dispatcher = new UiEventDispatcher(eventCapacity);
    }

    // ------------------------------------------------------------------ verbs

    UiHandleInfo getComponent(int iface, int comp, int key) {
        hookConnectionReset();
        String owner = owner();
        ensureSubscribed(false);
        UiHandleInfo info = UiCodec.decodeHandle(
                call(UiCodec.GET_COMPONENT, UiCodec.getComponentParams(iface, comp, key)));
        ownership.recordLookup(owner, info.handle());
        return toHost(info);
    }

    /**
     * Creates a component. If the owner was released (stopped, crashed, revoked)
     * while the call was in flight, the new component is destroyed at once, as the
     * host, and the call fails: otherwise it would outlive its script, untracked.
     */
    UiHandleInfo create(long parent, UiType type, UiPlacement placement, UiProps<?> props) {
        if (type == null || !type.isCreatable()) {
            throw new IllegalArgumentException("not a creatable type: " + type);
        }
        hookConnectionReset();
        String owner = owner();
        long agentParent = usableHandle(UiCodec.CREATE, owner, parent);
        ensureSubscribed(false);
        UiPlacement where = placement != null ? placement : UiPlacement.auto();
        UiHandleInfo info = UiCodec.decodeHandle(
                call(UiCodec.CREATE, UiCodec.createParams(agentParent, type, where, props)));
        // Recorded before the owner, so a release that lands in between forgets both.
        hooks.recordType(info.handle(), type);
        if (isRevoked(owner) || !ownership.recordCreated(owner, info.handle())) {
            asHost(() -> destroyAttempt(owner, info.handle()));
            throw new UiRefusedException(UiCodec.CREATE, RELEASED);
        }
        return toHost(info);
    }

    void set(long handle, UiProps<?> props) {
        long agent = usableHandle(UiCodec.SET, owner(), handle);
        call(UiCodec.SET, UiCodec.setParams(agent, props));
    }

    UiReadback get(long handle, boolean isContentRequested) {
        long agent = usableHandle(UiCodec.GET, owner(), handle);
        UiReadback r = UiCodec.decodeReadback(call(UiCodec.GET, UiCodec.getParams(agent, isContentRequested)));
        return r.withInfo(toHost(r.info()));
    }

    int canvas(long handle, List<CanvasOp> ops) {
        if (ops == null || ops.isEmpty() || ops.size() > UiLimits.MAX_CANVAS_OPS) {
            throw new IllegalArgumentException("ui.canvas takes 1.." + UiLimits.MAX_CANVAS_OPS
                    + " ops per call, got " + (ops == null ? 0 : ops.size()));
        }
        long agent = usableHandle(UiCodec.CANVAS, owner(), handle);
        Map<String, Object> reply = call(UiCodec.CANVAS, UiCodec.canvasParams(agent, ops));
        return MapHelper.getIntOr(reply, "num_elements", UiReadback.NOT_APPLICABLE);
    }

    void order(long handle, UiOrder to) {
        long agent = usableHandle(UiCodec.ORDER, owner(), handle);
        call(UiCodec.ORDER, UiCodec.orderParams(agent, to));
    }

    /**
     * Destroys one handle. It stops being tracked only when the agent removed it or
     * says it is {@code unknown}. Any other refusal leaves it tracked: refusing to
     * destroy a shared Jagex handle, for one, must not untrack it for the other owners
     * that looked it up.
     */
    boolean destroy(long handle) {
        long agent = usableHandle(UiCodec.DESTROY, owner(), handle);
        boolean isFreed;
        try {
            isFreed = MapHelper.getBool(call(UiCodec.DESTROY, UiCodec.handleParams(agent)), "freed");
        } catch (UiRefusedException e) {
            if (isUnknownHandle(e)) {
                forget(agent);
            }
            throw e;
        }
        forget(agent);
        return isFreed;
    }

    /** Destroys the caller's own handles, newest first; returns how many the agent removed. */
    int destroyOwned() {
        String owner = owner();
        return destroyEach(owner, ownership.agentHandlesOf(owner));
    }

    /** {@code ui.list}, narrowed to the caller's own handles; the host sees all of them. */
    List<UiHandleInfo> list() {
        String owner = owner();
        List<UiHandleInfo> all = UiCodec.decodeList(call(UiCodec.LIST, Map.of()));
        return all.stream()
                .filter(info -> hostOwner.equals(owner) || ownership.isOwnedBy(owner, info.handle(), info.isJagex()))
                .map(this::toHost)
                .toList();
    }

    /** Registers a listener; the subscription it needs is required here, so a refusal is raised. */
    UiSubscription onEvent(Consumer<UiEvent> listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener");
        }
        hookConnectionReset();
        String owner = owner();
        ensureSubscribed(true);
        return ownership.addListener(owner, listener);
    }

    /**
     * Adds a hook listener to one of the caller's components. The first listener for
     * a (handle, hook) sends {@code ui.subscribe}; a refusal is raised and nothing is
     * registered. A component destroyed while the call was in flight is refused too,
     * so no listener outlives its component.
     */
    UiSubscription subscribe(long handle, UiHook hook, Consumer<UiHookEvent> listener) {
        if (hook == null || listener == null) {
            throw new IllegalArgumentException(hook == null ? "hook" : "listener");
        }
        hookConnectionReset();
        long agent = usableHandle(UiCodec.SUBSCRIBE, owner(), handle);
        ensureSubscribed(true);
        synchronized (hookLock) {
            if (!hooks.hasListeners(agent, hook)) {
                call(UiCodec.SUBSCRIBE, UiCodec.hookParams(agent, hook));
            }
            if (!hooks.add(agent, hook, listener)) {
                throw new UiRefusedException(UiCodec.SUBSCRIBE, GONE);
            }
        }
        long epochHandle = handle;
        AtomicBoolean isClosed = new AtomicBoolean();
        return () -> {
            if (isClosed.compareAndSet(false, true)) {
                closeListener(epochHandle, agent, hook, listener);
            }
        };
    }

    /**
     * Drops the caller's listeners for {@code hook} and sends {@code ui.unsubscribe}.
     * A handle the agent no longer knows, or one from before a reset, answers
     * {@code false}: its subscriptions are already gone.
     */
    boolean unsubscribe(long handle, UiHook hook) {
        if (hook == null) {
            throw new IllegalArgumentException("hook");
        }
        if (!handles.isCurrent(handle)) {
            return false;
        }
        long agent = usableHandle(UiCodec.UNSUBSCRIBE, owner(), handle);
        synchronized (hookLock) {
            hooks.removeAll(agent, hook);
            return sendUnsubscribe(agent, hook);
        }
    }

    /** {@code ui.blur}: whether the field had the client's input focus. */
    boolean blur(long handle) {
        long agent = usableHandle(UiCodec.BLUR, owner(), handle);
        return MapHelper.getBool(call(UiCodec.BLUR, UiCodec.handleParams(agent)), "blurred");
    }

    // ------------------------------------------------------------------ lifetime

    /**
     * Makes {@code run} the owner's current run and lets it create again: called at
     * the start of each of its runs.
     */
    void openOwner(String owner, long run) {
        ownership.reopen(owner, run);
        dispatcher.reopen(owner);
    }

    /**
     * Releases one run of {@code owner}: {@link #releaseOwner(String)}, unless a newer
     * run has opened since, in which case it does nothing. That is a quarantined run
     * finishing its cleanup after its replacement started.
     *
     * @return how many handles the agent removed
     */
    int releaseOwner(String owner, long run) {
        return ownership.closeRun(owner, run).map(owned -> releaseClosed(owner, owned)).orElseGet(() -> {
            log.debug("Ignored a stale plugin-UI release of {} run {}: a newer run has opened", owner, run);
            return 0;
        });
    }

    /**
     * Releases everything {@code owner} holds: closes it, drops its listeners and its
     * event lane, and destroys its handles one by one, as the host. Each handle is
     * untracked only after its own destroy was attempted, and one failure does not
     * stop the rest. Never sends {@code ui.destroy_all}.
     *
     * @return how many handles the agent removed
     */
    int releaseOwner(String owner) {
        return releaseClosed(owner, ownership.close(owner));
    }

    private int releaseClosed(String owner, List<Long> owned) {
        dispatcher.close(owner);
        int removed = asHost(() -> destroyEach(owner, owned));
        if (!owned.isEmpty()) {
            log.debug("Released {} of {} plugin-UI handle(s) for {}", removed, owned.size(), owner);
        }
        return removed;
    }

    /** The pipe was replaced: everything the old one made is gone, and every old handle is now stale. */
    void onConnectionReset() {
        long ended = handles.advance();
        hooks.clear();
        Map<String, List<Long>> lost = ownership.reset();
        lost.forEach((owner, agentHandles) -> agentHandles.forEach(h -> dispatch(owner,
                new UiEvent(UiHandleSpace.compose(ended, h), UiEvent.HOOK_DESTROYED,
                        Optional.of(UiEvent.REASON_CONNECTION_RESET)))));
        if (!lost.isEmpty()) {
            log.info("Connection reset: forgot the plugin-UI handles of {} owner(s)", lost.size());
        }
    }

    // ------------------------------------------------------------------ events

    /**
     * The {@code ui.event} handler; runs on the RPC client's push thread and only
     * routes. A hook this host knows goes to that hook's listeners, decoded; anything
     * else ({@code destroyed}, or a hook a newer agent added) goes to the
     * {@code onEvent} listeners as a {@link UiEvent}, as it always has.
     */
    void onPush(Map<String, Object> data) {
        Optional<UiCodec.HookPush> hookPush = UiCodec.decodeHookPush(data);
        if (hookPush.isPresent()) {
            routeHook(hookPush.get());
            return;
        }
        UiCodec.decodeEvent(data).ifPresent(event -> {
            UiEvent hostEvent = new UiEvent(handles.toHost(event.handle()), event.hook(), event.reason());
            if (event.isDestroyed()) {
                hooks.forget(event.handle());
            }
            for (String owner : ownership.route(event.handle(), event.isDestroyed())) {
                dispatch(owner, hostEvent);
            }
        });
    }

    /**
     * Decodes a hook push by the component's recorded type and queues it on its
     * owner's lane. One for a handle this connection's owners did not make, or whose
     * args do not have the hook's shape, is dropped with a DEBUG.
     */
    private void routeHook(UiCodec.HookPush push) {
        long agent = push.handle();
        String owner = ownership.agentOwnerOf(agent);
        Optional<UiHookEvent> event = hooks.typeOf(agent).flatMap(type ->
                UiCodec.decodeHookEvent(handles.toHost(agent), type, push.hook(), push.args()));
        if (owner == null || event.isEmpty()) {
            log.debug("ui.event {} for handle {} dropped: untracked handle or malformed args {}",
                    push.hook().wireName(), agent, push.args());
            return;
        }
        UiHookEvent hookEvent = event.get();
        dispatcher.submit(owner, () -> deliverHook(owner, agent, hookEvent));
    }

    private void deliverHook(String owner, long agent, UiHookEvent event) {
        if (isRevoked(owner)) {
            return;
        }
        for (Consumer<UiHookEvent> listener : hooks.listeners(agent, event.hook())) {
            runListener(owner, () -> listener.accept(event));
        }
    }

    private void dispatch(String owner, UiEvent event) {
        dispatcher.submit(owner, () -> deliver(owner, event));
    }

    private void deliver(String owner, UiEvent event) {
        if (isRevoked(owner)) {
            return;
        }
        for (Consumer<UiEvent> listener : ownership.listenersOf(owner)) {
            runListener(owner, () -> listener.accept(event));
        }
    }

    /** Runs one listener as its owner; a listener that throws is logged and does not stop the rest. */
    private void runListener(String owner, Runnable listener) {
        runAs(owner, () -> {
            try {
                listener.run();
            } catch (RuntimeException e) {
                log.warn("ui.event listener of {} threw: {}", owner, e.toString());
            }
        });
    }

    /**
     * One subscription closed: drops its listener and, when it was the hook's last,
     * unsubscribes. Sends nothing for a handle that is already gone (destroyed,
     * released, announced, or from before a reset), and never throws: a close runs
     * in cleanup paths.
     */
    private void closeListener(long hostHandle, long agent, UiHook hook, Consumer<UiHookEvent> listener) {
        synchronized (hookLock) {
            if (!handles.isCurrent(hostHandle) || !hooks.remove(agent, hook, listener)) {
                return;
            }
            try {
                sendUnsubscribe(agent, hook);
            } catch (RuntimeException e) {
                log.debug("ui.unsubscribe of handle {} {} failed: {}", agent, hook.wireName(), e.getMessage());
            }
        }
    }

    /** {@code ui.unsubscribe}; an unknown handle answers {@code false}, as a gone handle has nothing to drop. */
    private boolean sendUnsubscribe(long agent, UiHook hook) {
        try {
            return MapHelper.getBool(call(UiCodec.UNSUBSCRIBE, UiCodec.hookParams(agent, hook)), "unsubscribed");
        } catch (UiRefusedException e) {
            if (isUnknownHandle(e)) {
                forget(agent);
                return false;
            }
            throw e;
        }
    }

    private static boolean isUnknownHandle(UiRefusedException e) {
        return e.getMessage() != null && e.getMessage().contains(UNKNOWN_HANDLE);
    }

    /** Stops tracking an agent handle: its owner, and its hook listeners with it. */
    private void forget(long agent) {
        ownership.forget(agent);
        hooks.forget(agent);
    }

    // ------------------------------------------------------------------ internals

    /**
     * Registers for connection resets the first time this connection's plugin UI is
     * used. Lazy on purpose: until a handle or a listener exists there is nothing to
     * forget, and a connection that never uses plugin UI makes no extra call on its
     * RPC client.
     */
    private void hookConnectionReset() {
        if (isResetHooked.compareAndSet(false, true)) {
            rpc.addConnectionResetListener(this::onConnectionReset);
        }
    }

    /** The caller: a script, else a management script, else the host. */
    private String owner() {
        ScriptGate scriptGate = gate.get();
        String script = scriptGate != null ? scriptGate.current() : null;
        if (script != null) {
            return script;
        }
        ManagementCallerTag tag = managementTag.get();
        String management = tag != null ? tag.current() : null;
        return management != null ? management : hostOwner;
    }

    /** Whether the watchdog revoked {@code owner}: a script on this connection's gate, or a management run. */
    private boolean isRevoked(String owner) {
        ScriptGate scriptGate = gate.get();
        ManagementCallerTag tag = managementTag.get();
        return (scriptGate != null && scriptGate.isRevoked(owner)) || (tag != null && tag.isRevoked(owner));
    }

    /**
     * The agent's number for a script-visible handle, refusing one from an earlier
     * connection and one another owner made. The host may use any current handle.
     */
    private long usableHandle(String method, String owner, long hostHandle) {
        if (!handles.isCurrent(hostHandle)) {
            throw new UiRefusedException(method, STALE);
        }
        long agent = UiHandleSpace.agentOf(hostHandle);
        String madeBy = ownership.agentOwnerOf(agent);
        if (madeBy != null && !madeBy.equals(owner) && !hostOwner.equals(owner)) {
            throw new UiRefusedException(method, NOT_YOURS);
        }
        return agent;
    }

    private UiHandleInfo toHost(UiHandleInfo agent) {
        return new UiHandleInfo(handles.toHost(agent.handle()), agent.type(), agent.iface(), agent.comp(),
                agent.key(), handles.toHost(agent.parent()), agent.isJagex(), agent.isQuarantined());
    }

    private int destroyEach(String owner, List<Long> agentHandles) {
        int removed = 0;
        for (long handle : agentHandles) {
            removed += destroyAttempt(owner, handle) ? 1 : 0;
        }
        return removed;
    }

    /** One destroy, never throwing; the handle is untracked after the attempt, whatever it did. */
    private boolean destroyAttempt(String owner, long agentHandle) {
        try {
            call(UiCodec.DESTROY, UiCodec.handleParams(agentHandle));
            return true;
        } catch (RuntimeException e) {
            log.debug("ui.destroy of {}'s handle {} failed: {}", owner, agentHandle, e.getMessage());
            return false;
        } finally {
            forget(agentHandle);
        }
    }

    /**
     * Runs {@code action} with the calling thread untagged, so the gate treats its
     * calls as the host's: never parked, never refused for a revoked script.
     */
    private <T> T asHost(Supplier<T> action) {
        ScriptGate scriptGate = gate.get();
        ManagementCallerTag tag = managementTag.get();
        String script = scriptGate != null ? scriptGate.current() : null;
        String management = tag != null ? tag.current() : null;
        clearTags(scriptGate, tag);
        try {
            return action.get();
        } finally {
            if (script != null) {
                scriptGate.enter(script);
            }
            if (management != null) {
                tag.enter(management);
            }
        }
    }

    private static void clearTags(ScriptGate scriptGate, ManagementCallerTag tag) {
        if (scriptGate != null) {
            scriptGate.exit();
        }
        if (tag != null) {
            tag.exit();
        }
    }

    /**
     * Runs a listener as exactly its owner: <b>both</b> tags are set for every kind of
     * owner (the gate tag to the script or to none, the management tag to the
     * management script or to none), whatever the thread carried before, and the
     * thread's previous tags are put back afterwards. Setting only the owner's own
     * tag would leave a stale one in force: a management listener on a thread still
     * gate-tagged as some script would act as that script.
     */
    private void runAs(String owner, Runnable action) {
        ScriptGate scriptGate = gate.get();
        ManagementCallerTag tag = managementTag.get();
        boolean isManagement = ManagementCallerTag.isOwnerName(owner);
        boolean isScript = !isManagement && !hostOwner.equals(owner);
        String previousScript = scriptGate != null ? scriptGate.current() : null;
        String previousManagement = tag != null ? tag.current() : null;
        setTag(scriptGate, isScript ? owner : null);
        setTag(tag, isManagement ? owner : null);
        try {
            action.run();
        } finally {
            setTag(scriptGate, previousScript);
            setTag(tag, previousManagement);
        }
    }

    private static void setTag(ScriptGate scriptGate, String owner) {
        if (scriptGate == null) {
            return;
        }
        if (owner != null) {
            scriptGate.enter(owner);
        } else {
            scriptGate.exit();
        }
    }

    private static void setTag(ManagementCallerTag tag, String owner) {
        if (tag == null) {
            return;
        }
        if (owner != null) {
            tag.enter(owner);
        } else {
            tag.exit();
        }
    }

    /**
     * Subscribes to {@code ui.event} once. A refusal is raised as
     * {@link UiRefusedException} when {@code isRequired} (a listener is being
     * added); otherwise it is logged and retried on the next call, so a create does
     * not fail for want of events.
     */
    private void ensureSubscribed(boolean isRequired) {
        if (isSubscribed) {
            return;
        }
        synchronized (subscribeLock) {
            if (isSubscribed) {
                return;
            }
            try {
                // Lost when a reconnect cannot re-subscribe: clear the flag so the
                // next call tries again rather than trusting a dead subscription.
                rpc.subscribeTopic(UiCodec.EVENT_TOPIC, this::onPush, () -> isSubscribed = false);
                isSubscribed = true;
            } catch (RpcRemoteException e) {
                subscribeFailed(new UiRefusedException(SUBSCRIBE, e.producerMessage(), e), isRequired);
            } catch (RuntimeException e) {
                subscribeFailed(e, isRequired);
            }
        }
    }

    private static void subscribeFailed(RuntimeException failure, boolean isRequired) {
        if (isRequired) {
            throw failure;
        }
        log.warn("Subscribing to {} failed; retrying on the next call: {}",
                UiCodec.EVENT_TOPIC, failure.getMessage());
    }

    private Map<String, Object> call(String method, Map<String, Object> params) {
        try {
            return rpc.callSync(method, params);
        } catch (RpcRemoteException e) {
            throw new UiRefusedException(method, e.producerMessage(), e);
        }
    }
}
