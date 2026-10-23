package com.botwithus.bot.cli.groups;

import com.botwithus.bot.cli.events.ClientKey;

import java.util.Objects;
import java.util.Optional;

/** What asking to add a client to a group did. */
public sealed interface MemberChange {

    /** The client joined; {@code group} is the group as it is now. */
    record Added(ClientGroup group) implements MemberChange {
        public Added {
            Objects.requireNonNull(group, "group");
        }
    }

    /** The client was a member already; nothing changed. */
    record AlreadyMember() implements MemberChange { }

    /** No group has that id. */
    record NoSuchGroup() implements MemberChange { }

    /**
     * The client cannot be a member.
     *
     * @param reason why, in words for the user
     */
    record Refused(String reason) implements MemberChange {
        public Refused {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * Why a client under {@code key} cannot be a group member, if it cannot.
     * Only the first client on a real account can: a member is remembered by its
     * account, and neither a pipe key nor a second client open on the same
     * account at once is recognised again after it closes.
     */
    static Optional<Refused> refusalFor(ClientKey key) {
        return switch (key) {
            case ClientKey.Pipe pipe -> Optional.of(new Refused("The client on pipe " + pipe.pipe()
                    + " reported no account UUID, so it would not be recognised once it closes."
                    + " Only clients on a real account can join a group."));
            case ClientKey.Account account when !account.isRemembered() -> Optional.of(new Refused(
                    "This is a second client open on account " + account.uuid() + " at the same time."
                            + " Only the first client on an account can join a group."));
            case ClientKey.Account _ -> Optional.empty();
        };
    }
}
