package com.botwithus.bot.cli.clients;

import com.botwithus.bot.cli.AccountReply;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * What the host keeps about a client on a real account between runs, so its
 * card is still there after a restart.
 *
 * @param accountUuid the account UUID; never a development placeholder
 * @param name        the name the client last showed, if any
 * @param lastWorld   the world it was last seen in, if any
 * @param lastSeenAt  when the host last saw it connected
 */
public record RememberedClient(String accountUuid, Optional<String> name, OptionalInt lastWorld,
                               Instant lastSeenAt) {

    public RememberedClient {
        Objects.requireNonNull(accountUuid, "accountUuid");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(lastWorld, "lastWorld");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt");
        if (AccountReply.identified(accountUuid).isEmpty()) {
            throw new IllegalArgumentException("not an account uuid: '" + accountUuid + "'");
        }
    }
}
