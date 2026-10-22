package com.botwithus.bot.core.voice;

import com.botwithus.bot.api.voice.Heard;
import com.botwithus.bot.api.voice.VoiceSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The real SAPI path, end to end, with no microphone: Windows' own text-to-speech renders
 * a phrase to a {@code .wav}, and {@link SapiRecognizer} recognises that file through the
 * same grammar and event code the microphone path uses.
 *
 * <p>Opt-in: {@code ./gradlew :core:harnessTest -PharnessTests=*SapiVoiceLiveTest*}. Needs
 * Windows with a speech recogniser for the display language and a TTS voice (both ship with
 * Windows); the WAV is rendered by PowerShell's {@code System.Speech}.</p>
 */
@EnabledOnOs(OS.WINDOWS)
@EnabledIfSystemProperty(named = "botwithus.smoke.live", matches = "true")
class SapiVoiceLiveTest {

    private static final long HEAR_TIMEOUT_S = 15;
    private static final long SYNTH_TIMEOUT_S = 60;

    @TempDir
    Path dir;

    @Test
    void defaultMicrophone_available_answersWithoutThrowing() {
        try (VoiceInputService voice = VoiceInputService.windowsDefaultMicrophone()) {
            System.out.println("default microphone available: " + voice.available());
        }
    }

    @Test
    void spokenPhrase_isHeardAsTheMatchingGrammarPhrase() throws Exception {
        Heard heard = hear("hello bot", List.of("hello bot", "stop"));

        assertNotNull(heard, "nothing recognised");
        assertEquals("hello bot", heard.phrase());
        assertTrue(heard.confidence() > 0f, "confidence " + heard.confidence());
        System.out.println("heard " + heard);
    }

    @Test
    void secondPhraseOfTheGrammar_isHeardByItsOwnRule() throws Exception {
        Heard heard = hear("set food to lobster", List.of("enable banking", "set food to lobster", "stop"));

        assertNotNull(heard, "nothing recognised");
        assertEquals("set food to lobster", heard.phrase());
    }

    @Test
    void phraseOutsideTheGrammar_isNeverReportedAsItself() throws Exception {
        Heard heard = hear("open the bank", List.of("hello bot", "stop"));

        // Command-and-control may force a low-confidence match onto a listed phrase; what it
        // must never do is invent text that is not in the grammar.
        if (heard != null) {
            assertTrue(List.of("hello bot", "stop").contains(heard.phrase()), heard.toString());
            System.out.println("out-of-grammar speech came through as " + heard);
        }
    }

    private Heard hear(String spoken, List<String> phrases) throws IOException, InterruptedException {
        Path wav = synthesize(spoken);
        BlockingQueue<Heard> heard = new LinkedBlockingQueue<>();
        try (VoiceInputService voice = VoiceInputService.windows(AudioSource.waveFile(wav))) {
            assumeTrue(voice.available(), "no speech recogniser installed");
            try (VoiceSession _ = voice.listen(phrases, heard::add)) {
                return heard.poll(HEAR_TIMEOUT_S, TimeUnit.SECONDS);
            }
        }
    }

    /** {@code text} spoken by the default Windows voice into a fresh {@code .wav}. */
    private Path synthesize(String text) throws IOException, InterruptedException {
        Path wav = dir.resolve(text.replace(' ', '-') + ".wav");
        String script = "Add-Type -AssemblyName System.Speech;"
                + "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer;"
                + "$s.SetOutputToWaveFile('" + wav + "');"
                + "$s.Speak('" + text + "');"
                + "$s.Dispose()";
        String systemRoot = System.getenv("SystemRoot");
        assumeTrue(systemRoot != null, "SystemRoot is not set");
        Path powershell = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        assumeTrue(Files.isRegularFile(powershell), "no Windows PowerShell at " + powershell);
        Process process = new ProcessBuilder(powershell.toString(), "-NoProfile", "-NonInteractive", "-Command", script)
                .inheritIO()
                .start();
        assumeTrue(process.waitFor(SYNTH_TIMEOUT_S, TimeUnit.SECONDS) && process.exitValue() == 0,
                "PowerShell System.Speech could not render the phrase");
        assumeTrue(Files.size(wav) > 0, "empty WAV");
        return wav;
    }
}
