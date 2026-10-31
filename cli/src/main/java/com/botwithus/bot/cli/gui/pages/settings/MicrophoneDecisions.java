package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.core.voice.MicrophoneDecision;
import com.botwithus.bot.core.voice.VoiceConsent;

import java.util.List;

/** The remembered microphone decisions, as the Settings page lists and resets them. */
public interface MicrophoneDecisions {

    /** Every script with a remembered decision, in name order. Cheap enough to call every frame. */
    List<MicrophoneRow> rows();

    /** Forgets the decision for the script with {@code key}, so it is asked again. */
    void reset(String key);

    /** No decisions and nothing to reset: a page with no voice input behind it. */
    static MicrophoneDecisions none() {
        return new MicrophoneDecisions() {
            @Override
            public List<MicrophoneRow> rows() {
                return List.of();
            }

            @Override
            public void reset(String key) {
            }
        };
    }

    /** The decisions {@code consent} remembers. */
    static MicrophoneDecisions of(VoiceConsent consent) {
        return new MicrophoneDecisions() {
            @Override
            public List<MicrophoneRow> rows() {
                return consent.decisions().stream()
                        .map(d -> new MicrophoneRow(d.key(), d.displayName(),
                                d.decision() == MicrophoneDecision.ALLOWED))
                        .toList();
            }

            @Override
            public void reset(String key) {
                consent.reset(key);
            }
        };
    }
}
