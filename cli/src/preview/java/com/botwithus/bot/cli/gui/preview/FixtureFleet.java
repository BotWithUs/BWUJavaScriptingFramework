package com.botwithus.bot.cli.gui.preview;

import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.gui.usermode.board.ClientState;
import com.botwithus.bot.cli.gui.usermode.board.ClientView;
import com.botwithus.bot.cli.gui.usermode.board.ResumeSwitch;
import com.botwithus.bot.cli.gui.usermode.board.ScriptInfo;
import com.botwithus.bot.cli.gui.usermode.board.ScriptRow;
import com.botwithus.bot.cli.gui.usermode.board.ScriptState;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Random;

/**
 * DEV ONLY. The prototype's sample clients as card views: the "every state"
 * fleet, the thirteen-client fleet, and the four stills of a game client
 * restarting and coming back by its account. Names, UUIDs, pipes and timings
 * are made-up sample data.
 */
final class FixtureFleet {

    private static final long MS = 1_000_000L;
    private static final int LANE = 24;
    private static final long SEED = 7L;
    private static final double JITTER_LOW = 0.8;
    private static final double JITTER_SPAN = 0.4;
    private static final double SPIKE = 2.6;
    private static final int SPIKE_SLOT = 19;
    private static final ResumeSwitch ON = new ResumeSwitch.Available(true);
    private static final ResumeSwitch OFF = new ResumeSwitch.Available(false);

    /** The management script whose robot link two cards show: it targets their scripts directly. */
    private static final String MANAGER = "Break Scheduler";
    static final String OAKHEART_UUID = "3f9a1c2e-58b0-4d7a-9e21-6c0f4b7d2a18";
    static final String OAKHEART_PIPE = "BotWithUs_14208";
    static final String QUILLON_UUID = "5c20aa91-7e44-4b18-b3c0-2f8d61e9a705";
    static final String HOLLOWMERE_PIPE = "BotWithUs_10344";
    private static final String RESTART_PIPE = "BotWithUs_19230";

    /** The four stills of the prototype's "Client restart (live)" scenario. */
    enum RestartStep { NOT_RESPONDING, CLOSED_AND_NEW_PIPE, RESUMING, RUNNING_AGAIN }

    private final Random rng = new Random(SEED);

    private FixtureFleet() {}

    /** Seven clients, one in each client state and each script state the prototype shows. */
    static List<ClientView> everyState() {
        return new FixtureFleet().base();
    }

    /** The seven, plus six more: two scripts on one client, a cut-off script and a lane spike. */
    static List<ClientView> thirteen() {
        FixtureFleet fleet = new FixtureFleet();
        List<ClientView> all = new ArrayList<>(fleet.base());
        all.addAll(fleet.more());
        return List.copyOf(all);
    }

    /** A development client with no account UUID, beside a normal one. */
    static List<ClientView> devClient() {
        FixtureFleet fleet = new FixtureFleet();
        ClientView dev = new ClientView(ClientKey.pipe("BotWithUs_20480"), Optional.of("BotWithUs_20480"),
                Optional.of("Dev Tester"), OptionalInt.of(1),
                connected(Duration.ofMinutes(12), 1.9),
                List.of(fleet.running(FixtureBoard.EXAMPLE, Duration.ofMinutes(11).plusSeconds(3), 64, -1)),
                new ResumeSwitch.Unavailable("No account to resume"));
        return List.of(fleet.base().getFirst(), dev);
    }

    /** One still of Oakheart's game client restarting, beside Quillon. */
    static List<ClientView> restart(RestartStep step) {
        FixtureFleet fleet = new FixtureFleet();
        ClientView quillon = fleet.base().get(2);
        ScriptRow woodcutting = ScriptRow.idle(FixtureBoard.WOODCUTTING, new ScriptState.Waiting());
        return switch (step) {
            case NOT_RESPONDING -> List.of(account("Oakheart", OAKHEART_UUID, OAKHEART_PIPE, 84,
                    notResponding(Duration.ofSeconds(5), 3, Duration.ofSeconds(2)), List.of(woodcutting), ON),
                    quillon);
            case CLOSED_AND_NEW_PIPE -> List.of(closed("Oakheart", OAKHEART_UUID, 84, Duration.ofSeconds(20),
                    List.of(woodcutting), ON), quillon, identifying(RESTART_PIPE));
            case RESUMING -> List.of(account("Oakheart", OAKHEART_UUID, RESTART_PIPE, 84,
                    new ClientState.Resuming(), List.of(woodcutting), ON), quillon);
            case RUNNING_AGAIN -> List.of(account("Oakheart", OAKHEART_UUID, RESTART_PIPE, 84,
                    connected(Duration.ofSeconds(9), 2.9),
                    List.of(fleet.running(FixtureBoard.WOODCUTTING, Duration.ofSeconds(1), 142, -1)), ON), quillon);
        };
    }

