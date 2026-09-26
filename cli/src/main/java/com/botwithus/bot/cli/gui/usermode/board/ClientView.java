package com.botwithus.bot.cli.gui.usermode.board;

import java.util.Locale;

/**
 * One client card's worth of data, rebuilt every frame.
 *
 * @param id      the pipe name; unique and stable for the connection's life
 * @param account display name, or the pipe name until the account reply lands
 * @param world   world number, or {@code 0} when unknown
 */
public record ClientView(String id, String account, int world, ClientStatus status) {

    /** The script this card is about, if any state names one. */
    public ScriptInfo scriptOrNull() {
        return switch (status) {
            case ClientStatus.Running r -> r.script();
            case ClientStatus.Crashed c -> c.script();
            case ClientStatus.Lost l -> l.wasRunning();
            case ClientStatus.Idle ignored -> null;
            case ClientStatus.Loading ignored -> null;
            case ClientStatus.Reconnecting ignored -> null;
        };
    }

    /** Case-insensitive match on account, script name or pipe name. */
    public boolean matches(String query) {
        if (query.isBlank()) {
            return true;
        }
        String needle = query.strip().toLowerCase(Locale.ROOT);
        ScriptInfo script = scriptOrNull();
        String haystack = account + ' ' + (script != null ? script.name() : "") + ' ' + id;
        return haystack.toLowerCase(Locale.ROOT).contains(needle);
    }
}
