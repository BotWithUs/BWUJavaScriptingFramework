package com.botwithus.bot.core.rpc;

import java.util.Map;

/**
 * Told about every RPC call before it is sent, on the calling thread. Used to
 * keep a script run's breadcrumbs; it must be cheap and must not throw, though
 * {@link RpcClient} contains a throw so it can never fail the call.
 */
@FunctionalInterface
public interface RpcCallObserver {

    /** Observes nothing. */
    RpcCallObserver NONE = (method, params) -> { };

    /** One call of {@code method}; {@code params} must not be modified. */
    void onCall(String method, Map<String, Object> params);
}
