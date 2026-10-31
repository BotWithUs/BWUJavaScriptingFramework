package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.voice.VoiceInput;

/**
 * Decides what voice input each script is given. The runtime asks it once per script run.
 *
 * <p>The gate, not the host's front-end, is what keeps the microphone closed for a script
 * the user has not allowed: a host that installs none gets the runtime's default, which
 * refuses every script.</p>
 */
@FunctionalInterface
public interface VoiceGate {

    /**
     * @param shared the host's one voice input, shared by every script
     * @param script the script the result is for
     * @return the voice input {@code script} sees
     */
    ScriptVoice forScript(VoiceInput shared, ScriptIdentity script);
}