    private List<ClientView> base() {
        return List.of(
                account("Oakheart", OAKHEART_UUID, OAKHEART_PIPE, 84, connected(Duration.ofMinutes(134), 2.8),
                        List.of(running(FixtureBoard.WOODCUTTING, Duration.ofSeconds(41 * 60 + 7), 142, -1)
                                .withManagedBy(Optional.of(MANAGER))), ON),
                account("Fernmoss", "b71d09e4-2c61-4f3e-8a57-1d9e0c4b6f33", "BotWithUs_9932", 2,
                        connected(Duration.ofMinutes(362), 3.4),
                        List.of(row(FixtureBoard.DIVINATION, new ScriptState.Stalled(Duration.ofSeconds(38)))), ON),
                account("Quillon", QUILLON_UUID, "BotWithUs_11820", 117, connected(Duration.ofMinutes(3), 2.1),
                        List.of(), OFF),
                identifying("BotWithUs_7716"),
                account("Hollowmere", "e4410b7a-9d25-4c6b-a1f8-73e02b5d9c46", HOLLOWMERE_PIPE, 84,
                        notResponding(Duration.ofSeconds(42), 4, Duration.ofSeconds(8)),
                        List.of(row(FixtureBoard.WOODCUTTING, new ScriptState.Waiting())), ON),
                closed("Brackenridge", "0a6d2f58-c3e1-47b9-9f04-5e8b17a2d6c9", 44, Duration.ofMinutes(3),
                        List.of(row(FixtureBoard.DIVINATION, new ScriptState.Waiting())), ON),
                account("Tamsin Vale", "90ce3f15-4b87-4e2a-8d6c-1a5f09b3e724", "BotWithUs_15002", 31,
                        connected(Duration.ofMinutes(100), 2.6),
                        List.of(row(FixtureBoard.COOKS, new ScriptState.Crashed("NullPointerException in onLoop()",
                                LocalTime.of(14, 2)))), ON));
    }

    private List<ClientView> more() {
        return List.of(
                account("Wrenfield", "6b1e8c04-f2a7-4d95-b6e3-08c4a1d7f250", "BotWithUs_16640", 58,
                        connected(Duration.ofMinutes(231), 3.0),
                        List.of(running(FixtureBoard.FLETCHER, Duration.ofSeconds(2 * 3600 + 2 * 60 + 44), 118, -1)),
                        ON),
                account("Ashgrove", "c93f5a27-81d0-4e6b-9c42-b7e15f0a38d1", "BotWithUs_17012", 84,
                        connected(Duration.ofMinutes(26), 4.9),
                        List.of(running(FixtureBoard.GHOST, Duration.ofSeconds(12 * 60 + 40), 211, SPIKE_SLOT)), ON),
                closed("Mirelock", "2d7b4e91-0a36-4f8c-a5d2-9e61c3b08f47", 102, Duration.ofMinutes(12),
                        List.of(row(FixtureBoard.WOODCUTTING, new ScriptState.Waiting())), OFF),
                account("Sableton", "f40a6c3d-9b17-4e52-8f0e-4d2c7a91b563", "BotWithUs_17720", 44,
                        connected(Duration.ofMinutes(51), 2.4), List.of(), OFF),
                account("Kestrel Moor", "81c5e0b2-6d4f-4a37-b9e8-3f0a2c7d5e16", "BotWithUs_18104", 2,
                        connected(Duration.ofMinutes(258), 2.2),
                        List.of(running(FixtureBoard.FLAG, Duration.ofSeconds(48), 74, -1)
                                        .withManagedBy(Optional.of(MANAGER)),
                                row(FixtureBoard.PROBE, new ScriptState.Stopped())), ON),
                account("Duskwater", "4e92d71a-3c08-4b6f-a2d5-e7b1f9c04a38", "BotWithUs_18466", 117,
                        connected(Duration.ofMinutes(309), 3.1),
                        List.of(row(FixtureBoard.WOODCUTTING, new ScriptState.CutOff())), ON));
    }

    // ── Builders ───────────────────────────────────────────────────────────

    private static ClientView account(String name, String uuid, String pipe, int world, ClientState state,
                                      List<ScriptRow> rows, ResumeSwitch resume) {
        return new ClientView(ClientKey.account(uuid), Optional.of(pipe), Optional.of(name), OptionalInt.of(world),
                state, rows, resume);
    }

    private static ClientView closed(String name, String uuid, int world, Duration ago, List<ScriptRow> rows,
                                     ResumeSwitch resume) {
        return new ClientView(ClientKey.account(uuid), Optional.empty(), Optional.of(name), OptionalInt.of(world),
                new ClientState.Closed(ago), rows, resume);
    }

    private static ClientView identifying(String pipe) {
        return new ClientView(ClientKey.pipe(pipe), Optional.of(pipe), Optional.empty(), OptionalInt.empty(),
                new ClientState.Identifying(), List.of(), new ResumeSwitch.Unavailable("Waiting for account info"));
    }

    private static ClientState connected(Duration online, double rpcMs) {
        return new ClientState.Connected(online, OptionalDouble.of(rpcMs));
    }

    private static ClientState notResponding(Duration silent, int attempt, Duration nextIn) {
        return new ClientState.NotResponding(silent, attempt, OptionalInt.empty(), Optional.of(nextIn));
    }

    private static ScriptRow row(ScriptInfo script, ScriptState state) {
        return ScriptRow.idle(script, state);
    }

    /** A running row whose last 24 loops jitter ±20% round {@code avgMs}; one spike at {@code spikeAt}. */
    private ScriptRow running(ScriptInfo script, Duration runningFor, double avgMs, int spikeAt) {
        long[] lane = new long[LANE];
        for (int i = 0; i < LANE; i++) {
            lane[i] = Math.round(avgMs * (JITTER_LOW + rng.nextDouble() * JITTER_SPAN) * MS);
        }
        if (spikeAt >= 0) {
            lane[spikeAt] = Math.round(avgMs * SPIKE * MS);
        }
        return new ScriptRow(script, new ScriptState.Running(runningFor), lane, avgMs, Optional.empty());
    }
}
