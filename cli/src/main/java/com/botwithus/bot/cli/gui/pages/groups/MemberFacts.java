package com.botwithus.bot.cli.gui.pages.groups;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * What the host knows about one account's client, for the Groups page: who it
 * is, where it is, and every script it has run, is running or is waiting to run.
 *
 * @param uuid    the account UUID
 * @param name    the name the client shows, else the last one it showed
 * @param world   the world it is in, else the last one it was seen in
 * @param scripts in the order the runtime lists them, queued starts last
 */
public record MemberFacts(String uuid, Optional<String> name, OptionalInt world, MemberLink link,
                          List<ScriptFact> scripts) {

    public MemberFacts {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(link, "link");
        scripts = List.copyOf(scripts);
    }

    /** An account the host has no client for: not seen since it started, or forgotten. */
    public static MemberFacts unknown(String uuid) {
        return new MemberFacts(uuid, Optional.empty(), OptionalInt.empty(), MemberLink.CLOSED, List.of());
    }

    public boolean isConnected() {
        return link.isConnected();
    }

    /**
     * The script a row shows first: what needs a look, then what runs, then what
     * waits, then what stopped; see {@link ScriptState}.
     */
    public Optional<ScriptFact> primary() {
        return scripts.stream().min(Comparator.comparingInt(fact -> fact.state().ordinal()));
    }

    /** The scripts whose thread is running, in runtime order: what a stop stops. */
    public List<ScriptFact> active() {
        return scripts.stream().filter(fact -> fact.state().isActive()).toList();
    }

    /** The scripts waiting for the client to be back. */
    public List<ScriptFact> queued() {
        return scripts.stream().filter(fact -> fact.state() == ScriptState.QUEUED).toList();
    }

    /** Whether {@code script} is running, stalled or not, on the client. */
    public boolean isRunning(String script) {
        return active().stream().anyMatch(fact -> fact.isNamed(script));
    }

    /** The member in one word; see {@link MemberHealth}. */
    public MemberHealth health() {
        return switch (link) {
            case CLOSED -> MemberHealth.CLOSED;
            case RECONNECTING, NOT_RESPONDING -> MemberHealth.RECONNECTING;
            case CONNECTED -> primary().map(fact -> healthOf(fact.state())).orElse(MemberHealth.IDLE);
        };
    }

    private static MemberHealth healthOf(ScriptState state) {
        return switch (state) {
            case CRASHED, CUT_OFF -> MemberHealth.CRASHED;
            case STALLED -> MemberHealth.STALLED;
            case RUNNING -> MemberHealth.RUNNING;
            case QUEUED, STOPPED -> MemberHealth.IDLE;
        };
    }
}
