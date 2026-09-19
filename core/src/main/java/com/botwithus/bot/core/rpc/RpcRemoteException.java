package com.botwithus.bot.core.rpc;

/**
 * The call reached the agent and the agent answered with an error.
 *
 * <p>Distinguished from a plain {@link RpcException} — which also covers a write the
 * pipe rejected, a reader that died, and a codec failure — because <b>"the producer
 * refused this" and "the transport is gone" are different conditions and a caller
 * can act on only one of them</b>. A refusal means this request was wrong and the
 * next one may well be fine; a transport failure means nothing will work until the
 * connection is rebuilt, and swallowing it hides a dead pipe.</p>
 *
 * <p>The distinction was previously available only by inspecting whether the
 * exception carried a cause, which is not a contract anyone should rely on. It is a
 * subclass rather than a flag so existing {@code catch (RpcException e)} arms keep
 * catching it unchanged.</p>
 *
 * <p>The first consumer is {@code GameAPIImpl.drawHighlights}, which has to report a
 * refused highlight as a count — the contract {@code DrawFrame} already gives its
 * callers for a refused primitive — while still letting a dead pipe propagate. Doing
 * that by catching {@link RpcException} broadly would have turned a dead pipe into a
 * silent {@code dropped}.</p>
 *
 * <p>{@link #getMessage()} keeps the {@code "RPC error: <producer message>"} shape
 * the plain exception used, so callers and tests that match on the producer's own
 * wording are unaffected. {@link #producerMessage()} is that wording alone.</p>
 */
public class RpcRemoteException extends RpcException {

    private static final long serialVersionUID = 1L;

    private final String method;
    private final String producerMessage;

    public RpcRemoteException(String method, String producerMessage) {
        super("RPC error: " + producerMessage);
        this.method = method;
        this.producerMessage = producerMessage;
    }

    /** The RPC method that was refused. */
    public String getMethod() {
        return method;
    }

    /** The producer's own error text, without this class's prefix. */
    public String producerMessage() {
        return producerMessage;
    }
}
