package com.botwithus.bot.cli.gui.pages.groups;

import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.groups.ClientGroup;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * A client the host knows, live or remembered, as the Add clients and New group
 * dialogs list it.
 *
 * @param name       the name it shows, else the last one it showed
 * @param world      the world it is in, else the last one it was seen in
 * @param script     the script it is running or last ran, if any
 * @param refusal    why it cannot join a group, in words for the user; empty
 *                   when it can
 */
public record PickableClient(ClientKey key, Optional<String> name, OptionalInt world, Optional<String> script,
                             Optional<String> refusal) {

    public PickableClient {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(script, "script");
        Objects.requireNonNull(refusal, "refusal");
    }

    /** The account UUID; empty for a client keyed by its pipe. */
    public Optional<String> accountUuid() {
        return key.accountUuid();
    }

    /** Whether the client can be a group member; see {@link #refusal()}. */
    public boolean canJoin() {
        return refusal.isEmpty();
    }

    /** Whether it is a member of any of {@code groups}. */
    public boolean isInAny(List<ClientGroup> groups) {
        return accountUuid().filter(uuid -> groups.stream().anyMatch(group -> group.contains(uuid))).isPresent();
    }
}
