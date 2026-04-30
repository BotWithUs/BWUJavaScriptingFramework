package com.botwithus.bot.core.shm;

import com.botwithus.bot.api.event.GameEvent;

/**
 * Standalone smoke-test entry point for the shared-memory bridge. Validates
 * end-to-end that the producer side (NXTLibrary DLL) and consumer side
 * (this package) agree on the wire layout, before we rip out the existing
 * pipe-events path in {@code EventDispatcher}.
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
        if (args.length < 1) {
            System.err.println("usage: SnapshotProbe <pid>");
            System.exit(2);
        }
        long pid = Long.parseLong(args[0]);

        try (SharedRegion region = SharedRegion.open(pid)) {
            System.out.println("Bound to pid=" + pid + " (frontIdx=" + region.frontIdx() + ")");

            EventRingReader events = new EventRingReader(region, /*fromHead*/ true);
            long lastSnapshotPrint = 0;
            long lastTick = -1;

            while (!Thread.currentThread().isInterrupted()) {
                events.poll(SnapshotProbe::printEvent);

                long now = System.currentTimeMillis();
                if (now - lastSnapshotPrint >= 1000) {
                    SnapshotView s = region.snapshot();
                    if (s.tickId() != lastTick) {
                        System.out.printf(
                                "[t=%d] tick=%d state=%d ownIdx=%d npcs=%d players=%d invs=%d skills=%d (drops: writer=%d reader=%d)%n",
                                now / 1000,
                                s.tickId(),
                                s.gameState(),
                                s.ownIndex(),
                                s.npcCount(),
                                s.playerCount(),
                                s.inventoryCount(),
                                s.self().skillCount(),
                                events.writerSideDroppedCount(),
                                events.droppedCount());
                        lastTick = s.tickId();
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
            case com.botwithus.bot.api.event.LoginStateChangeEvent e ->
                    "old=" + e.getOldState() + " new=" + e.getNewState();
            case com.botwithus.bot.api.event.TickEvent e ->
                    "tick=" + e.getTick();
            case com.botwithus.bot.api.event.VarChangeEvent e ->
                    "id=" + e.getVarId() + " " + e.getOldValue() + "->" + e.getNewValue();
            case com.botwithus.bot.api.event.VarbitChangeEvent e ->
                    "id=" + e.getVarId() + " " + e.getOldValue() + "->" + e.getNewValue();
            case com.botwithus.bot.api.event.ChatMessageEvent e ->
                    "type=" + e.getMessage().messageType()
                            + " from=" + e.getMessage().playerName()
                            + " text='" + e.getMessage().text() + "'";
            case com.botwithus.bot.api.event.KeyInputEvent e ->
                    "key=" + e.getKey() + " alt=" + e.isAlt() + " ctrl=" + e.isCtrl() + " shift=" + e.isShift();
            case com.botwithus.bot.api.event.ActionExecutedEvent e ->
                    "action=" + e.getActionId();
            case com.botwithus.bot.api.event.BreakStartedEvent e ->
                    "duration=" + e.getDurationSeconds() + "s fatigue=" + e.getFatigue() + " risk=" + e.getRisk();
            case com.botwithus.bot.api.event.BreakEndedEvent ignored ->
                    "";
            case com.botwithus.bot.api.event.WalkArrivedEvent e ->
                    "tile=(" + e.getTargetX() + "," + e.getTargetY() + ")";
            case com.botwithus.bot.api.event.WalkCancelledEvent e ->
                    "tile=(" + e.getTargetX() + "," + e.getTargetY() + ")";
            case com.botwithus.bot.api.event.WalkFailedEvent e ->
                    "tile=(" + e.getTargetX() + "," + e.getTargetY() + ")";
            default -> "(no summary)";
        };
    }
}
