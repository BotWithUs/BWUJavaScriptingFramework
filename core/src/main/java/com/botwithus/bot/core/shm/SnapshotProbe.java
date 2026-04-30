package com.botwithus.bot.core.shm;

import com.botwithus.bot.api.event.ActionExecutedEvent;
import com.botwithus.bot.api.event.BreakEndedEvent;
import com.botwithus.bot.api.event.BreakStartedEvent;
import com.botwithus.bot.api.event.ChatMessageEvent;
import com.botwithus.bot.api.event.GameEvent;
import com.botwithus.bot.api.event.KeyInputEvent;
import com.botwithus.bot.api.event.LoginStateChangeEvent;
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
                if (now - lastSnapshotPrint >= 1000) {
                    GameSnapshot snap = new GameSnapshotImpl(region.snapshot());
                    if (snap.tickId() != lastTick) {
                        LocalPlayer self = snap.self();
                        System.out.printf(
                                "[t=%d] tick=%d state=%d ownIdx=%d npcs=%d players=%d invs=%d skills=%d (drops: writer=%d reader=%d)%n",
                                now / 1000,
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

                Thread.sleep(50);
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
                    "old=" + e.getOldState() + " new=" + e.getNewState();
            case TickEvent e ->
                    "tick=" + e.getTick();
            case VarChangeEvent e ->
                    "id=" + e.getVarId() + " " + e.getOldValue() + "->" + e.getNewValue();
            case VarcChangeEvent e ->
                    "id=" + e.getVarId() + " " + e.getOldValue() + "->" + e.getNewValue();
            case VarbitChangeEvent e ->
                    "id=" + e.getVarId() + " " + e.getOldValue() + "->" + e.getNewValue();
            case ChatMessageEvent e ->
                    "type=" + e.getMessage().messageType()
                            + " from=" + e.getMessage().playerName()
                            + " text='" + e.getMessage().text() + "'";
            case KeyInputEvent e ->
                    "key=" + e.getKey() + " alt=" + e.isAlt() + " ctrl=" + e.isCtrl() + " shift=" + e.isShift();
            case ActionExecutedEvent e ->
                    "action=" + e.getActionId();
            case BreakStartedEvent e ->
                    "duration=" + e.getDurationSeconds() + "s fatigue=" + e.getFatigue() + " risk=" + e.getRisk();
            case BreakEndedEvent ignored ->
                    "";
            case WalkArrivedEvent e ->
                    "tile=(" + e.getTargetX() + "," + e.getTargetY() + ")";
            case WalkCancelledEvent e ->
                    "tile=(" + e.getTargetX() + "," + e.getTargetY() + ")";
            case WalkFailedEvent e ->
                    "tile=(" + e.getTargetX() + "," + e.getTargetY() + ")";
            default -> "(no summary)";
        };
    }
}
