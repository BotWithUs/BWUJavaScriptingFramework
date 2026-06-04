package com.botwithus.bot.core.rpc;

public class RpcException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RpcException(String message) { super(message); }
    public RpcException(String message, Throwable cause) { super(message, cause); }
}
