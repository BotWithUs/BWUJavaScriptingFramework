package com.botwithus.bot.cli;

import com.botwithus.bot.core.impl.MapHelper;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Typed reading of the agent's {@code get_account_info} reply.
 *
 * <p>Newer agents add {@code account_name} (the launcher's name for the account,
 * known before the client logs in) and {@code game_state}. Older agents send
 * neither, so both are optional here and every reader has a fallback. An empty
 * string means "unknown" for every name field.</p>
 *
 * @param raw the reply as decoded from the wire
 */
public record AccountReply(Map<String, Object> raw) {

    /** The {@code account_uuid} an agent reports when it was loaded with no launch details. */
    public static final String DEV_UUID = "dev_uuid";

    private static final String DISPLAY_NAME = "display_name";
    private static final String JX_DISPLAY_NAME = "jx_display_name";
    private static final String ACCOUNT_NAME = "account_name";
    private static final String ACCOUNT_UUID = "account_uuid";
    private static final String GAME_STATE = "game_state";
    private static final String IS_MEMBER = "is_member";

    public AccountReply {
        if (raw == null) {
            throw new IllegalArgumentException("raw");
        }
    }

    /**
     * The character's name as the client reports it once logged in, else as the
     * Jagex launcher passed it in. Auto-start treats a client as identified once
     * this is known, and nudges one without it toward the lobby, so it
     * deliberately leaves out {@code account_name}: that is known from the start
     * and would stop the nudge.
     */
    public Optional<String> characterName() {
        return firstKnown(DISPLAY_NAME, JX_DISPLAY_NAME);
    }

    /**
     * The name of the character the client is logged in as, read from the game
     * itself ({@code display_name}). The agent sends it empty outside a world, so
     * this is empty at the login screen and in the lobby. Unlike
     * {@link #characterName()} it never falls back to {@link #launchedName()},
     * which says who the client was launched for, not who is logged in now.
     */
    public Optional<String> inGameName() {
        return firstKnown(DISPLAY_NAME);
    }

    /**
     * The Jagex character the client was launched for ({@code jx_display_name}),
     * as the Jagex launcher passed it in when the process started. Known from the
     * login screen on and fixed for the life of the process; empty when the client
     * was not started by the Jagex launcher.
     */
    public Optional<String> launchedName() {
        return firstKnown(JX_DISPLAY_NAME);
    }

    /** The launcher's name for the account ({@code account_name}); empty from an older agent. */
    public Optional<String> accountName() {
        return firstKnown(ACCOUNT_NAME);
    }

    /**
     * The best name to show for the client: {@link #characterName()}, else the
     * account name the loader was given. Empty when none is set.
     */
    public Optional<String> displayName() {
        return firstKnown(DISPLAY_NAME, JX_DISPLAY_NAME, ACCOUNT_NAME);
    }

    /**
     * The account UUID when it identifies a real account. Empty when the reply has
     * none, or has one of the placeholders a development launch produces: an empty
     * string (launch details zeroed) or {@link #DEV_UUID} (no launch details at all).
     * Those are shared by every such client, so they must not key anything.
     */
    public Optional<String> identifiedUuid() {
        return identified(MapHelper.getStringNullable(raw, ACCOUNT_UUID));
    }

    /** The client's game state, when the agent is new enough to report it here. */
    public Optional<GameState> gameState() {
        if (!raw.containsKey(GAME_STATE)) {
            return Optional.empty();
        }
        return Optional.of(GameState.fromWire(MapHelper.getIntOr(raw, GAME_STATE, 0)));
    }

    /** Whether the account is a member; only meaningful once the client is in a world. */
    public boolean isMember() {
        return MapHelper.getBool(raw, IS_MEMBER);
    }

    /** {@code uuid} unless it is missing or a development placeholder. */
    public static Optional<String> identified(String uuid) {
        if (!isKnown(uuid) || DEV_UUID.equals(uuid)) {
            return Optional.empty();
        }
        return Optional.of(uuid);
    }

    private Optional<String> firstKnown(String... keys) {
        return Stream.of(keys)
                .map(key -> MapHelper.getStringNullable(raw, key))
                .filter(AccountReply::isKnown)
                .findFirst();
    }

    private static boolean isKnown(String value) {
        return value != null && !value.isBlank();
    }
}
