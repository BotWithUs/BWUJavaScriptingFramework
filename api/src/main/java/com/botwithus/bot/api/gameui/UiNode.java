package com.botwithus.bot.api.gameui;

import com.botwithus.bot.api.domain.GameUiAPI;

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

    @Override
    public String toString() {
        return info.type().wireName() + "#" + Long.toUnsignedString(info.handle());
    }
}
