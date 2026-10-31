package com.botwithus.bot.core.voice;

import com.botwithus.bot.api.voice.Heard;
import com.botwithus.bot.api.voice.VoiceInput;
import com.botwithus.bot.api.voice.VoiceSession;
import com.botwithus.bot.core.runtime.ScriptIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceConsentFileTest {

    private static final String KEY = "wc/com.example.Woodcutter";

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve(VoiceConsentFile.FILE_NAME);
    }

    private void writeFile(String text) throws IOException {
        Files.writeString(file(), text, StandardCharsets.UTF_8);
    }

    @Test
    void decisions_survive_aRestart() {
        VoiceConsentFile first = new VoiceConsentFile(file());
        first.save(new StoredConsent(KEY, "Woodcutter: Oak & Yew", MicrophoneDecision.ALLOWED));
        first.save(new StoredConsent("fish/com.example.Fisher", "Fisher", MicrophoneDecision.DENIED));

        VoiceConsentFile second = new VoiceConsentFile(file());

        assertEquals(Optional.of(new StoredConsent(KEY, "Woodcutter: Oak & Yew", MicrophoneDecision.ALLOWED)),
                second.find(KEY));
        assertEquals(List.of("Fisher", "Woodcutter: Oak & Yew"),
                second.all().stream().map(StoredConsent::displayName).toList());
    }

    @Test
    void forget_survives_aRestart() {
        VoiceConsentFile first = new VoiceConsentFile(file());
        first.save(new StoredConsent(KEY, "Woodcutter", MicrophoneDecision.ALLOWED));
        first.forget(KEY);

        assertEquals(Optional.empty(), new VoiceConsentFile(file()).find(KEY));
    }

    @Test
    void noFile_isNoDecision() {
        assertEquals(Optional.empty(), new VoiceConsentFile(file()).find(KEY));
    }

    @Test
    void unparseableFile_isNoDecision() throws IOException {
        // A malformed \\u escape makes Properties.load throw.
        writeFile("format=1\ndecision." + KEY + "=allow\nbroken=\\u12\n");

        assertEquals(Optional.empty(), new VoiceConsentFile(file()).find(KEY));
    }

    @Test
    void binaryGarbage_isNoDecision() throws IOException {
        Files.write(file(), new byte[] {(byte) 0xFF, 0x00, (byte) 0xFE, 0x5C, 0x75});

        assertEquals(Optional.empty(), new VoiceConsentFile(file()).find(KEY));
    }

    @Test
    void missingOrOtherFormat_isNoDecision() throws IOException {
        writeFile("decision." + KEY + "=allow\n");
        assertEquals(Optional.empty(), new VoiceConsentFile(file()).find(KEY));

        writeFile("format=2\ndecision." + KEY + "=allow\n");
        assertEquals(Optional.empty(), new VoiceConsentFile(file()).find(KEY));
    }

    @Test
    void anythingButExactlyAllowOrDeny_isNoDecision() throws IOException {
        writeFile("format=1\ndecision.a=Allow\ndecision.b=allow \ndecision.c=yes\ndecision.d=\ndecision.e=deny\n");

        VoiceConsentFile store = new VoiceConsentFile(file());

        assertEquals(List.of("e"), store.all().stream().map(StoredConsent::key).toList());
    }

    @Test
    void aCorruptFile_neverReachesTheMic() throws IOException {
        writeFile("format=1\ndecision." + KEY + "=ALLOW\n");
        AtomicInteger listens = new AtomicInteger();
        VoiceInput mic = new VoiceInput() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public VoiceSession listen(List<String> phrases, Consumer<Heard> onHeard) {
                listens.incrementAndGet();
                return () -> { };
            }
        };

        new VoiceConsent(new VoiceConsentFile(file()))
                .forScript(mic, new ScriptIdentity(KEY, "Woodcutter")).listen(List.of("chop"), h -> { });

        assertEquals(0, listens.get());
    }

    @Test
    void aFileThatCannotBeWritten_keepsTheDecisionForThisRun() throws IOException {
        // A directory where the file should be: every write fails.
        Files.createDirectories(file());
        VoiceConsentFile store = new VoiceConsentFile(file());

        store.save(new StoredConsent(KEY, "Woodcutter", MicrophoneDecision.DENIED));

        assertEquals(Optional.of(MicrophoneDecision.DENIED), store.find(KEY).map(StoredConsent::decision));
        assertTrue(Files.isDirectory(file()));
    }
}
