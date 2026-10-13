package com.botwithus.bot.cli.gui.scriptui;

import imgui.ImGui;
import imgui.assertion.ImAssertCallback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * The one Dear ImGui assertion handler for the process, replacing imgui-java's
 * default. It decides, per thread, what a failed assertion costs.
 *
 * <p>Why it has to look like this. In imgui-java 1.90 a native {@code IM_ASSERT}
 * calls {@link ImAssertCallback#imAssert}, which runs the installed
 * {@link ImAssertCallback#imAssertCallback} and then calls {@code System.exit(1)}
 * whether the callback returns or throws an {@code Exception}. Throwing an
 * {@code Error} would skip the exit, but the native function then carries on
 * past the failed check, and several of Dear ImGui's checks guard an
 * out-of-bounds read or write. So the only safe outcomes are to let the process
 * exit, or to never return to native code. (imgui-java's callback type is an
 * abstract class; it is adapted here, not extended.)</p>
 *
 * <ul>
 *   <li>On a thread a {@link ScriptUiSandbox} routed here (a script UI's own
 *       render thread), the failure is handed to that sandbox and the thread
 *       <b>parks for good</b>. Dear ImGui never runs past the check, and the
 *       sandbox's context and thread are abandoned, not reused.</li>
 *   <li>On any other thread (the host's own UI), it logs and returns, and
 *       imgui-java exits the process exactly as it did before this class
 *       existed: an assertion in the host's own code is a host bug.</li>
 * </ul>
 */
public final class AssertRouter {

    private static final Logger log = LoggerFactory.getLogger(AssertRouter.class);

    private final Map<Thread, Consumer<ImGuiAssertion>> sinks = new ConcurrentHashMap<>();

    private AssertRouter() {
    }

    /** Creates the router and makes it Dear ImGui's assertion handler. Call once, from the UI thread. */
    public static AssertRouter install() {
        AssertRouter router = new AssertRouter();
        ImGui.setAssertCallback(new ImAssertCallback() {
            @Override
            public void imAssertCallback(String assertion, int line, String file) {
                router.failed(new ImGuiAssertion(assertion, file, line));
            }
        });
        return router;
    }

    /** From now on, a failed assertion on {@code thread} goes to {@code sink} and parks the thread. */
    void route(Thread thread, Consumer<ImGuiAssertion> sink) {
        sinks.put(thread, sink);
    }

    /** {@code thread} no longer runs ImGui code; an assertion on it would be the host's. */
    void unroute(Thread thread) {
        sinks.remove(thread);
    }

    /** On whichever thread asserted, from inside the native call. Returns only for the host's own threads. */
    private void failed(ImGuiAssertion failed) {
        Thread current = Thread.currentThread();
        Consumer<ImGuiAssertion> sink = sinks.remove(current);
        if (sink == null) {
            log.error("Dear ImGui assertion failed in the host's own UI; imgui-java exits the process: {}",
                    failed.describe());
            return;
        }
        try {
            sink.accept(failed);
        } catch (RuntimeException e) {
            log.error("Could not report a script UI's ImGui assertion {}", failed.describe(), e);
        }
        parkForever();
    }

    /**
     * Never returns, so the native frame that asserted never resumes. Interrupts
     * and spurious wake-ups only loop back into the park.
     */
    private static void parkForever() {
        while (true) {
            LockSupport.park();
        }
    }
}
