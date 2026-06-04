package com.botwithus.bot.core.msgpack;

/**
 * Thrown when MessagePack encoding or decoding fails.
 */
public class MessagePackException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MessagePackException(String message) { super(message); }
    public MessagePackException(String message, Throwable cause) { super(message, cause); }
}
