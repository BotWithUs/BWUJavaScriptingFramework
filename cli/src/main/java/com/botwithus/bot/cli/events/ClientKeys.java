package com.botwithus.bot.cli.events;

import com.botwithus.bot.cli.events.HostEvent.ClientForgotten;
import com.botwithus.bot.cli.events.HostEvent.ClientIdentified;
import com.botwithus.bot.cli.events.HostEvent.ClientOpened;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Decides which {@link ClientKey} each pipe's events are published under, and
 * publishes them.
 *
 * <p>A pipe is keyed by itself from the moment it opens until the host reads
 * the client's account; {@link #identify} then settles its key. Every event
 * about a client goes through {@link #publish}, which looks the key up and
 * queues the event under one lock, the same lock {@link #identify} changes keys
 * under. So the order events reach subscribers in is also the order their keys
 * were settled in: nothing about a pipe arrives under its old key after the
 * {@link ClientIdentified} that retired it.</p>
 *
 * <p><b>Two clients on one account.</b> The first client open on an account
 * gets the account's key. Should a second identify on the same account while the
 * first is still live, it gets the next instance ({@code uuid#2}) and a warning
 * is logged. A pipe that is no longer live does not hold its key: a client whose
 * game was restarted identifies on a new pipe, takes the key over, and the dead
 * pipe falls back to a pipe key of its own.</p>
 *
 * <p>Thread-safe. Nothing here blocks: publishing only queues the event.</p>
 */
public final class ClientKeys {

    private static final Logger log = LoggerFactory.getLogger(ClientKeys.class);

    /** A pipe's key, and whether its account has been read yet. */
    private record Binding(ClientKey key, boolean isIdentified) { }

    private final HostEventBus bus;
    private final Predicate<String> isLive;
    private final Object lock = new Object();
    /** Every pipe seen and not forgotten. Guarded by {@link #lock}. */
    private final Map<String, Binding> byPipe = new HashMap<>();

    /**
     * @param bus    where the events go
     * @param isLive whether a pipe is open right now; a pipe that is not does
     *               not keep its account's key from a client that identifies on
     *               another pipe. Called with this object's lock held, so it
     *               must not block or call back in here.
     */
    public ClientKeys(HostEventBus bus, Predicate<String> isLive) {
        this.bus = bus;
        this.isLive = isLive;
    }

    /** The client on {@code pipe} as its events are keyed now. A pipe never seen is keyed by itself. */
    public ClientRef refFor(String pipe) {
        synchronized (lock) {
            return new ClientRef(keyOf(pipe), pipe);
        }
    }

    /** The pipes currently keyed as {@code key}, in no particular order. */
    public List<String> pipesOf(ClientKey key) {
        synchronized (lock) {
            List<String> pipes = new ArrayList<>();
            byPipe.forEach((pipe, binding) -> {
                if (binding.key().equals(key)) {
                    pipes.add(pipe);
                }
            });
            return pipes;
        }
    }

    /** Publishes the event {@code eventFor} builds for the client on {@code pipe}, under its current key. */
    public void publish(String pipe, Function<ClientRef, ? extends HostEvent> eventFor) {
        synchronized (lock) {
            bus.publish(eventFor.apply(new ClientRef(keyOf(pipe), pipe)));
        }
    }

    /**
     * A connection opened on {@code pipe}: keys it by the pipe until it is
     * identified, and publishes {@link ClientOpened}. A pipe name seen before
     * belongs to a new process now, so whatever it was keyed as is dropped.
     */
    public void opened(String pipe, Instant at) {
        synchronized (lock) {
            ClientKey key = ClientKey.pipe(pipe);
            byPipe.put(pipe, new Binding(key, false));
            bus.publish(new ClientOpened(new ClientRef(key, pipe), at));
        }
    }

    /**
     * The host read the account of the client on {@code pipe}. Settles its key
     * and publishes {@link ClientIdentified} the first time, and again only if
     * the client turns out to be on a different account. Reading the same
     * account again changes nothing.
     *
     * @param accountUuid the raw {@code account_uuid} the agent sent, or
     *                    {@code null}; a missing or placeholder UUID keys the
     *                    client by its pipe
     * @param name        the name the client shows, if any
     * @return whether the client's key was settled or changed by this call
     */
    public boolean identify(String pipe, String accountUuid, Optional<String> name, Instant at) {
        synchronized (lock) {
            Binding current = byPipe.get(pipe);
            ClientKey reported = ClientKey.of(accountUuid, pipe);
            if (current == null || (current.isIdentified() && isSameClient(current.key(), reported))) {
                return false;
            }
            ClientKey key = claim(reported, pipe);
            byPipe.put(pipe, new Binding(key, true));
            bus.publish(new ClientIdentified(new ClientRef(key, pipe), name, at));
            return true;
        }
    }

    /**
     * Forgets every pipe keyed as {@code key} and publishes {@link ClientForgotten}.
     * A pipe forgotten here is keyed by itself again if it is ever seen again.
     */
    public void forget(ClientKey key, Instant at) {
        synchronized (lock) {
            String lastPipe = ClientRef.NO_PIPE;
            var bindings = byPipe.entrySet().iterator();
            while (bindings.hasNext()) {
                var binding = bindings.next();
                if (binding.getValue().key().equals(key)) {
                    lastPipe = binding.getKey();
                    bindings.remove();
                }
            }
            bus.publish(new ClientForgotten(new ClientRef(key, lastPipe), at));
        }
    }

    private ClientKey keyOf(String pipe) {
        Binding binding = byPipe.get(pipe);
        return binding != null ? binding.key() : ClientKey.pipe(pipe);
    }

    /** Whether two keys name one client: the same account, or the same pipe. */
    private static boolean isSameClient(ClientKey held, ClientKey reported) {
        return switch (held) {
            case ClientKey.Account account -> reported.accountUuid().equals(Optional.of(account.uuid()));
            case ClientKey.Pipe pipe -> pipe.equals(reported);
        };
    }

    /**
     * The key a client reporting {@code reported} gets: a pipe key as is, an
     * account's first instance no live pipe holds. Dead pipes holding it fall
     * back to their own pipe keys.
     */
    private ClientKey claim(ClientKey reported, String pipe) {
        return switch (reported) {
            case ClientKey.Pipe own -> own;
            case ClientKey.Account account -> claimAccount(account.uuid(), pipe);
        };
    }

    private ClientKey claimAccount(String uuid, String pipe) {
        for (int instance = 1; ; instance++) {
            ClientKey candidate = new ClientKey.Account(uuid, instance);
            if (!isHeldLive(candidate, pipe)) {
                releaseDeadHolders(candidate, pipe);
                if (instance > 1) {
                    log.warn("Account {} is open on more than one client; '{}' is shown as {}",
                            uuid, pipe, candidate.value());
                }
                return candidate;
            }
        }
    }

    private boolean isHeldLive(ClientKey key, String claimant) {
        for (var binding : byPipe.entrySet()) {
            String holder = binding.getKey();
            if (!holder.equals(claimant) && binding.getValue().key().equals(key) && isLive.test(holder)) {
                return true;
            }
        }
        return false;
    }

    private void releaseDeadHolders(ClientKey key, String claimant) {
        byPipe.replaceAll((holder, binding) -> holder.equals(claimant) || !binding.key().equals(key)
                ? binding
                : new Binding(ClientKey.pipe(holder), true));
    }
}
