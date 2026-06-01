package com.botwithus.bot.test;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.Navigation;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.event.EventBus;
import com.botwithus.bot.api.isc.MessageBus;
import com.botwithus.bot.api.isc.SharedState;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.snapshot.GameSnapshot;

import java.util.List;
import java.util.function.Supplier;

/**
 * Test-only {@link ScriptContext} composed from a builder.
 *
 * <p>The two configurable seams are:
 * <ul>
 *   <li>{@link Builder#withSnapshot(Supplier)} — provides the snapshot that
 *       the embedded {@link GameAPI} returns from {@code snapshot()} /
 *       {@code getLocalPlayer()}.</li>
 *   <li>{@link Builder#recordingActionsInto(List)} — every {@code queueAction}
 *       and {@code queueActions} invocation appends the action's string form
 *       to the supplied sink, in order. Subsequent asserts inspect the list
 *       to verify the script issued the expected commands.</li>
 * </ul>
 *
 * <p>Everything else on the {@link ScriptContext} returns {@code null} (the
 * {@code MessageBus} / {@code SharedState} / {@code Navigation}) — tests
 * should fail loudly if a script under test reaches a seam that wasn't
 * deliberately stubbed. The embedded {@link InMemoryEventBus} is the one
 * default-on seam since scripts subscribe in {@code onStart} and the bus
 * has no side effects without a publish.</p>
 */
public final class MockScriptContext implements ScriptContext {

    private final GameAPI gameAPI;
    private final InMemoryEventBus eventBus;

    private MockScriptContext(GameAPI gameAPI, InMemoryEventBus eventBus) {
        this.gameAPI = gameAPI;
        this.eventBus = eventBus;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public GameAPI getGameAPI() {
        return gameAPI;
    }

    @Override
    public EventBus getEventBus() {
        return eventBus;
    }

    @Override
    public MessageBus getMessageBus() {
        return null;
    }

    @Override
    public SharedState getSharedState() {
        return null;
    }

    @Override
    public Navigation getNavigation() {
        return null;
    }

    /** Returns the {@link InMemoryEventBus} so tests can publish into it. */
    public InMemoryEventBus eventBus() {
        return eventBus;
    }

    /**
     * Builder for {@link MockScriptContext}. At minimum a snapshot supplier
     * must be configured; the action sink is optional (defaults to a sink
     * that no-ops).
     */
    public static final class Builder {

        private Supplier<GameSnapshot> snapshotSource = CannedSnapshot::empty;
        private List<String> actionSink;

        private Builder() {}

        /**
         * Sets the source of {@link GameSnapshot} that the embedded
         * {@link GameAPI} returns from {@code snapshot()} and
         * {@code getLocalPlayer()}. The supplier is called every time the
         * script asks for the snapshot — tests can mutate the returned
         * snapshot reference between calls to simulate ticks.
         */
        public Builder withSnapshot(Supplier<GameSnapshot> source) {
            if (source == null) {
                throw new IllegalArgumentException("source");
            }
            this.snapshotSource = source;
            return this;
        }

        /**
         * Records the string form of every queued action into the supplied
         * sink. The list is appended-to in invocation order; tests inspect
         * it after running the script.
         */
        public Builder recordingActionsInto(List<String> sink) {
            if (sink == null) {
                throw new IllegalArgumentException("sink");
            }
            this.actionSink = sink;
            return this;
        }

        public MockScriptContext build() {
            List<String> sink = actionSink;
            GameAPI api = new MockGameAPI(
                    snapshotSource,
                    sink == null ? action -> {} : action -> sink.add(formatAction(action)));
            return new MockScriptContext(api, new InMemoryEventBus());
        }

        private static String formatAction(GameAction action) {
            return action.actionId() + "/" + action.param1() + "/" + action.param2() + "/" + action.param3();
        }
    }
}
