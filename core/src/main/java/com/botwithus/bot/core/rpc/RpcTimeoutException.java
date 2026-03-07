package com.botwithus.bot.core.rpc;

/**
 * Thrown when an RPC call exceeds its configured timeout.
 */
public class RpcTimeoutException extends RpcException {
    private final String method;
    private final long timeoutMs;

    public RpcTimeoutException(String method, long timeoutMs) {
        super("RPC call '" + method + "' timed out after " + timeoutMs + "ms");
        this.method = method;
        this.timeoutMs = timeoutMs;
    }

    public String getMethod() { return method; }
    public long getTimeoutMs() { return timeoutMs; }
}
