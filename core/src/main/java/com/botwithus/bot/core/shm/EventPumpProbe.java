package com.botwithus.bot.core.shm;

import com.botwithus.bot.api.event.ChatMessageEvent;
import com.botwithus.bot.api.event.LoginStateChangeEvent;
import com.botwithus.bot.api.event.TickEvent;
import com.botwithus.bot.api.event.VarChangeEvent;
import com.botwithus.bot.api.event.VarcChangeEvent;
import com.botwithus.bot.core.impl.EventBusImpl;

/**
 * Smoke-test for the slice-3 bridge: drives the same wiring
 * {@code JBotApplication} now uses — {@link SharedRegionEventPump} feeding
 * an {@link EventBusImpl} — and prints every typed event a subscriber
 * receives. If this runs cleanly under live game traffic, the
 * {@code EventRingReader} → bus → subscriber path is end-to-end correct.
 */
public final class EventPumpProbe {

    private EventPumpProbe() {}

    public static void main(String[] args) throws InterruptedException {
        long pid;
        if (args.length >= 1) {
            pid = Long.parseLong(args[0]);
        } else {
            var pids = SharedRegion.discoverPids();
            if (pids.isEmpty()) {
                System.err.println("No BotWithUs_<pid> pipes found. Is the DLL injected?");
                System.exit(2);
                return;
            }
            pid = pids.getFirst();
        }

        EventBusImpl bus = new EventBusImpl();
        bus.subscribe(TickEvent.class,             e -> System.out.println("Tick:  " + e.tick()));
        bus.subscribe(LoginStateChangeEvent.class, e -> System.out.println("Login: " + e.oldState() + " -> " + e.newState()));
        bus.subscribe(VarChangeEvent.class,        e -> System.out.println("Varp:  id=" + e.varId() + " " + e.oldValue() + "->" + e.newValue()));
        bus.subscribe(VarcChangeEvent.class,       e -> System.out.println("Varc:  id=" + e.varId() + " " + e.oldValue() + "->" + e.newValue()));
        bus.subscribe(ChatMessageEvent.class,      e -> System.out.println("Chat:  '" + e.message().text() + "'"));

        try (SharedRegionEventPump pump = new SharedRegionEventPump(pid, bus::publish)) {
            System.out.println("Pump running for pid=" + pid + ". Ctrl+C to stop.");
            // Sleep until interrupt — pump runs on its own daemon thread.
            Thread.currentThread().join();
        }
    }
}
