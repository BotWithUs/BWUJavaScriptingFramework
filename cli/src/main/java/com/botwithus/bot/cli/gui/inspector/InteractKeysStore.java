package com.botwithus.bot.cli.gui.inspector;

import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.SettingKeys;

/** Where the keys held to make a pinned overlay take the mouse are kept. Render thread only. */
sealed interface InteractKeysStore {

    InteractKeys get();

    void set(InteractKeys keys);

    /** Kept in the host's settings, so the combination survives a restart. */
    final class Saved implements InteractKeysStore {

        private final HostSettings settings;
        /** The setting's text as last read, and what it parsed to, so a frame reads it without parsing. */
        private String lastText = "";
        private InteractKeys lastKeys = InteractKeys.DEFAULT;

        Saved(HostSettings settings) {
            this.settings = settings;
        }

        /** The saved combination; the default should the file hold one that does not parse. */
        @Override
        public InteractKeys get() {
            String text = settings.get(SettingKeys.OVERLAY_INTERACT_KEYS);
            if (!text.equals(lastText)) {
                lastText = text;
                lastKeys = InteractKeys.parse(text).orElse(InteractKeys.DEFAULT);
            }
            return lastKeys;
        }

        @Override
        public void set(InteractKeys keys) {
            settings.set(SettingKeys.OVERLAY_INTERACT_KEYS, keys.label());
        }
    }

    /** Kept for this session only, for a GUI with no settings behind it. */
    final class Session implements InteractKeysStore {

        private InteractKeys keys = InteractKeys.DEFAULT;

        @Override
        public InteractKeys get() {
            return keys;
        }

        @Override
        public void set(InteractKeys keys) {
            this.keys = keys;
        }
    }
}
