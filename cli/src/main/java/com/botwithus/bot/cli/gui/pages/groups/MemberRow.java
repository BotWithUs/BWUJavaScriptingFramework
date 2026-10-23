package com.botwithus.bot.cli.gui.pages.groups;

import com.botwithus.bot.cli.events.ClientKey;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One row of a group's members table. */
public sealed interface MemberRow {

    /** What the row's checkbox selects it by; unique within the group. */
    String key();

    /**
     * A member on an account.
     *
     * @param account      the name the client shows, else its account UUID
     * @param primary      the script the row shows; see {@link MemberFacts#primary()}
     * @param moreScripts  how many other scripts the client shows
     * @param otherGroups  the names of the other groups the member is in
     */
    record Account(MemberFacts facts, String account, Optional<ScriptFact> primary, int moreScripts,
                   List<String> otherGroups, RowAction action) implements MemberRow {

        public Account {
            Objects.requireNonNull(facts, "facts");
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(primary, "primary");
            Objects.requireNonNull(action, "action");
            otherGroups = List.copyOf(otherGroups);
        }

        @Override
        public String key() {
            return facts.uuid();
        }

        /** The first characters of the account UUID, as the table shows it. */
        public String shortUuid() {
            return GroupText.shortUuid(facts.uuid());
        }
    }

    /**
     * A member carried over from a group saved before members were accounts,
     * whose account is still not known: "unknown client (pipe X)".
     *
     * @param pipe the pipe it was on when the group was saved
     */
    record Unresolved(String pipe) implements MemberRow {

        public Unresolved {
            Objects.requireNonNull(pipe, "pipe");
        }

        @Override
        public String key() {
            return ClientKey.PIPE_PREFIX + pipe;
        }
    }
}
