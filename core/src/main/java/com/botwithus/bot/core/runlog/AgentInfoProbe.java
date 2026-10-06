package com.botwithus.bot.core.runlog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Asks the agent, once, who it is: {@code rpc.agent_info}, which fills the
 * {@code agent_build} and {@code game_revision} run-log header keys.
 *
 * <p>Every way the answer can fail to arrive means {@code unknown}, never an
 * error: an agent that predates the method (it answers with a handler error,
 * and the error's text is deliberately not inspected), any other error, no
 * reply within the deadline, or a reply missing a key, which leaves just that
 * key {@code unknown}. Values are written through as the agent sent them, the
 * unbaked Debug sentinel {@code deadb51d000000000000000000000001} included: it
 * tells a script author the user ran a dev agent.</p>
 *
 * <p>The call runs off the caller's thread, so attaching to a client never
 * waits on it. A run that starts before the answer lands writes
 * {@code unknown}; every run after it writes the agent's values.</p>
 */
public final class AgentInfoProbe {

    /** The agent's method; additive over the pipe, no protocol bump. */
    public static final String METHOD = "rpc.agent_info";
    /** How long to wait for the reply before settling on {@code unknown}. */
    public static final Duration DEFAULT_DEADLINE = Duration.ofSeconds(5);
    static final String BUILD_ID = "build_id";
    static final String GAME_BUILD = "game_build";

    private static final Logger log = LoggerFactory.getLogger(AgentInfoProbe.class);

    private AgentInfoProbe() {
    }

    /** One agent call: the RPC client's {@code callSync} bound to {@link #METHOD}. */
    @FunctionalInterface
    public interface AgentCall {
        Map<String, Object> call();
    }

    /**
     * Starts the probe on a virtual thread and returns its answer as it stands:
     * {@link AgentIdentity#UNKNOWN} until the agent has replied, and for good if
     * it never does. Bind it with {@code ScriptRuntime.setAgentIdentity}.
     */
    public static Pending start(AgentCall agent, Duration deadline) {
        Pending pending = new Pending();
        Thread.ofVirtual().name("agent-info").start(() -> pending.settle(fetch(agent, deadline)));
        return pending;
    }

    /** Asks and waits, at most {@code deadline}. Never throws; any failure is {@code UNKNOWN}. */
    static AgentIdentity fetch(AgentCall agent, Duration deadline) {
        CompletableFuture<Map<String, Object>> reply = CompletableFuture.supplyAsync(agent::call,
                task -> Thread.ofVirtual().name("agent-info-call").start(task));
        try {
            return decode(reply.get(deadline.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            log.info("Agent did not answer {} within {} ms; run logs say unknown",
                    METHOD, deadline.toMillis());
        } catch (ExecutionException e) {
            log.info("Agent has no usable {} ({}); run logs say unknown",
                    METHOD, e.getCause().getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return AgentIdentity.UNKNOWN;
    }

    /** Each key on its own: a missing or blank value is {@code unknown}, the other is kept. */
    static AgentIdentity decode(Map<String, Object> reply) {
        if (reply == null) {
            return AgentIdentity.UNKNOWN;
        }
        return new AgentIdentity(valueOf(reply, BUILD_ID), valueOf(reply, GAME_BUILD));
    }

    private static String valueOf(Map<String, Object> reply, String key) {
        Object value = reply.get(key);
        String text = value == null ? "" : String.valueOf(value).strip();
        return text.isEmpty() ? RunLogHeader.UNKNOWN : text;
    }

    /** The probe's answer so far. */
    public static final class Pending implements Supplier<AgentIdentity> {

        private final CountDownLatch done = new CountDownLatch(1);
        private volatile AgentIdentity identity = AgentIdentity.UNKNOWN;

        private Pending() {
        }

        @Override
        public AgentIdentity get() {
            return identity;
        }

        /** Waits until the probe has settled, for callers that must not race it (tests). */
        public boolean awaitSettled(Duration timeout) throws InterruptedException {
            return done.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        private void settle(AgentIdentity answer) {
            identity = answer;
            done.countDown();
        }
    }
}
