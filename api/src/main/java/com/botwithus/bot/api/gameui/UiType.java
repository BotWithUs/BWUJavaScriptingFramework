package com.botwithus.bot.api.gameui;

/**
 * The kind of a plugin-UI component, as the client's own {@code ui.ComponentType}
 * numbers it.
 *
 * <p>Only the types the agent can create today have a constant here, plus
 * {@link #OTHER} for anything else a Jagex handle can report. The agent refuses
 * every other type with {@code ui type not supported yet: <type>}; when it learns
 * one (buttons, input fields, sliders and so on), it arrives as a new constant and
 * a new {@code create*} method on {@link GameUi}, so nothing a script already
 * calls changes shape.</p>
 */
public enum UiType {

    LINE("line", 0),
    RECTANGLE("rectangle", 1),
    TEXT("text", 2),
    CANVAS("canvas", 8),
    LAYER("layer", 20),
    /** A Jagex component of a type the agent does not name. Never creatable. */
    OTHER("other", -1);

    private final String wireName;
    private final int clientType;

    UiType(String wireName, int clientType) {
        this.wireName = wireName;
        this.clientType = clientType;
    }

    /** The name {@code ui.create} takes and {@code type_name} reports. */
    public String wireName() {
        return wireName;
    }

    /** The client's {@code ui.ComponentType} value; {@code -1} for {@link #OTHER}. */
    public int clientType() {
        return clientType;
    }

    /** Whether {@code ui.create} accepts this type. */
    public boolean isCreatable() {
        return this != OTHER;
    }

    /** The constant for a {@code type_name}; {@link #OTHER} for anything unrecognised. */
    public static UiType fromWireName(String name) {
        for (UiType type : values()) {
            if (type.wireName.equals(name)) {
                return type;
            }
        }
        return OTHER;
    }
}
