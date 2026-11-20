package com.botwithus.bot.api.gameui;

import com.botwithus.bot.api.domain.GameUiAPI;

import java.util.function.Consumer;

/** The handle and the verbs every component shares; each component type delegates to one. */
final class UiNode {

    private final GameUiAPI api;
    private final UiHandleInfo info;

    UiNode(GameUiAPI api, UiHandleInfo info) {
        this.api = api;
        this.info = info;
    }

    GameUiAPI api() {
        return api;
    }

    UiHandleInfo info() {
        return info;
    }

    long handle() {
        return info.handle();
    }

    UiReadback readback() {
        return api.uiGet(info.handle(), false);
    }

    void order(UiOrder to) {
        api.uiOrder(info.handle(), to);
    }

    boolean destroy() {
        return api.uiDestroy(info.handle());
    }

    void set(UiProps<?> props) {
        api.uiSet(info.handle(), props);
    }

    UiSubscription subscribe(UiHook hook, Consumer<? super UiHookEvent> listener) {
        if (hook == null) {
            throw new IllegalArgumentException("hook");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener");
        }
        return api.uiSubscribe(info.handle(), hook, listener::accept);
    }

    boolean unsubscribe(UiHook hook) {
        if (hook == null) {
            throw new IllegalArgumentException("hook");
        }
        return api.uiUnsubscribe(info.handle(), hook);
    }

    boolean blur() {
        return api.uiBlur(info.handle());
    }

    String content() {
        return api.uiGet(info.handle(), true).content().orElse("");
    }

    /** The readback's widget keys; the agent always sends them for this node's type. */
    UiWidgetState widget() {
        return readback().widget().orElseThrow(() -> new IllegalStateException(
                "the agent reported no " + info.type().wireName() + " keys for " + this));
    }

    @Override
    public String toString() {
        return info.type().wireName() + "#" + Long.toUnsignedString(info.handle());
    }
}
