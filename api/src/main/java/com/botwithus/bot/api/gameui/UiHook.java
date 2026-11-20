package com.botwithus.bot.api.gameui;

import java.util.Optional;

/**
 * A client event a script can subscribe to on one of its own components, named as
 * the client's own {@code ui.Hook} is, lower-cased on the wire.
 *
 * <p>Which types accept which hook is the agent's to enforce: a hook that does not
 * fit the component is refused with {@code ui: hook not valid for this type}, as a
 * {@link UiRefusedException}. The table, as the agent has it:</p>
 * <ul>
 *   <li>{@link #ON_CLICK}, {@link #ON_RELEASE}, {@link #ON_MOUSE_OVER},
 *       {@link #ON_MOUSE_LEAVE}: every type; delivered as {@link UiHookEvent.Pointer}.</li>
 *   <li>{@link #ON_BUTTON_PRESSED}: button and check; {@link UiHookEvent.ButtonPressed}.</li>
 *   <li>{@link #ON_CONTENT_CHANGED}: input field; {@link UiHookEvent.ContentChanged}.</li>
 *   <li>{@link #ON_SELECTION_CHANGED}: slider ({@link UiHookEvent.SliderChanged}),
 *       combo field and list ({@link UiHookEvent.SelectionChanged}).</li>
 * </ul>
 *
 * <p>{@code destroyed} is not here: it needs no subscription and keeps arriving
 * through {@link GameUi#onEvent} as a {@link UiEvent}.</p>
 */
public enum UiHook {

    ON_CLICK("onclick", 0),
    ON_RELEASE("onrelease", 2),
    /** Raised only while the client's script-controlled mouseover toggle is on, the default. */
    ON_MOUSE_OVER("onmouseover", 4),
    ON_MOUSE_LEAVE("onmouseleave", 6),
    ON_BUTTON_PRESSED("onbuttonpressed", 49),
    /**
     * Raised for typed input, submits and focus changes, <b>and for the script's own
     * {@code content} set</b>: a subscribed script hears its own writes.
     */
    ON_CONTENT_CHANGED("oncontentchanged", 50),
    /**
     * Raised by the user's interaction only. A {@code value} or {@code selected} the
     * script sets itself does not raise it.
     */
    ON_SELECTION_CHANGED("onselectionchanged", 51);

    private final String wireName;
    private final int clientId;

    UiHook(String wireName, int clientId) {
        this.wireName = wireName;
        this.clientId = clientId;
    }

    /** The name {@code ui.subscribe} takes and {@code ui.event} reports. */
    public String wireName() {
        return wireName;
    }

    /** The client's own {@code ui.Hook} number; diagnostic. */
    public int clientId() {
        return clientId;
    }

    /** Whether its events carry a pointer position ({@link UiHookEvent.Pointer}). */
    public boolean isPointer() {
        return this == ON_CLICK || this == ON_RELEASE || this == ON_MOUSE_OVER || this == ON_MOUSE_LEAVE;
    }

    /** The hook a {@code ui.event} names; empty for {@code destroyed} and for any name this host does not know. */
    public static Optional<UiHook> fromWireName(String name) {
        for (UiHook hook : values()) {
            if (hook.wireName.equals(name)) {
                return Optional.of(hook);
            }
        }
        return Optional.empty();
    }
}
