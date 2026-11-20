package com.botwithus.bot.api.gameui;

/** What input an {@link InputField} accepts, as the client names it. */
public enum InputFilter {

    NONE("none"),
    ALPHANUMERIC("alphanumeric"),
    NUMERIC("numeric"),
    QUANTITY("quantity"),
    NUMBERS_AS_TEXT("numbers_as_text"),
    /** Read back for a filter the agent does not name. Never settable. */
    OTHER("other");

    private final String wireName;

    InputFilter(String wireName) {
        this.wireName = wireName;
    }

    /** The name the {@code filter} prop takes and readback reports. */
    public String wireName() {
        return wireName;
    }

    /** The constant for a readback name; {@link #OTHER} for anything unrecognised. */
    public static InputFilter fromWireName(String name) {
        for (InputFilter filter : values()) {
            if (filter.wireName.equals(name)) {
                return filter;
            }
        }
        return OTHER;
    }
}
