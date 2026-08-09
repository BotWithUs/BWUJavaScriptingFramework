package com.botwithus.bot.core.gameval;

/** Unchecked failure opening or reading the gameval index ({@code gameval.sqlite}). */
public final class GamevalIndexException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public GamevalIndexException(String message, Throwable cause) {
        super(message, cause);
    }

    public GamevalIndexException(String message) {
        super(message);
    }
}
