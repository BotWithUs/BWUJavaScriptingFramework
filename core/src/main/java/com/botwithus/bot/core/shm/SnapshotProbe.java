package com.botwithus.bot.core.shm;

import com.botwithus.bot.api.event.ActionExecutedEvent;
import com.botwithus.bot.api.event.BreakEndedEvent;
import com.botwithus.bot.api.event.BreakStartedEvent;
import com.botwithus.bot.api.event.ChatMessageEvent;
import com.botwithus.bot.api.event.ConnectionLostEvent;
import com.botwithus.bot.api.event.GameEvent;
import com.botwithus.bot.api.event.ReconnectStateChangedEvent;
import com.botwithus.bot.api.event.ScriptLoadFailedEvent;
import com.botwithus.bot.api.event.KeyInputEvent;
import com.botwithus.bot.api.event.LoginStateChangeEvent;
import com.botwithus.bot.api.event.ScriptCrashedEvent;
import com.botwithus.bot.api.event.TickEvent;
import com.botwithus.bot.api.event.VarChangeEvent;
import com.botwithus.bot.api.event.VarbitChangeEvent;
import com.botwithus.bot.api.event.VarcChangeEvent;
import com.botwithus.bot.api.event.WalkArrivedEvent;
import com.botwithus.bot.api.event.WalkCancelledEvent;
import com.botwithus.bot.api.event.WalkFailedEvent;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.core.impl.snapshot.GameSnapshotImpl;

/**
 * Standalone smoke-test entry point for the shared-memory bridge. Exercises
 * the public {@code api.snapshot} surface against a live injected DLL —
 * confirms the producer side and the public consumer types agree on the
 * wire layout.
 *
 * <pre>{@code
 *   java -p <classpath> -m com.botwithus.bot.core/com.botwithus.bot.core.shm.SnapshotProbe <pid>
 * }</pre>
 *
 * <p>Prints a one-line snapshot summary every second and streams events as
 * they arrive. Ctrl+C to stop.</p>
 */
public final class SnapshotProbe {

    /** How often the probe prints a snapshot summary (ms). */
    private static final long PRINT_INTERVAL_MS = 1000L;
    /** How long the probe sleeps between event-ring polls (ms). */
    private static final long POLL_INTERVAL_MS = 50L;
    /** Divisor used to convert {@code currentTimeMillis} to a printable seconds-since-epoch column. */
    private static final long MS_PER_SECOND = 1000L;

    private SnapshotProbe() {}

    public static void main(String[] args) throws InterruptedException {
        long pid;
        if (args.length >= 1) {
            pid = Long.parseLong(args[0]);
        } else {
            // No pid given — discover via pipe scan. Pipe + snapshot are
            // created together by the DLL, so any visible pipe pairs with
            // a bindable snapshot mapping.
            var discovered = SharedRegion.discoverPids();
            if (discovered.isEmpty()) {
                System.err.println("No BotWithUs_<pid> pipes found. Is the DLL injected?");
                System.err.println("usage: SnapshotProbe [<pid>]");
                System.exit(2);
                return;
            }
            if (discovered.size() > 1) {
                System.err.println("Multiple games detected: " + discovered + " — picking first.");
            }
            pid = discovered.getFirst();
        }

        try (SharedRegion region = SharedRegion.open(pid)) {
            System.out.println("Bound to pid=" + pid + " (frontIdx=" + region.frontIdx() + ")");

            EventRingReader events = new EventRingReader(region, /*fromHead*/ true);
            long lastSnapshotPrint = 0;
            long lastTick = -1;

            while (!Thread.currentThread().isInterrupted()) {
                events.poll(SnapshotProbe::printEvent);

                long now = System.currentTimeMillis();
                if (now - lastSnapshotPrint >= PRINT_INTERVAL_MS) {
                    GameSnapshot snap = new GameSnapshotImpl(region.snapshot());
                    if (snap.tickId() != lastTick) {
                        LocalPlayer self = snap.self();
                        System.out.printf(
                                "[t=%d] tick=%d state=%d ownIdx=%d npcs=%d players=%d invs=%d skills=%d (drops: writer=%d reader=%d)%n",
                                now / MS_PER_SECOND,
                                snap.tickId(),
                                snap.gameState(),
                                snap.ownIndex(),
                                snap.npcs().count(),
                                snap.players().count(),
                                snap.inventories().count(),
                                self == null ? 0 : self.skills().size(),
                                events.writerSideDroppedCount(),
                                events.droppedCount());
                        lastTick = snap.tickId();
                    }
                    lastSnapshotPrint = now;
                }

                Thread.sleep(POLL_INTERVAL_MS);
            }
        }
    }

    private static void printEvent(GameEvent ev) {
        System.out.println("evt: " + ev.getClass().getSimpleName() + " " + summarise(ev));
    }

    /** Small switch to render an inline event summary; better than relying on toString() since the event types don't override it. */
    private static String summarise(GameEvent ev) {
        return switch (ev) {
            case LoginStateChangeEvent e ->
                    "old=" + e.oldState() + " new=" + e.newState();
            case TickEvent e ->
                    "tick=" + e.tick();
            case VarChangeEvent e ->
                    "id=" + e.varId() + " " + e.oldValue() + "->" + e.newValue();
            case VarcChangeEvent e ->
                    "id=" + e.varId() + " " + e.oldValue() + "->" + e.newValue();
            case VarbitChangeEvent e ->
                    "id=" + e.varId() + " " + e.oldValue() + "->" + e.newValue();
            case ChatMessageEvent e ->
                    "type=" + e.message().messageType()
                            + " from=" + e.message().playerName()
                            + " text='" + e.message().text() + "'";
            case KeyInputEvent e ->
                    "key=" + e.key() + " alt=" + e.alt() + " ctrl=" + e.ctrl() + " shift=" + e.shift();
            case ActionExecutedEvent e ->
                    "action=" + e.actionId();
            case BreakStartedEvent e ->
                    "duration=" + e.durationSeconds() + "s fatigue=" + e.fatigue() + " risk=" + e.risk();
            case BreakEndedEvent ignored ->
                    "";
            case WalkArrivedEvent e ->
                    "tile=(" + e.targetX() + "," + e.targetY() + ")";
            case WalkCancelledEvent e ->
                    "tile=(" + e.targetX() + "," + e.targetY() + ")";
            case WalkFailedEvent e ->
                    "tile=(" + e.targetX() + "," + e.targetY() + ")";
            case ScriptCrashedEvent e ->
                    "script=" + e.scriptName() + " phase=" + e.crash().phase()
                            + " cause=" + e.crash().cause().getClass().getSimpleName();
            case ConnectionLostEvent e ->
                    "conn=" + e.connectionName()
                            + " cause=" + (e.cause() != null ? e.cause().getClass().getSimpleName() : "<none>");
            case ReconnectStateChangedEvent e ->
                    "conn=" + e.connectionName() + " state=" + e.state().getClass().getSimpleName();
            case ScriptLoadFailedEvent e ->
                    "jar=" + e.jar().getFileName()
                            + " cause=" + e.cause().getClass().getSimpleName();
        };
    }
}
