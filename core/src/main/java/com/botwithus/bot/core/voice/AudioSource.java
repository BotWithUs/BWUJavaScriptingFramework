package com.botwithus.bot.core.voice;

import java.nio.file.Path;
import java.util.Objects;

/** Where the recogniser takes its audio from. */
public sealed interface AudioSource {

    /** The audio input device Windows has set as the default. */
    static AudioSource defaultMicrophone() {
        return new DefaultMicrophone();
    }

    /** A recorded {@code .wav} file, read once from the start. For tests and diagnosis. */
    static AudioSource waveFile(Path file) {
        return new WaveFile(file);
    }

    /** The default capture device. */
    record DefaultMicrophone() implements AudioSource {
    }

    /** A {@code .wav} file. */
    record WaveFile(Path file) implements AudioSource {

        public WaveFile {
            Objects.requireNonNull(file, "file");
        }
    }
}
