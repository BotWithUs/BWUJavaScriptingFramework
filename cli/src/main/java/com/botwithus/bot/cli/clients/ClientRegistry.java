package com.botwithus.bot.cli.clients;

import com.botwithus.bot.api.runtime.ReconnectState;
import com.botwithus.bot.cli.GameStatus;
import com.botwithus.bot.cli.clients.ClientLifecycle.Closed;
import com.botwithus.bot.cli.clients.ClientLifecycle.Identifying;
import com.botwithus.bot.cli.clients.ClientLifecycle.NotResponding;
import com.botwithus.bot.cli.clients.ClientLifecycle.Resuming;
import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.events.ClientRef;
import com.botwithus.bot.cli.events.ConnectionHistory;
import com.botwithus.bot.cli.events.HostEvent;
import com.botwithus.bot.cli.events.HostEvent.ClientClosed;
import com.botwithus.bot.cli.events.HostEvent.ClientForgotten;
import com.botwithus.bot.cli.events.HostEvent.ClientIdentified;
import com.botwithus.bot.cli.events.HostEvent.ClientOpened;
import com.botwithus.bot.cli.events.HostEvent.ClientResumed;
import com.botwithus.bot.cli.events.HostEvent.ConnectionLost;
import com.botwithus.bot.cli.events.HostEvent.ManagementAction;
import com.botwithus.bot.cli.events.HostEvent.ManagementScriptCrashed;
import com.botwithus.bot.cli.events.HostEvent.ReconnectStateChanged;
import com.botwithus.bot.cli.events.HostEvent.ScriptCrashed;
import com.botwithus.bot.cli.events.HostEvent.ScriptLoadFailed;
import com.botwithus.bot.cli.events.HostEvent.ScriptStalled;
import com.botwithus.bot.cli.events.HostEvent.ScriptStarted;
import com.botwithus.bot.cli.events.HostEvent.ScriptStopped;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Every client the host knows, live or remembered, keyed by {@link ClientKey}:
 * one entry per account, however many times its game restarts.
 *
 * <p>Subscribe it to the host event bus. It follows each client from the pipe
 * opening ({@link ClientLifecycle.Identifying}) through identification, drops and
 * retries to closing, and moves a client's entry onto its account's key when the
 * account is read. A client on a real account that closes is kept, and saved to
 * the {@link ClientStore}, so its entry is there after a host restart and is
 * picked up again when the account identifies on a new pipe. A client keyed by
 * its pipe is kept only until the host exits, and never saved.</p>
 *
 * <p>Thread-safe. {@link #clients()} and {@link #get} return immutable
 * snapshots, safe to read on any thread, that combine what the events said with
 * a fresh read of each open connection. Saving runs on the save executor, never
 * on the thread delivering events.</p>
 */
public final class ClientRegistry implements Consumer<HostEvent> {

    private static final Logger log = LoggerFactory.getLogger(ClientRegistry.class);

    /** How long a client that came back on a new pipe shows as {@link Resuming}. */
    public static final Duration RESUMING_FOR = Duration.ofSeconds(5);

    /** Where a client is: on a pipe, or gone. */
    private sealed interface Presence {

        /** On {@code pipe}, which the host still has a connection for. */
        record Live(String pipe, LiveClient link, boolean isIdentified,
                    Optional<ReconnectState> linkState, Optional<Resume> resume) implements Presence {

            Live withLinkState(ReconnectState state) {
                return new Live(pipe, link, isIdentified, Optional.of(state), resume);
            }

            Live withResume(Resume resumed) {
                return new Live(pipe, link, isIdentified, linkState, Optional.of(resumed));
            }
        }

        /** Closed; {@code lastPipe} is empty when remembered from an earlier run. */
        record Gone(Optional<String> lastPipe, Instant since) implements Presence { }
    }

    /** The client came back on a new pipe at {@code since}. */
    private record Resume(Optional<String> previousPipe, Instant since) { }

    private record Entry(ClientKey key, Presence presence, Optional<String> name, OptionalInt lastWorld) {

        Entry with(Presence next) {
            return new Entry(key, next, name, lastWorld);
        }
    }

    /** An entry whose client is live on a given pipe. */
    private record Holder(Entry entry, Presence.Live live) { }

    private final ClientStore store;
    private final Function<String, Optional<LiveClient>> links;
    private final ConnectionHistory history;
    private final Consumer<HostEvent> publisher;
    private final Clock clock;
    private final Executor saveExecutor;
    private final Object lock = new Object();
    /** In the order clients were first seen. Guarded by {@link #lock}. */
    private final Map<ClientKey, Entry> entries = new LinkedHashMap<>();
    private final Object saveLock = new Object();
    private final AtomicBoolean saveQueued = new AtomicBoolean();

    /**
     * @param store        where remembered clients are saved
     * @param links        the connection on a pipe, while the host has one
     * @param history      the per-client event history {@link #history} reads
     * @param publisher    where {@link ClientResumed} is published
     * @param saveExecutor runs saves, which write to disk
     */
    public ClientRegistry(ClientStore store, Function<String, Optional<LiveClient>> links,
                          ConnectionHistory history, Consumer<HostEvent> publisher,
                          Clock clock, Executor saveExecutor) {
        this.store = store;
        this.links = links;
        this.history = history;
        this.publisher = publisher;
        this.clock = clock;
        this.saveExecutor = saveExecutor;
    }

    /**
     * Adds the clients saved by an earlier run, as closed, unless one is already
     * here. A store that cannot be read is logged and skipped.
     */
    public void load() {
        List<RememberedClient> saved;
        try {
            saved = store.load();
        } catch (IOException e) {
            log.warn("Could not read the remembered clients: {}", e.toString());
            return;
        }
        synchronized (lock) {
            for (RememberedClient client : saved) {
                ClientKey key = ClientKey.account(client.accountUuid());
                Presence gone = new Presence.Gone(Optional.empty(), client.lastSeenAt());
                entries.putIfAbsent(key, new Entry(key, gone, client.name(), client.lastWorld()));
            }
        }
    }

    /** Every client, in the order each was first seen. */
    public List<ClientRecord> clients() {
        List<Entry> snapshot;
        synchronized (lock) {
            snapshot = List.copyOf(entries.values());
        }
        Instant now = clock.instant();
        return snapshot.stream().map(entry -> recordOf(entry, now)).toList();
    }

    /** The client under {@code key}, if the registry has it. */
    public Optional<ClientRecord> get(ClientKey key) {
        Entry entry;
        synchronized (lock) {
            entry = entries.get(key);
        }
        return Optional.ofNullable(entry).map(found -> recordOf(found, clock.instant()));
    }

    /** Whether the registry has a client under {@code key}, live or not. */
    public boolean contains(ClientKey key) {
        synchronized (lock) {
            return entries.containsKey(key);
        }
    }

    /** The client's recorded events, oldest first; see {@link ConnectionHistory}. */
    public List<HostEvent> history(ClientKey key) {
        return history.forClient(key);
    }

    /** Saves the remembered clients now, on the calling thread. For shutdown. */
    public void saveNow() {
        synchronized (saveLock) {
            try {
                store.save(remembered(clock.instant()));
            } catch (IOException e) {
                log.warn("Could not save the remembered clients: {}", e.toString());
            }
        }
    }

    @Override
    public void accept(HostEvent event) {
        boolean isRememberedChange;
        synchronized (lock) {
            isRememberedChange = apply(event);
        }
        if (isRememberedChange) {
            requestSave();
        }
    }

    /** @return whether a remembered client changed, so the store needs saving */
    private boolean apply(HostEvent event) {
        return switch (event) {
            case ClientOpened opened -> onOpened(opened.client());
            case ClientIdentified identified -> onIdentified(identified);
            case ReconnectStateChanged changed -> onLinkState(changed.client(), changed.state());
            case ClientClosed closed -> onClosed(closed.client(), closed.at());
            case ClientForgotten forgotten -> onForgotten(forgotten.client().key());
            case ClientResumed _, ConnectionLost _, ScriptStarted _, ScriptStopped _, ScriptStalled _,
                 ScriptCrashed _, ScriptLoadFailed _, ManagementAction _,
                 ManagementScriptCrashed _ -> false;
        };
    }

    private boolean onOpened(ClientRef ref) {
        Optional<LiveClient> link = links.apply(ref.pipe());
        if (link.isEmpty()) {
            return false;
        }
        Presence live = new Presence.Live(ref.pipe(), link.get(), false, Optional.empty(), Optional.empty());
        Entry existing = entries.get(ref.key());
        entries.put(ref.key(), existing != null
                ? existing.with(live)
                : new Entry(ref.key(), live, Optional.empty(), OptionalInt.empty()));
        return false;
    }

    /**
     * Settles the pipe's client under the identified key. If that key already
     * has an entry that is not this pipe's, the client came back: it takes that
     * entry over and shows as resuming.
     */
    private boolean onIdentified(ClientIdentified event) {
        ClientRef ref = event.client();
        Optional<Holder> from = holderOn(ref.pipe());
        Optional<LiveClient> link = from.map(holder -> holder.live().link()).or(() -> links.apply(ref.pipe()));
        if (link.isEmpty()) {
            return false;
        }
        Presence.Live identified = new Presence.Live(ref.pipe(), link.get(), true,
                from.flatMap(holder -> holder.live().linkState()), Optional.empty());
        Entry target = entries.get(ref.key());
        boolean isOwnEntry = from.map(holder -> holder.entry().key().equals(ref.key())).orElse(false);
        if (target == null || isOwnEntry) {
            settle(from.map(Holder::entry), ref.key(), identified, event.name());
        } else {
            resume(from.map(Holder::entry), target, identified, event);
        }
        return ref.key().isRemembered();
    }

    private void settle(Optional<Entry> from, ClientKey key, Presence.Live live, Optional<String> name) {
        Entry settled = new Entry(key, live,
                name.or(() -> from.flatMap(Entry::name)),
                from.map(Entry::lastWorld).orElse(OptionalInt.empty()));
        replace(from.map(Entry::key).orElse(key), settled);
    }

    private void resume(Optional<Entry> from, Entry target, Presence.Live live, ClientIdentified event) {
        Optional<String> previousPipe = lastPipeOf(target.presence());
        Presence resumed = live.withResume(new Resume(previousPipe, event.at()));
        from.ifPresent(entry -> entries.remove(entry.key()));
        entries.put(target.key(), new Entry(target.key(), resumed, event.name().or(target::name),
                target.lastWorld()));
        publisher.accept(new ClientResumed(event.client(), previousPipe, event.at()));
    }

    private boolean onLinkState(ClientRef ref, ReconnectState state) {
        holderOn(ref.pipe())
                .filter(holder -> holder.entry().key().equals(ref.key()))
                .ifPresent(holder -> entries.put(ref.key(),
                        holder.entry().with(holder.live().withLinkState(state))));
        return false;
    }

    /** Keeps the client, closed, with the name and world it last showed. */
    private boolean onClosed(ClientRef ref, Instant at) {
        Optional<Holder> holder = holderOn(ref.pipe())
                .filter(found -> found.entry().key().equals(ref.key()));
        if (holder.isEmpty()) {
            return false;
        }
        Entry entry = holder.get().entry();
        LiveClient link = holder.get().live().link();
        OptionalInt world = link.gameStatus().world();
        entries.put(ref.key(), new Entry(ref.key(), new Presence.Gone(Optional.of(ref.pipe()), at),
                link.displayName().or(entry::name), world.isPresent() ? world : entry.lastWorld()));
        return ref.key().isRemembered();
    }

    private boolean onForgotten(ClientKey key) {
        return entries.remove(key) != null && key.isRemembered();
    }

    /** The entry whose client is live on {@code pipe}, if any. */
    private Optional<Holder> holderOn(String pipe) {
        for (Entry entry : entries.values()) {
            switch (entry.presence()) {
                case Presence.Live live when live.pipe().equals(pipe) -> {
                    return Optional.of(new Holder(entry, live));
                }
                case Presence.Live _, Presence.Gone _ -> { }
            }
        }
        return Optional.empty();
    }

    /** Puts {@code entry} where {@code oldKey} was, or last if it was not there. */
    private void replace(ClientKey oldKey, Entry entry) {
        if (oldKey.equals(entry.key()) || !entries.containsKey(oldKey)) {
            entries.put(entry.key(), entry);
            return;
        }
        Map<ClientKey, Entry> rebuilt = new LinkedHashMap<>();
        entries.forEach((key, existing) -> {
            if (key.equals(oldKey)) {
                rebuilt.put(entry.key(), entry);
            } else if (!key.equals(entry.key())) {
                rebuilt.put(key, existing);
            }
        });
        entries.clear();
        entries.putAll(rebuilt);
    }

    private static Optional<String> lastPipeOf(Presence presence) {
        return switch (presence) {
            case Presence.Live live -> Optional.of(live.pipe());
            case Presence.Gone gone -> gone.lastPipe();
        };
    }

    // ── Snapshots ──────────────────────────────────────────────────────────

    private static ClientRecord recordOf(Entry entry, Instant now) {
        return switch (entry.presence()) {
            case Presence.Live live -> liveRecord(entry, live, now);
            case Presence.Gone gone -> closedRecord(entry, new Closed(gone.since()), Optional.empty());
        };
    }

    private static ClientRecord liveRecord(Entry entry, Presence.Live live, Instant now) {
        ClientLifecycle lifecycle = lifecycleOf(live, now);
        return switch (lifecycle) {
            case Closed _ -> closedRecord(entry, lifecycle, Optional.of(live.pipe()));
            case Identifying _, ClientLifecycle.Connected _, Resuming _, NotResponding _ ->
                    openRecord(entry, live, lifecycle);
        };
    }

    private static ClientRecord openRecord(Entry entry, Presence.Live live, ClientLifecycle lifecycle) {
        LiveClient link = live.link();
        GameStatus status = link.gameStatus();
        OptionalInt world = status.world().isPresent() ? status.world() : entry.lastWorld();
        return new ClientRecord(entry.key(), lifecycle, Optional.of(live.pipe()),
                link.displayName().or(entry::name), status, world, Optional.of(link.connectedAt()));
    }

    private static ClientRecord closedRecord(Entry entry, ClientLifecycle closed, Optional<String> pipe) {
        return new ClientRecord(entry.key(), closed, pipe, entry.name(), GameStatus.UNKNOWN,
                entry.lastWorld(), Optional.empty());
    }

    private static ClientLifecycle lifecycleOf(Presence.Live live, Instant now) {
        Optional<ClientLifecycle> dropped = live.linkState().flatMap(state -> droppedLifecycle(state, live.link()));
        if (dropped.isPresent()) {
            return dropped.get();
        }
        if (!live.isIdentified()) {
            return new Identifying();
        }
        return live.resume()
                .filter(resume -> now.isBefore(resume.since().plus(RESUMING_FOR)))
                .<ClientLifecycle>map(resume -> new Resuming(resume.previousPipe(), resume.since()))
                .orElseGet(ClientLifecycle.Connected::new);
    }

    /** What a reconnect state says about the client; empty when its pipe is up. */
    private static Optional<ClientLifecycle> droppedLifecycle(ReconnectState state, LiveClient link) {
        Instant at = Instant.ofEpochMilli(state.timestamp());
        return switch (state) {
            case ReconnectState.Connected _ -> Optional.empty();
            case ReconnectState.Disconnected _ ->
                    Optional.of(new NotResponding(0, Optional.of(Duration.ZERO), link.maxAttempts(), at));
            case ReconnectState.Reconnecting retrying -> Optional.of(new NotResponding(retrying.attempt(),
                    Optional.of(Duration.ofMillis(retrying.nextDelayMs())), link.maxAttempts(), at));
            case ReconnectState.GivingUp gaveUp -> Optional.of(link.isClientGone()
                    ? new Closed(at)
                    : new NotResponding(gaveUp.attempts(), Optional.empty(), link.maxAttempts(), at));
        };
    }

    // ── Saving ─────────────────────────────────────────────────────────────

    private void requestSave() {
        if (!saveQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            saveExecutor.execute(() -> {
                saveQueued.set(false);
                saveNow();
            });
        } catch (RejectedExecutionException e) {
            saveQueued.set(false);
            log.warn("Remembered clients not saved: {}", e.toString());
        }
    }

    /** The clients to save: every remembered one, as it is now. */
    private List<RememberedClient> remembered(Instant now) {
        List<RememberedClient> remembered = new ArrayList<>();
        synchronized (lock) {
            for (Entry entry : entries.values()) {
                if (entry.key().isRemembered()) {
                    remembered.add(rememberedOf(entry, now));
                }
            }
        }
        return remembered;
    }

    private static RememberedClient rememberedOf(Entry entry, Instant now) {
        String uuid = entry.key().accountUuid().orElseThrow();
        return switch (entry.presence()) {
            case Presence.Live live -> {
                OptionalInt world = live.link().gameStatus().world();
                yield new RememberedClient(uuid, live.link().displayName().or(entry::name),
                        world.isPresent() ? world : entry.lastWorld(), now);
            }
            case Presence.Gone gone -> new RememberedClient(uuid, entry.name(), entry.lastWorld(), gone.since());
        };
    }
}
