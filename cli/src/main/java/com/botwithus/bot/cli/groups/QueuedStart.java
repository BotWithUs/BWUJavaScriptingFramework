package com.botwithus.bot.cli.groups;

import com.botwithus.bot.cli.AccountReply;

import java.time.Instant;
import java.util.Objects;

/**
 * A request to start a script on a client that was not connected when it was
 * made. It waits in the {@link StartWhenBackQueue} until the client is back.
 *
 * @param accountUuid the client's account; never a development placeholder
 * @param script      the script's name, as its manifest gives it
 * @param requestedAt when the start was asked for
 */
public record QueuedStart(String accountUuid, String script, Instant requestedAt) {

    public QueuedStart {
        Objects.requireNonNull(accountUuid, "accountUuid");
        Objects.requireNonNull(script, "script");
        Objects.requireNonNull(requestedAt, "requestedAt");
        if (AccountReply.identified(accountUuid).isEmpty()) {
            throw new IllegalArgumentException("not an account uuid: '" + accountUuid + "'");
        }
        if (script.isBlank()) {
            throw new IllegalArgumentException("script name is blank");
        }
    }

    /**
     * Whether this is the request to start {@code scriptName} on account
     * {@code uuid}. Script names match ignoring case, as the runtime matches them.
     */
    public boolean isFor(String uuid, String scriptName) {
        return accountUuid.equals(uuid) && script.equalsIgnoreCase(scriptName);
    }
}
