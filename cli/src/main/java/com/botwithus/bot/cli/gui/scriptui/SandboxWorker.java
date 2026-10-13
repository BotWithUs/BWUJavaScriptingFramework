package com.botwithus.bot.cli.gui.scriptui;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Supplier;

/**
 * The thread one script's UI renders on. The UI thread hands it a frame and
 * waits for the outcome, so exactly one thread touches Dear ImGui at a time,
 * as before; the hand-off exists only so that a failed assertion can park
 * <em>this</em> thread instead of exiting the process (see {@link AssertRouter}).
 *
 * <p>The thread is named after the script, so a parked one is recognisable in a
 * thread dump. It outlives the ImGui contexts it renders: a context is replaced
 * after {@code render()} throws, but the thread is given up only on an
 * assertion, when it is parked inside the native call that asserted.</p>
 *
 * <p>There is deliberately no timeout. A {@code render()} that never returns
 * blocks the UI thread, as it always has: abandoning the thread instead would
 * let it resume later and call Dear ImGui while the host's context is current.</p>
 */
final class SandboxWorker {

    private final BlockingQueue<Runnable> jobs = new LinkedBlockingQueue<>();
    private final Thread thread;
    private final AssertRouter router;
    private volatile CompletableFuture<FrameOutcome> pending;
    private volatile boolean isStopped;

    SandboxWorker(String name, AssertRouter router) {
        this.router = router;
        // rule-exception: platform thread, not virtual. A failed assertion parks this thread
        // inside a native call for good; a virtual thread would pin and so hold its carrier
        // forever. See CLAUDE.md, "Script runner threads are platform threads".
        this.thread = Thread.ofPlatform().daemon().name(name).unstarted(this::loop);
        router.route(thread, this::asserted);
        thread.start();
    }

    /** Runs {@code frame} on this thread and waits for it, however long it takes. */
    FrameOutcome run(Supplier<FrameOutcome> frame) {
        CompletableFuture<FrameOutcome> outcome = new CompletableFuture<>();
        pending = outcome;
        jobs.add(() -> outcome.complete(guarded(frame)));
        return outcome.join();
    }

    /** True once an assertion parked the thread; it will never run another frame. */
    boolean isParked() {
        return isStopped;
    }

    /** Lets the thread end once it is idle. A parked thread stays parked. */
    void stop() {
        if (!isStopped) {
            isStopped = true;
            router.unroute(thread);
            jobs.add(() -> { });
        }
    }

    String name() {
        return thread.getName();
    }

    private static FrameOutcome guarded(Supplier<FrameOutcome> frame) {
        try {
            return frame.get();
        } catch (Error e) {
            return new FrameOutcome.Crashed(e);
        }
    }

    /** On this thread, from inside the native call that asserted, just before it parks. */
    private void asserted(ImGuiAssertion assertion) {
        isStopped = true;
        CompletableFuture<FrameOutcome> outcome = pending;
        if (outcome != null) {
            outcome.complete(new FrameOutcome.Asserted(assertion));
        }
    }

    private void loop() {
        try {
            while (!isStopped) {
                jobs.take().run();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
