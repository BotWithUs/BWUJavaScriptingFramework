package com.botwithus.bot.test;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.Navigation;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.event.EventBus;
import com.botwithus.bot.api.input.FocusMode;
import com.botwithus.bot.api.isc.MessageBus;
import com.botwithus.bot.api.isc.SharedState;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.snapshot.GameSnapshot;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Test-only {@link ScriptContext} composed from a builder.
 *
 * <p>The configurable seams are:
 * <ul>
 *   <li>{@link Builder#withSnapshot(Supplier)} — provides the snapshot that
 *       the embedded {@link GameAPI} returns from {@code snapshot()} /
 *       {@code getLocalPlayer()}.</li>
 *   <li>{@link Builder#recordingActionsInto(List)} — every {@code queueAction}
 *       and {@code queueActions} invocation appends the action's string form
 *       to the supplied sink, in order. Subsequent asserts inspect the list
 *       to verify the script issued the expected commands.</li>
 *   <li>{@link Builder#recordingKeysInto(List)} and
 *       {@link Builder#recordingFocusModesInto(List)} - every delivered
 *       {@code sendKey} and every applied {@code setFocusMode}, in order.
 *       {@link Builder#withRealFocus(boolean)},
 *       {@link Builder#withFocusMode(FocusMode)} and
 *       {@link Builder#withAgentFocusAvailable(boolean)} shape how the mock
 *       answers them.</li>
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
    /** What {@link #getDisplayName()} reports; null for no name. */
    private final String displayName;

    private MockScriptContext(GameAPI gameAPI, InMemoryEventBus eventBus, String displayName) {
        this.gameAPI = gameAPI;
        this.eventBus = eventBus;
        this.displayName = displayName;
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

    @Override
    public Optional<String> getDisplayName() {
        return Optional.ofNullable(displayName);
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
        private String displayName;
        private List<String> keySink;
        private List<FocusMode> focusSink;
        private FocusMode initialFocusMode = FocusMode.REAL;
        private boolean realFocusOnView = true;
        private boolean agentFocusAvailable = true;

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

        /**
         * Makes {@link MockScriptContext#getDisplayName()} report {@code name},
         * as a context on a client logged in or launched as that character would.
         * Without this the context reports no name, as on a client at the login
         * screen that was not launched for a known Jagex character.
         */
        public Builder withDisplayName(String name) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name");
            }
            this.displayName = name;
            return this;
        }

        /**
         * Records every key the script's {@code sendKey} delivered, as
         * {@code "<virtualKey>/<holdMs>"} ({@code sendKey(49)} records
         * {@code "49/0"}), in invocation order. A refused key
         * ({@code NOT_FOCUSED}) is not recorded.
         */
        public Builder recordingKeysInto(List<String> sink) {
            if (sink == null) {
                throw new IllegalArgumentException("sink");
            }
            this.keySink = sink;
            return this;
        }

        /**
         * Records every focus mode a {@code setFocusMode} call applied, in
         * invocation order. A switch to {@link FocusMode#AGENT} refused because of
         * {@link #withAgentFocusAvailable(boolean) withAgentFocusAvailable(false)}
         * is not recorded.
         */
        public Builder recordingFocusModesInto(List<FocusMode> sink) {
            if (sink == null) {
                throw new IllegalArgumentException("sink");
            }
            this.focusSink = sink;
            return this;
        }

        /**
         * Whether the client's render view really holds the keyboard. Defaults to
         * {@code true}. With {@code false}, a {@code sendKey} in
         * {@link FocusMode#REAL} answers {@code NOT_FOCUSED}, as a background
         * client does; in {@link FocusMode#AGENT} keys are still delivered.
         */
        public Builder withRealFocus(boolean focused) {
            this.realFocusOnView = focused;
            return this;
        }

        /** The focus mode the mock starts in. Defaults to {@link FocusMode#REAL}. */
        public Builder withFocusMode(FocusMode mode) {
            if (mode == null) {
                throw new IllegalArgumentException("mode");
            }
            this.initialFocusMode = mode;
            return this;
        }

        /**
         * Whether {@link FocusMode#AGENT} can be armed. Defaults to {@code true}.
         * With {@code false}, {@code setFocusMode(AGENT)} leaves the mode as it
         * was and returns a state whose {@code available} is false, as the real
         * binding does on such a client.
         */
        public Builder withAgentFocusAvailable(boolean available) {
            this.agentFocusAvailable = available;
            return this;
        }

        public MockScriptContext build() {
            List<String> sink = actionSink;
            List<String> keys = keySink;
            List<FocusMode> focus = focusSink;
            MockKeyboard keyboard = new MockKeyboard(initialFocusMode, realFocusOnView, agentFocusAvailable,
                    keys == null ? key -> {} : keys::add,
                    focus == null ? mode -> {} : focus::add);
            GameAPI api = new MockGameAPI(
                    snapshotSource,
                    sink == null ? action -> {} : action -> sink.add(formatAction(action)),
                    keyboard);
            return new MockScriptContext(api, new InMemoryEventBus(), displayName);
        }

        private static String formatAction(GameAction action) {
            return action.actionId() + "/" + action.param1() + "/" + action.param2() + "/" + action.param3();
        }
    }
}
