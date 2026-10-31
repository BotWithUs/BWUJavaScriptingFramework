package com.botwithus.bot.core.voice;

import com.botwithus.bot.core.config.AtomicFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * Microphone decisions in {@code ~/.botwithus/voice-consent.properties}, shared by every
 * host on the machine.
 *
 * <p>Each script has a {@code decision.<key>} entry, {@code allow} or {@code deny}, and a
 * {@code name.<key>} entry with the name it was shown under. A {@code format} entry gates
 * the file.</p>
 *
 * <p><b>The file is the truth, not this object.</b> Several hosts can be open at once, so
 * {@link #find} reads the file every time; a Reset or a Deny made in another host applies to
 * this host's next question. {@link #save} and {@link #forget} hold a lock on a sidecar
 * {@code .lock} file while they read the file, change the one script they are about, and
 * write it back through {@link AtomicFiles}, so two hosts never undo each other's changes.
 * {@link #all} is cached until the file changes, because the Settings page reads it every
 * frame.</p>
 *
 * <p><b>Anything unexpected reads as "no decision", never as consent.</b> A file that cannot
 * be read or parsed, a missing or different {@code format}, and a decision value other than
 * exactly {@code allow} or {@code deny} all leave the script undecided. Such a file is never
 * overwritten, so a damaged read cannot wipe the decisions it holds. A change that cannot be
 * written is kept in memory and holds until the host closes.</p>
 */
public final class VoiceConsentFile implements VoiceConsentStore {

    /** The file's name inside the host's data folder. */
    public static final String FILE_NAME = "voice-consent.properties";

    private static final Logger log = LoggerFactory.getLogger(VoiceConsentFile.class);
    private static final String DATA_FOLDER_NAME = ".botwithus";
    private static final String LOCK_SUFFIX = ".lock";
    private static final String FORMAT_KEY = "format";
    private static final String FORMAT_VERSION = "1";
    private static final String DECISION_PREFIX = "decision.";
    private static final String NAME_PREFIX = "name.";
    private static final String HEADER = "BotWithUs microphone decisions per script. Reset them in "
            + "Settings > Scripts. A value other than exactly allow or deny counts as undecided.";
    /** How long a change waits for another host to finish writing before keeping it in memory. */
    private static final Duration LOCK_WAIT = Duration.ofSeconds(2);
    private static final long LOCK_RETRY_MILLIS = 20;

    /** The sidecar lock and its channel; closing releases both. */
    private record Held(FileChannel channel, FileLock lock) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }

    /** What a read of the file found. Untrusted means it exists but must not be rewritten. */
    private record Disk(Map<String, StoredConsent> decisions, boolean isTrusted, Object stamp) {}

    private final Path file;
    private final Path lockFile;
    /** Guarded by {@code this}: changes not yet on disk; an empty value is a forget. */
    private final Map<String, Optional<StoredConsent>> unsaved = new HashMap<>();
    /** Guarded by {@code this}: the last read, reused by {@link #all} while the file is unchanged. */
    private Disk cached;
    /** Guarded by {@code this}: the stamp of the last unreadable file warned about, so it warns once. */
    private Object warnedStamp;

    public VoiceConsentFile(Path file) {
        this.file = Objects.requireNonNull(file, "file");
        this.lockFile = file.resolveSibling(file.getFileName() + LOCK_SUFFIX);
    }

    /** The file in the user's {@code ~/.botwithus} folder. Reads nothing until first use. */
    public static VoiceConsentFile inUserHome() {
        return new VoiceConsentFile(Path.of(System.getProperty("user.home"), DATA_FOLDER_NAME, FILE_NAME));
    }

    @Override
    public synchronized Optional<StoredConsent> find(String key) {
        Optional<StoredConsent> pending = unsaved.get(key);
        if (unsaved.containsKey(key)) {
            return pending;
        }
        return Optional.ofNullable(read().decisions().get(key));
    }

    @Override
    public synchronized List<StoredConsent> all() {
        Object stamp = stamp();
        if (cached == null || !Objects.equals(cached.stamp(), stamp)) {
            cached = read();
        }
        Map<String, StoredConsent> merged = new HashMap<>(cached.decisions());
        unsaved.forEach((key, consent) -> consent.ifPresentOrElse(c -> merged.put(key, c),
                () -> merged.remove(key)));
        return merged.values().stream().sorted(MemoryConsentStore.BY_NAME).toList();
    }

    @Override
    public synchronized void save(StoredConsent consent) {
        change(consent.key(), Optional.of(consent));
    }

    @Override
    public synchronized void forget(String key) {
        change(key, Optional.empty());
    }

    /** Applies one script's change to the file under the lock, or keeps it in memory. */
    private void change(String key, Optional<StoredConsent> consent) {
        unsaved.put(key, consent);
        try (Held lock = lock()) {
            if (lock == null) {
                log.warn("Another host kept {} locked; the microphone decision for {} holds until this host closes",
                        lockFile, key);
                return;
            }
            Disk disk = read();
            if (!disk.isTrusted()) {
                log.warn("Not rewriting the unreadable {}; the microphone decision for {} holds until this host closes",
                        file, key);
                return;
            }
            Map<String, StoredConsent> next = new HashMap<>(disk.decisions());
            consent.ifPresentOrElse(c -> next.put(key, c), () -> next.remove(key));
            write(next);
            unsaved.remove(key);
            cached = null;
        } catch (IOException e) {
            log.warn("Could not save microphone decisions to {} ({}); the decision for {} holds until this host closes",
                    file, e.getMessage(), key);
        }
    }

    /** The sidecar lock, or null if another host held it for {@link #LOCK_WAIT}. */
    private Held lock() throws IOException {
        Files.createDirectories(lockFile.toAbsolutePath().getParent());
        FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        long deadline = System.nanoTime() + LOCK_WAIT.toNanos();
        try {
            do {
                FileLock lock = tryLock(channel);
                if (lock != null) {
                    return new Held(channel, lock);
                }
                Thread.sleep(LOCK_RETRY_MILLIS);
            } while (System.nanoTime() < deadline);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            channel.close();
            throw e;
        }
        channel.close();
        return null;
    }

    /** One attempt: null if another holder has it, in this process or another. */
    private static FileLock tryLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException e) {
            return null;
        }
    }

    private Object stamp() {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            return List.of(attributes.lastModifiedTime(), attributes.size());
        } catch (IOException e) {
            return List.of();
        }
    }

    private Disk read() {
        Object stamp = stamp();
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file);
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (NoSuchFileException e) {
            return new Disk(Map.of(), true, stamp);
        } catch (IOException | IllegalArgumentException e) {
            warnOnce(stamp, "Could not read microphone decisions from {} ({}); every script will be asked again",
                    e.getMessage());
            return new Disk(Map.of(), false, stamp);
        }
        if (!FORMAT_VERSION.equals(props.getProperty(FORMAT_KEY))) {
            warnOnce(stamp, "{} is not a format 1 microphone decision file ({}); every script will be asked again",
                    props.getProperty(FORMAT_KEY, "no format"));
            return new Disk(Map.of(), false, stamp);
        }
        return new Disk(decisionsIn(props), true, stamp);
    }

    private void warnOnce(Object stamp, String message, String detail) {
        if (!Objects.equals(warnedStamp, stamp)) {
            warnedStamp = stamp;
            log.warn(message, file, detail);
        }
    }

    private Map<String, StoredConsent> decisionsIn(Properties props) {
        Map<String, StoredConsent> found = new HashMap<>();
        for (String name : props.stringPropertyNames()) {
            if (!name.startsWith(DECISION_PREFIX)) {
                continue;
            }
            String key = name.substring(DECISION_PREFIX.length());
            Optional<MicrophoneDecision> decision = MicrophoneDecision.parse(props.getProperty(name));
            if (key.isBlank() || decision.isEmpty()) {
                continue;
            }
            String shown = props.getProperty(NAME_PREFIX + key, key);
            found.put(key, new StoredConsent(key, shown, decision.get()));
        }
        return found;
    }

    private void write(Map<String, StoredConsent> decisions) throws IOException {
        Properties props = new Properties();
        props.setProperty(FORMAT_KEY, FORMAT_VERSION);
        for (StoredConsent consent : decisions.values()) {
            props.setProperty(DECISION_PREFIX + consent.key(), consent.decision().stored());
            props.setProperty(NAME_PREFIX + consent.key(), consent.displayName());
        }
        AtomicFiles.write(file, out -> props.store(out, HEADER));
    }
}
