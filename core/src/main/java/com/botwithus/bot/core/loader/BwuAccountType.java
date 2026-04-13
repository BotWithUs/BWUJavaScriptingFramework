package com.botwithus.bot.core.loader;

public enum BwuAccountType {
    DEFAULT(0),
    MANAGED(1),
    PLATFORM(2);

    private final int value;

    BwuAccountType(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    public static BwuAccountType fromValue(int value) {
        return switch (value) {
            case 0 -> DEFAULT;
            case 1 -> MANAGED;
            case 2 -> PLATFORM;
            default -> throw new IllegalArgumentException("Unknown BwuAccountType: " + value);
        };
    }
}
