package com.botwithus.bot.api;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Global bridge for the currently active client {@link GameAPI}.
 *
 * <p>This is intended for UI surfaces that need read-only live client state
 * before a script has been started and therefore before a {@link ScriptContext}
 * exists.</p>
 */
public final class ActiveGameApiRegistry {

    private static final AtomicReference<GameAPI> ACTIVE_GAME_API = new AtomicReference<>();

    private ActiveGameApiRegistry() {}

    public static void set(GameAPI gameApi) {
        ACTIVE_GAME_API.set(gameApi);
    }

    public static Optional<GameAPI> get() {
        return Optional.ofNullable(ACTIVE_GAME_API.get());
    }

    public static void clear() {
        ACTIVE_GAME_API.set(null);
    }
}
