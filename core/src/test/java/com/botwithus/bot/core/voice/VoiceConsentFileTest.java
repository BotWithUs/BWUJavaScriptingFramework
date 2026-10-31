package com.botwithus.bot.core.voice;

import com.botwithus.bot.api.voice.Heard;
import com.botwithus.bot.api.voice.VoiceInput;
import com.botwithus.bot.api.voice.VoiceSession;
import com.botwithus.bot.core.runtime.ScriptIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceConsentFileTest {

    private static final String KEY = "wc/com.example.Woodcutter";
    private static final String OTHER = "fish/com.example.Fisher";

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

    // ── Several hosts on one file ─────────────────────────────────────────

    @Test
    void twoHosts_aResetInOne_isNotUndoneByTheOthersNextSave() {
        new VoiceConsentFile(file()).save(new StoredConsent(KEY, "Woodcutter", MicrophoneDecision.ALLOWED));
        VoiceConsentFile hostA = new VoiceConsentFile(file());
        VoiceConsentFile hostB = new VoiceConsentFile(file());
        assertEquals(Optional.of(MicrophoneDecision.ALLOWED), hostA.find(KEY).map(StoredConsent::decision));

        hostB.forget(KEY);
        assertEquals(Optional.empty(), hostA.find(KEY), "host A sees the reset at its next question");
        hostA.save(new StoredConsent(OTHER, "Fisher", MicrophoneDecision.DENIED));

        assertEquals(Optional.empty(), new VoiceConsentFile(file()).find(KEY), "the reset must survive A's save");
        assertEquals(Optional.empty(), hostA.find(KEY));
        assertEquals(Optional.of(MicrophoneDecision.DENIED),
                new VoiceConsentFile(file()).find(OTHER).map(StoredConsent::decision));
    }

    @Test
    void twoHosts_aDenyInOne_isNotTurnedBackIntoAllowByTheOthersSave() {
        new VoiceConsentFile(file()).save(new StoredConsent(KEY, "Woodcutter", MicrophoneDecision.ALLOWED));
        VoiceConsentFile hostA = new VoiceConsentFile(file());
        VoiceConsentFile hostB = new VoiceConsentFile(file());
        hostA.find(KEY);
        hostA.all();

        hostB.forget(KEY);
        hostB.save(new StoredConsent(KEY, "Woodcutter", MicrophoneDecision.DENIED));
        hostA.save(new StoredConsent(OTHER, "Fisher", MicrophoneDecision.DENIED));

        assertEquals(Optional.of(MicrophoneDecision.DENIED),
                new VoiceConsentFile(file()).find(KEY).map(StoredConsent::decision));
        assertEquals(Optional.of(MicrophoneDecision.DENIED), hostA.find(KEY).map(StoredConsent::decision));
    }

    @Test
    void all_followsAnotherHostsChanges() {
        VoiceConsentFile hostA = new VoiceConsentFile(file());
        VoiceConsentFile hostB = new VoiceConsentFile(file());
        assertEquals(List.of(), hostA.all());

        hostB.save(new StoredConsent(KEY, "Woodcutter", MicrophoneDecision.ALLOWED));

        assertEquals(List.of(KEY), hostA.all().stream().map(StoredConsent::key).toList());
    }

    @Test
    void anUntrustedFile_isNeverRewritten_andTheChangeHoldsInMemory() throws IOException {
        String untrusted = "format=2\ndecision." + OTHER + "=deny\n";
        writeFile(untrusted);
        VoiceConsentFile store = new VoiceConsentFile(file());

        store.save(new StoredConsent(KEY, "Woodcutter", MicrophoneDecision.DENIED));
        store.forget("never/Seen");

        assertEquals(untrusted, Files.readString(file(), StandardCharsets.UTF_8),
                "a damaged read must not wipe the file");
        assertEquals(Optional.of(MicrophoneDecision.DENIED), store.find(KEY).map(StoredConsent::decision));
        assertEquals(List.of(KEY), store.all().stream().map(StoredConsent::key).toList());
    }

    @Test
    void aLockHeldElsewhere_keepsTheChangeInMemory_andLeavesTheFileAlone() throws IOException {
        new VoiceConsentFile(file()).save(new StoredConsent(OTHER, "Fisher", MicrophoneDecision.ALLOWED));
        String before = Files.readString(file(), StandardCharsets.UTF_8);
        VoiceConsentFile store = new VoiceConsentFile(file());
        Path lock = file().resolveSibling(VoiceConsentFile.FILE_NAME + ".lock");

        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock held = channel.lock()) {
            store.save(new StoredConsent(KEY, "Woodcutter", MicrophoneDecision.DENIED));
            assertTrue(held.isValid());
        }

        assertEquals(before, Files.readString(file(), StandardCharsets.UTF_8));
        assertEquals(Optional.of(MicrophoneDecision.DENIED), store.find(KEY).map(StoredConsent::decision));
    }

    // ── A change that could not be written ────────────────────────────────

    /** Runs {@code change} while another holder has the sidecar lock, so it cannot be written. */
    private void whileLocked(Runnable change) throws IOException {
        Path lock = file().resolveSibling(VoiceConsentFile.FILE_NAME + ".lock");
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock held = channel.lock()) {
            change.run();
            assertTrue(held.isValid());
        }
    }

    private Optional<MicrophoneDecision> onDisk(String key) {
        return new VoiceConsentFile(file()).find(key).map(StoredConsent::decision);
    }

    @Test
    void anUnsavedAllow_neverOverridesADenySavedByAnotherHost() throws IOException {
        VoiceConsentFile hostA = new VoiceConsentFile(file());
        whileLocked(() -> hostA.save(new StoredConsent(KEY, "Woodcutter", MicrophoneDecision.ALLOWED)));

        new VoiceConsentFile(file()).save(new StoredConsent(KEY, "Woodcutter", MicrophoneDecision.DENIED));

        assertEquals(Optional.of(MicrophoneDecision.DENIED), hostA.find(KEY).map(StoredConsent::decision));
        assertEquals(List.of(MicrophoneDecision.DENIED),
                hostA.all().stream().map(StoredConsent::decision).toList());
    }

    @Test
    void anUnsavedAllow_isNeitherAnsweredNorListed_overNoDecision() throws IOException {
        VoiceConsentFile store = new VoiceConsentFile(file());

        whileLocked(() -> store.save(new StoredConsent(KEY, "Woodcutter", MicrophoneDecision.ALLOWED)));

        assertEquals(Optional.empty(), store.find(KEY));
        assertEquals(List.of(), store.all());
    }

    @Test
    void anUnsavedDeny_isWrittenWithTheNextChange() throws IOException {
        new VoiceConsentFile(file()).save(new StoredConsent(KEY, "Woodcutter", MicrophoneDecision.ALLOWED));
        VoiceConsentFile store = new VoiceConsentFile(file());
        whileLocked(() -> store.save(new StoredConsent(KEY, "Woodcutter", MicrophoneDecision.DENIED)));
        assertEquals(Optional.of(MicrophoneDecision.ALLOWED), onDisk(KEY), "sanity: the Deny is not on disk yet");

        store.save(new StoredConsent(OTHER, "Fisher", MicrophoneDecision.DENIED));

        assertEquals(Optional.of(MicrophoneDecision.DENIED), onDisk(KEY));
        assertEquals(Optional.of(MicrophoneDecision.DENIED), onDisk(OTHER));
    }

    @Test
    void anUnsavedReset_isWrittenWithTheNextChange() throws IOException {
        new VoiceConsentFile(file()).save(new StoredConsent(KEY, "Woodcutter", MicrophoneDecision.ALLOWED));
        VoiceConsentFile store = new VoiceConsentFile(file());
        whileLocked(() -> store.forget(KEY));
        assertEquals(Optional.empty(), store.find(KEY), "this host already treats it as reset");

        store.save(new StoredConsent(OTHER, "Fisher", MicrophoneDecision.DENIED));

        assertEquals(Optional.empty(), onDisk(KEY));
    }

    @Test
    void flush_writesAnUnsavedDeny_soTheNextStartDoesNotAllow() throws IOException {
        new VoiceConsentFile(file()).save(new StoredConsent(KEY, "Woodcutter", MicrophoneDecision.ALLOWED));
        VoiceConsentFile store = new VoiceConsentFile(file());
        whileLocked(() -> store.save(new StoredConsent(KEY, "Woodcutter", MicrophoneDecision.DENIED)));

        store.flush();

        assertEquals(Optional.of(MicrophoneDecision.DENIED), onDisk(KEY));
    }

    @Test
    void aNewerAllow_replacesAnUnsavedDenyOfTheSameScript() throws IOException {
        VoiceConsentFile store = new VoiceConsentFile(file());
        whileLocked(() -> store.save(new StoredConsent(KEY, "Woodcutter", MicrophoneDecision.DENIED)));

        store.save(new StoredConsent(KEY, "Woodcutter", MicrophoneDecision.ALLOWED));

        assertEquals(Optional.of(MicrophoneDecision.ALLOWED), onDisk(KEY));
        assertEquals(Optional.of(MicrophoneDecision.ALLOWED), store.find(KEY).map(StoredConsent::decision));
    }

    @Test
    void awkwardKeysAndNames_roundTrip() {
        String[] keys = {"m/a=b:c d", "mod.ü/pkg.Ün$Inner", "m/#x!y\\z", "m/‮evil",
            "m/name.decision.q", "decision.m/X"};
        VoiceConsentFile writer = new VoiceConsentFile(file());
        for (String key : keys) {
            writer.save(new StoredConsent(key, "n=" + key, MicrophoneDecision.ALLOWED));
        }

        VoiceConsentFile reader = new VoiceConsentFile(file());
        for (String key : keys) {
            assertEquals(Optional.of(new StoredConsent(key, "n=" + key, MicrophoneDecision.ALLOWED)),
                    reader.find(key), key);
        }
        assertEquals(keys.length, reader.all().size());
    }
}
