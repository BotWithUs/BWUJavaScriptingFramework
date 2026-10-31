package com.botwithus.bot.core.voice;

import com.botwithus.bot.core.config.AtomicFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Microphone decisions in {@code ~/.botwithus/voice-consent.properties}.
 *
 * <p>Each script has a {@code decision.<key>} entry, {@code allow} or {@code deny}, and a
 * {@code name.<key>} entry with the name it was shown under. A {@code format} entry gates
 * the file. The file is read once, on first use, and every change rewrites it through
 * {@link AtomicFiles}, so a crash leaves the old file or the new one.</p>
 *
 * <p><b>Anything unexpected reads as "no decision", never as consent.</b> A file that cannot
 * be read or parsed, a missing or different {@code format}, and a decision value other than
 * exactly {@code allow} or {@code deny} all leave the script undecided, so it is asked
 * again. Each such problem is logged once, when the file is read.</p>
 */
public final class VoiceConsentFile implements VoiceConsentStore {

    /** The file's name inside the host's data folder. */
    public static final String FILE_NAME = "voice-consent.properties";

    private static final Logger log = LoggerFactory.getLogger(VoiceConsentFile.class);
    private static final String DATA_FOLDER_NAME = ".botwithus";
    private static final String FORMAT_KEY = "format";
    private static final String FORMAT_VERSION = "1";
    private static final String DECISION_PREFIX = "decision.";
    private static final String NAME_PREFIX = "name.";
    private static final String HEADER = "BotWithUs microphone decisions per script. Reset them in "
            + "Settings > Scripts. A value other than exactly allow or deny counts as undecided.";

    private final Path file;
    /** Guarded by {@code this}; null until first read. */
    private Map<String, StoredConsent> decisions;

    public VoiceConsentFile(Path file) {
        this.file = file;
    }

    /** The file in the user's {@code ~/.botwithus} folder. Reads nothing until first use. */
    public static VoiceConsentFile inUserHome() {
        return new VoiceConsentFile(Path.of(System.getProperty("user.home"), DATA_FOLDER_NAME, FILE_NAME));
    }

    @Override
    public synchronized Optional<StoredConsent> find(String key) {
        return Optional.ofNullable(loaded().get(key));
    }

    @Override
    public synchronized List<StoredConsent> all() {
        return loaded().values().stream().sorted(MemoryConsentStore.BY_NAME).toList();
    }

    @Override
    public synchronized void save(StoredConsent consent) {
        loaded().put(consent.key(), consent);
        write();
    }

    @Override
    public synchronized void forget(String key) {
        if (loaded().remove(key) != null) {
            write();
        }
    }

    private Map<String, StoredConsent> loaded() {
        if (decisions == null) {
            decisions = read();
        }
        return decisions;
    }

    private Map<String, StoredConsent> read() {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file);
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (NoSuchFileException e) {
            return new HashMap<>();
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Could not read microphone decisions from {} ({}); every script will be asked again",
                    file, e.getMessage());
            return new HashMap<>();
        }
        if (!FORMAT_VERSION.equals(props.getProperty(FORMAT_KEY))) {
            log.warn("{} is not a format {} microphone decision file; every script will be asked again",
                    file, FORMAT_VERSION);
            return new HashMap<>();
        }
        return decisionsIn(props);
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
                log.warn("Ignoring the unreadable microphone decision {} in {}", name, file);
                continue;
            }
            String shown = props.getProperty(NAME_PREFIX + key, key);
            found.put(key, new StoredConsent(key, shown, decision.get()));
        }
        return found;
    }

    private void write() {
        Properties props = new Properties();
        props.setProperty(FORMAT_KEY, FORMAT_VERSION);
        for (StoredConsent consent : decisions.values()) {
            props.setProperty(DECISION_PREFIX + consent.key(), consent.decision().stored());
            props.setProperty(NAME_PREFIX + consent.key(), consent.displayName());
        }
        try {
            AtomicFiles.write(file, out -> props.store(out, HEADER));
        } catch (IOException e) {
            log.warn("Could not save microphone decisions to {} ({}); they hold until the host closes",
                    file, e.getMessage());
        }
    }
}
