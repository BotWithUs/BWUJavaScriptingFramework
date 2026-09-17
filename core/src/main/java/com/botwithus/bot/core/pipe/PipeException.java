package com.botwithus.bot.core.pipe;

public class PipeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PipeException(String message) { super(message); }
    public PipeException(String message, Throwable cause) { super(message, cause); }
}
