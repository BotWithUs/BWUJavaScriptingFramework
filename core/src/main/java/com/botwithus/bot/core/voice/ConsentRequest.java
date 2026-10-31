package com.botwithus.bot.core.voice;

import com.botwithus.bot.core.runtime.ScriptIdentity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * One script's question to the user: may it use the microphone. Shown by the host's
 * front-end and answered through {@link VoiceConsent#answer}.
 *
 * <p>There is at most one per script at a time. Every run of that script still waiting
 * (one per client it runs on) shares it, and one answer reaches them all.</p>
 */
public final class ConsentRequest {

    private final ScriptIdentity script;
    /** The phrases the script last asked to listen for; a later {@code listen} replaces them. */
    private volatile List<String> phrases;
    /** Guarded by the owning {@link VoiceConsent}'s lock. */
    private final List<Consumer<ConsentAnswer>> waiters = new ArrayList<>();

    ConsentRequest(ScriptIdentity script, List<String> phrases) {
        this.script = script;
        this.phrases = List.copyOf(phrases);
    }

    /** The name the user sees for the script that is asking. */
    public String scriptName() {
        return script.displayName();
    }

    /** The key the decision is remembered under, {@code <module>/<class>}. */
    public String scriptKey() {
        return script.key();
    }

    /**
     * The phrases the script wants to listen for: the thing being consented to. The latest
     * the script asked for, so read it each time the prompt is drawn.
     */
    public List<String> phrases() {
        return phrases;
    }

    void setPhrases(List<String> latest) {
        this.phrases = List.copyOf(latest);
    }

    /** The waiters; the caller holds the owning consent's lock. */
    List<Consumer<ConsentAnswer>> waiters() {
        return waiters;
    }
}
