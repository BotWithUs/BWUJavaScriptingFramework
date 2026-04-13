package com.botwithus.bot.core.loader;

public enum BwuTargetType {
    PRIMARY(0),
    SECONDARY(1);

    private final int value;

    BwuTargetType(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    public static BwuTargetType fromValue(int value) {
        return switch (value) {
            case 0 -> PRIMARY;
            case 1 -> SECONDARY;
            default -> throw new IllegalArgumentException("Unknown BwuTargetType: " + value);
        };
    }
}
