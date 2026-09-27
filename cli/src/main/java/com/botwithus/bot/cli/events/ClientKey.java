package com.botwithus.bot.cli.events;

import com.botwithus.bot.cli.AccountReply;

import java.util.Objects;
import java.util.Optional;

/**
 * The identity a client is known by across pipes and host restarts.
 *
 * <p>A client that reports a real account UUID is keyed by it: the UUID survives
 * a game restart, which gives the client a new pipe, and a host restart. A
 * client that reports none, or reports one of the placeholders a development
 * launch produces, is keyed by its pipe, so two such clients never share a key.
 * A pipe key lasts only as long as the pipe.</p>
 *
 * <p>{@link #value()} is the key as text: the UUID, the UUID with {@code #n}
 * appended for the n-th client open on the same account at once, or
 * {@code pipe:<name>}.</p>
 */
public sealed interface ClientKey {

    /** The prefix of a pipe key's {@link #value()}. */
    String PIPE_PREFIX = "pipe:";

    /** The key as text; see the type's description. */
    String value();

    /**
     * Whether a client under this key can be remembered once it closes and
     * recognised when it comes back: only the first client on an account can.
     */
    boolean isRemembered();

    /** The account UUID behind this key; empty for a pipe key. */
    Optional<String> accountUuid();

    /**
     * An account's client. The first client open on an account is instance
     * {@code 1}; a second one open at the same time, which should not happen,
     * is instance {@code 2}, and so on.
     *
     * @param uuid     the account UUID, never a development placeholder
     * @param instance which client on the account this is, from {@code 1}
     */
    record Account(String uuid, int instance) implements ClientKey {

        private static final String INSTANCE_SEPARATOR = "#";

        public Account {
            Objects.requireNonNull(uuid, "uuid");
            if (AccountReply.identified(uuid).isEmpty()) {
                throw new IllegalArgumentException("not an account uuid: '" + uuid + "'");
            }
            if (instance < 1) {
                throw new IllegalArgumentException("instance must be positive: " + instance);
            }
        }

        @Override
        public String value() {
            return instance == 1 ? uuid : uuid + INSTANCE_SEPARATOR + instance;
        }

        @Override
        public boolean isRemembered() {
            return instance == 1;
        }

        @Override
        public Optional<String> accountUuid() {
            return Optional.of(uuid);
        }

        @Override
        public String toString() {
            return value();
        }
    }

    /**
     * A client with no account UUID to key it by, keyed by its pipe instead.
     *
     * @param pipe the pipe name
     */
    record Pipe(String pipe) implements ClientKey {

        public Pipe {
            Objects.requireNonNull(pipe, "pipe");
            if (pipe.isEmpty()) {
                throw new IllegalArgumentException("pipe name is empty");
            }
        }

        @Override
        public String value() {
            return PIPE_PREFIX + pipe;
        }

        @Override
        public boolean isRemembered() {
            return false;
        }

        @Override
        public Optional<String> accountUuid() {
            return Optional.empty();
        }

        @Override
        public String toString() {
            return value();
        }
    }

    /** The key of the first client on account {@code uuid}. */
    static ClientKey account(String uuid) {
        return new Account(uuid, 1);
    }

    /** The key of a client known only by its pipe. */
    static ClientKey pipe(String pipe) {
        return new Pipe(pipe);
    }

    /**
     * The key for a client on {@code pipe} that reported {@code accountUuid}: the
     * account's key when the UUID identifies one, else the pipe's. A missing UUID,
     * an empty one and the development placeholder all fall back to the pipe.
     *
     * @param accountUuid the raw {@code account_uuid} the agent sent, or {@code null}
     */
    static ClientKey of(String accountUuid, String pipe) {
        return AccountReply.identified(accountUuid).map(ClientKey::account).orElseGet(() -> pipe(pipe));
    }
}
