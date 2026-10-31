package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.voice.VoiceInput;

/**
 * The voice input one script sees, which the runtime can release when the script stops.
 */
public interface ScriptVoice extends VoiceInput {

    /**
     * Closes the session the script left open and withdraws any question it is waiting on.
     * Called from the runner's cleanup; repeatable.
     */
    void closeAll();
}
