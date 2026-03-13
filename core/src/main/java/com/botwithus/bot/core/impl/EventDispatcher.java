package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.event.*;
import com.botwithus.bot.api.model.ChatMessage;

import java.util.Map;

import static com.botwithus.bot.core.impl.MapHelper.*;

/**
 * Converts raw pipe event maps into typed {@link GameEvent} instances
 * and publishes them to the {@link EventBus}.
 *
 * <p>Also manages automatic server-side subscriptions: when a script
 * subscribes to e.g. {@code TickEvent.class} on the EventBus, the
 * dispatcher automatically calls {@code GameAPI.subscribe("tick")}.</p>
 */
public class EventDispatcher {

    private static final Map<Class<? extends GameEvent>, String> EVENT_NAMES = Map.of(
            TickEvent.class, "tick",
            LoginStateChangeEvent.class, "login_state_change",
            VarChangeEvent.class, "var_change",
            VarbitChangeEvent.class, "varbit_change",
            KeyInputEvent.class, "key_input",
            ActionExecutedEvent.class, "action_executed",
            BreakStartedEvent.class, "break_started",
            BreakEndedEvent.class, "break_ended",
            ChatMessageEvent.class, "chat_message"
    );

    private final EventBusImpl eventBus;

    public EventDispatcher(EventBusImpl eventBus) {
        this.eventBus = eventBus;
    }

    /**
     * Wires up automatic server-side subscription management.
     * When a script subscribes to a typed event on the EventBus,
     * the corresponding {@code GameAPI.subscribe()} call is made
     * automatically. Unsubscribing the last listener calls
     * {@code GameAPI.unsubscribe()}.
     */
    public void bindAutoSubscription(GameAPI api) {
        eventBus.setSubscriptionHooks(
                eventClass -> {
                    String name = EVENT_NAMES.get(eventClass);
                    if (name != null) {
                        api.subscribe(name);
                    }
                },
                eventClass -> {
                    String name = EVENT_NAMES.get(eventClass);
                    if (name != null) {
                        api.unsubscribe(name);
                    }
                }
        );
    }

    /**
     * Handles a raw event map from the pipe. Converts it to the appropriate
     * typed {@link GameEvent} and publishes it to the event bus.
     *
     * @param raw the raw event map with "event" and "data" fields
     */
    @SuppressWarnings("unchecked")
    public void dispatch(Map<String, Object> raw) {
        String eventType = (String) raw.get("event");
        if (eventType == null) return;

        Map<String, Object> data = raw.get("data") instanceof Map<?, ?>
                ? (Map<String, Object>) raw.get("data")
                : Map.of();

        GameEvent event = switch (eventType) {
            case "tick" -> new TickEvent(
                    getInt(data, "tick")
            );
            case "login_state_change" -> new LoginStateChangeEvent(
                    getInt(data, "old_state"),
                    getInt(data, "new_state")
            );
            case "var_change" -> new VarChangeEvent(
                    getInt(data, "var_id"),
                    getInt(data, "old_value"),
                    getInt(data, "new_value")
            );
            case "varbit_change" -> new VarbitChangeEvent(
                    getInt(data, "var_id"),
                    getInt(data, "old_value"),
                    getInt(data, "new_value")
            );
            case "key_input" -> new KeyInputEvent(
                    getInt(data, "key"),
                    getBool(data, "is_alt"),
                    getBool(data, "is_ctrl"),
                    getBool(data, "is_shift")
            );
            case "action_executed" -> new ActionExecutedEvent(
                    getInt(data, "action_id"),
                    getInt(data, "param1"),
                    getInt(data, "param2"),
                    getInt(data, "param3")
            );
            case "break_started" -> new BreakStartedEvent(
                    getInt(data, "duration_seconds"),
                    getDouble(data, "fatigue"),
                    getDouble(data, "risk")
            );
            case "break_ended" -> new BreakEndedEvent();
            case "chat_message" -> new ChatMessageEvent(new ChatMessage(
                    getInt(data, "index"),
                    getInt(data, "message_type"),
                    getStringNullable(data, "text"),
                    getStringNullable(data, "player_name")
            ));
            default -> null;
        };

        if (event != null) {
            eventBus.publish(event);
        }
    }
}
