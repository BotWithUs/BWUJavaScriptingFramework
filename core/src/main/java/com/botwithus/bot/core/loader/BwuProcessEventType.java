package com.botwithus.bot.core.loader;

public enum BwuProcessEventType {
    NONE(0),
    START(1),
    EXIT(2);

    private final int value;

    BwuProcessEventType(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    public static BwuProcessEventType fromValue(int value) {
        return switch (value) {
            case 0 -> NONE;
            case 1 -> START;
            case 2 -> EXIT;
            default -> throw new IllegalArgumentException("Unknown BwuProcessEventType: " + value);
        };
    }
}
