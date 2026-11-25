package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.domain.KeyboardAPI;
import com.botwithus.bot.api.input.FocusMode;
import com.botwithus.bot.api.input.FocusState;
import com.botwithus.bot.api.input.KeyResult;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.rpc.RpcRemoteException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;

import static com.botwithus.bot.core.impl.MapHelper.getBool;
import static com.botwithus.bot.core.impl.MapHelper.getString;

/**
 * The {@link KeyboardAPI} half of {@link GameAPIImpl}: {@code send_key},
 * {@code set_focus_mode} and {@code get_focus_mode} on the pipe.
 *
 * <p>Focus refusals come back from the agent as a handler error, which
 * {@link RpcClient#callSync} raises as an {@link RpcRemoteException} carrying
 * the agent's own text. Only the texts named here are turned into results;
 * every other refusal, and every transport failure, propagates as it does for
 * any other call.</p>
 */
final class KeyboardBinding {

    private static final Logger log = LoggerFactory.getLogger(KeyboardBinding.class);

    static final String SEND_KEY = "send_key";
    static final String SET_FOCUS_MODE = "set_focus_mode";
    static final String GET_FOCUS_MODE = "get_focus_mode";

    static final String NOT_FOCUSED = "not_focused";
    static final String NO_WINDOW = "no_window";
    static final String FOCUS_LOST = "focus_lost";
    static final String NOT_AVAILABLE = "set_focus_mode: not_available";
    static final String SWAP_FAILED = "set_focus_mode: swap_failed";

    private static final String MODE_REAL = "real";
    private static final String MODE_AGENT = "agent";

    private final RpcClient rpc;

    KeyboardBinding(RpcClient rpc) {
        this.rpc = rpc;
    }

    /** See {@link KeyboardAPI#sendKey(int, int)}. */
    KeyResult sendKey(int virtualKey, int holdMs) {
        checkRange("virtualKey", virtualKey, KeyboardAPI.MAX_VIRTUAL_KEY);
        checkRange("holdMs", holdMs, KeyboardAPI.MAX_HOLD_MS);
        // Neither char nor extended is sent: this version presses keys only, and
        // the agent derives extended from the vk when the field is absent.
        Map<String, Object> reply;
        try {
            reply = rpc.callSync(SEND_KEY, Map.of("vk", virtualKey, "hold_ms", holdMs));
        } catch (RpcRemoteException e) {
            return focusRefusal(e);
        }
        return getBool(reply, "sent_vk") ? KeyResult.SENT : KeyResult.NOT_SENT;
    }

    /** See {@link KeyboardAPI#setFocusMode(FocusMode)}. */
    FocusState setFocusMode(FocusMode mode) {
        Objects.requireNonNull(mode, "mode");
        try {
            rpc.callSync(SET_FOCUS_MODE, Map.of("mode", wireName(mode)));
        } catch (RpcRemoteException e) {
            String refusal = e.producerMessage();
            // Both are soft: nothing the caller can fix by catching, and the
            // read-back below says what is in effect.
            if (!NOT_AVAILABLE.equals(refusal) && !SWAP_FAILED.equals(refusal)) {
                throw e;
            }
            log.debug("set_focus_mode {} refused: {}", wireName(mode), refusal);
        }
        // The set reply has no real_focus_on_view, so the state is read back
        // either way. After a refusal this is also the only honest answer:
        // a swap_failed can leave the client in either mode.
        return focusMode();
    }

    /** See {@link KeyboardAPI#focusMode()}. */
    FocusState focusMode() {
        Map<String, Object> reply = rpc.callSync(GET_FOCUS_MODE, Map.of());
        boolean armed = getBool(reply, "armed");
        return new FocusState(parseMode(getString(reply, "mode"), armed), armed,
                getBool(reply, "available"), getBool(reply, "real_focus_on_view"));
    }

    private static KeyResult focusRefusal(RpcRemoteException refusal) {
        return switch (refusal.producerMessage()) {
            case NOT_FOCUSED -> KeyResult.NOT_FOCUSED;
            case NO_WINDOW -> KeyResult.NO_WINDOW;
            case FOCUS_LOST -> KeyResult.FOCUS_LOST;
            default -> throw refusal;
        };
    }

    private static String wireName(FocusMode mode) {
        return switch (mode) {
            case REAL -> MODE_REAL;
            case AGENT -> MODE_AGENT;
        };
    }

    /**
     * The agent sends {@code "real"} or {@code "agent"}. Anything else falls
     * back to {@code armed}, which the agent documents as identical to the mode
     * in practice.
     */
    private static FocusMode parseMode(String wire, boolean armed) {
        return switch (wire) {
            case MODE_REAL -> FocusMode.REAL;
            case MODE_AGENT -> FocusMode.AGENT;
            default -> {
                log.warn("get_focus_mode: unknown mode '{}', using armed={}", wire, armed);
                yield armed ? FocusMode.AGENT : FocusMode.REAL;
            }
        };
    }

    private static void checkRange(String name, int value, int max) {
        if (value < 0 || value > max) {
            throw new IllegalArgumentException(name + " must be 0.." + max + ", was " + value);
        }
    }
}
