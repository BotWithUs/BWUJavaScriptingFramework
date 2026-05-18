package com.botwithus.bot.core.resolver.pgp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.TypeAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory;
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Manages the user's trusted-keys store at {@code ~/.botwithus/}:
 * <ul>
 *   <li>{@code trusted-keys.gpg} — a BouncyCastle-compatible
 *       {@link PGPPublicKeyRingCollection} (binary; armored input is
 *       decoded on import).</li>
 *   <li>{@code trusted-keys.json} — Gson-serialised {@link TrustedKey}
 *       list keyed by hex key-ID; carries the user-ID and import
 *       timestamp for {@code scripts trust list} rendering.</li>
 * </ul>
 *
 * <p>Atomic writes (temp-file + {@link Files#move ATOMIC_MOVE} with
 * cross-FS fallback). Loading is lazy: each public accessor reads from
 * the in-memory cache; {@link #load} is invoked once at construction by
 * the CLI wiring.</p>
 *
 * <p>BouncyCastle classes are referenced from {@link #addKey} only —
 * not from class-init, not from {@link #load}. A CLI session that never
 * runs {@code scripts trust add} pays no BC class-load cost from this
 * class.</p>
 */
public final class KeyRingStore {

    private static final Logger log = LoggerFactory.getLogger(KeyRingStore.class);
    private static final String TMP_SUFFIX = ".tmp";
    private static final String DEFAULT_USER_ID = "(no user-id in key packet)";
    private static final int KEY_ID_HEX_DIGITS = 16;

    public static final Path DEFAULT_KEYRING_PATH =
            Path.of(System.getProperty("user.home"), ".botwithus", "trusted-keys.gpg");
    public static final Path DEFAULT_METADATA_PATH =
            Path.of(System.getProperty("user.home"), ".botwithus", "trusted-keys.json");

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(Instant.class, new InstantTypeAdapter())
            .create();
    private static final Type LIST_TYPE = new TypeToken<List<TrustedKey>>() {}.getType();

    private final Path keyringFile;
    private final Path metadataFile;
    private final Map<String, TrustedKey> byKeyId = new LinkedHashMap<>();

    public KeyRingStore(Path keyringFile, Path metadataFile) {
        this.keyringFile = Objects.requireNonNull(keyringFile, "keyringFile");
        this.metadataFile = Objects.requireNonNull(metadataFile, "metadataFile");
    }

    public Path keyringFile() {
        return keyringFile;
    }

    public synchronized void load() throws IOException {
        byKeyId.clear();
        if (!Files.exists(metadataFile)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(metadataFile)) {
            List<TrustedKey> loaded = GSON.fromJson(reader, LIST_TYPE);
            if (loaded != null) {
                for (TrustedKey k : loaded) {
                    if (k != null) {
                        byKeyId.put(k.keyId(), k);
                    }
                }
            }
        } catch (JsonIOException | JsonSyntaxException e) {
            throw new IOException("malformed " + metadataFile + ": " + e.getMessage(), e);
        }
    }

    public synchronized List<TrustedKey> list() {
        return List.copyOf(byKeyId.values());
    }

    public synchronized Optional<TrustedKey> find(String keyId) {
        return Optional.ofNullable(byKeyId.get(Objects.requireNonNull(keyId, "keyId")));
    }

    /** Returns the in-memory snapshot of trusted key IDs (uppercase hex). */
    public synchronized Set<String> trustedKeyIds() {
        return Set.copyOf(byKeyId.keySet());
    }

    /**
     * Returns the immutable {@link KeyRing} view consumed by
     * {@link BouncyCastlePgpVerifier}. {@code Optional.empty()} when no
     * keys have been trusted yet.
     */
    public synchronized Optional<KeyRing> currentKeyRing() {
        if (byKeyId.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new KeyRing(keyringFile, Set.copyOf(byKeyId.keySet())));
    }

    /**
     * Imports one or more public keys from an armored or binary PGP key
     * file. Returns the list of key IDs (hex uppercase) actually added.
     * Already-known keys are skipped (no-op for that key, included in
     * the returned list as "already-present").
     */
    public synchronized List<String> addKey(Path keyFile) throws IOException {
        Objects.requireNonNull(keyFile, "keyFile");
        if (!Files.exists(keyFile)) {
            throw new IOException("key file not found: " + keyFile);
        }
        List<PGPPublicKeyRing> newRings;
        try {
            newRings = readKeyRings(keyFile);
        } catch (PGPException e) {
            throw new IOException("could not parse key file " + keyFile + ": " + e.getMessage(), e);
        }
        if (newRings.isEmpty()) {
            throw new IOException("no PGP public-key packets in " + keyFile);
        }

        PGPPublicKeyRingCollection existing = loadOrEmptyRingCollection();
        PGPPublicKeyRingCollection updated = existing;
        List<String> imported = new ArrayList<>();
        Instant now = Instant.now();
        for (PGPPublicKeyRing ring : newRings) {
            PGPPublicKey master = ring.getPublicKey();
            String keyId = formatKeyId(master.getKeyID());
            if (byKeyId.containsKey(keyId)) {
                imported.add(keyId);
                continue;
            }
            updated = PGPPublicKeyRingCollection.addPublicKeyRing(updated, ring);
            byKeyId.put(keyId, new TrustedKey(keyId, primaryUserId(master), now));
            imported.add(keyId);
        }

        writeRingCollection(updated);
        writeMetadata();
        return imported;
    }

    /**
     * Removes the public-key ring whose master key matches {@code keyId}
     * (16-hex-digit uppercase). Returns {@code true} if a key was
     * removed.
     */
    public synchronized boolean removeKey(String keyId) throws IOException {
        Objects.requireNonNull(keyId, "keyId");
        if (!byKeyId.containsKey(keyId)) {
            return false;
        }
        PGPPublicKeyRingCollection existing = loadOrEmptyRingCollection();
        PGPPublicKeyRingCollection updated = existing;
        Iterator<PGPPublicKeyRing> iter = existing.getKeyRings();
        while (iter.hasNext()) {
            PGPPublicKeyRing ring = iter.next();
            if (formatKeyId(ring.getPublicKey().getKeyID()).equals(keyId)) {
                updated = PGPPublicKeyRingCollection.removePublicKeyRing(updated, ring);
            }
        }
        writeRingCollection(updated);
        byKeyId.remove(keyId);
        writeMetadata();
        return true;
    }

    private PGPPublicKeyRingCollection loadOrEmptyRingCollection() throws IOException {
        if (!Files.exists(keyringFile)) {
            return new PGPPublicKeyRingCollection(List.of());
        }
        try (InputStream in = new BufferedInputStream(Files.newInputStream(keyringFile));
             InputStream decoded = PGPUtil.getDecoderStream(in)) {
            return new PGPPublicKeyRingCollection(decoded, new BcKeyFingerprintCalculator());
        } catch (PGPException e) {
            throw new IOException("could not load keyring " + keyringFile + ": " + e.getMessage(), e);
        }
    }

    private static List<PGPPublicKeyRing> readKeyRings(Path keyFile) throws IOException, PGPException {
        List<PGPPublicKeyRing> rings = new ArrayList<>();
        try (InputStream in = new BufferedInputStream(Files.newInputStream(keyFile));
             InputStream decoded = PGPUtil.getDecoderStream(in)) {
            BcPGPObjectFactory factory = new BcPGPObjectFactory(decoded);
            Object obj;
            while ((obj = factory.nextObject()) != null) {
                switch (obj) {
                    case PGPPublicKeyRing ring -> rings.add(ring);
                    default -> { }
                }
            }
        }
        return rings;
    }

    private void writeRingCollection(PGPPublicKeyRingCollection rings) throws IOException {
        Path parent = keyringFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = (parent != null ? parent : Path.of("."))
                .resolve(keyringFile.getFileName() + TMP_SUFFIX);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        rings.encode(buf);
        try (OutputStream out = Files.newOutputStream(tmp)) {
            out.write(buf.toByteArray());
        }
        moveWithAtomicFallback(tmp, keyringFile);
    }

    private void writeMetadata() throws IOException {
        Path parent = metadataFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = (parent != null ? parent : Path.of("."))
                .resolve(metadataFile.getFileName() + TMP_SUFFIX);
        try (Writer writer = Files.newBufferedWriter(tmp)) {
            GSON.toJson(List.copyOf(byKeyId.values()), LIST_TYPE, writer);
        }
        moveWithAtomicFallback(tmp, metadataFile);
    }

    private static void moveWithAtomicFallback(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException atomicFailed) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Returns the first user-ID string from the master key's user-IDs
     * iterator, or a placeholder if the packet has none.
     */
    private static String primaryUserId(PGPPublicKey master) {
        Iterator<String> ids = master.getUserIDs();
        if (ids.hasNext()) {
            return ids.next();
        }
        return DEFAULT_USER_ID;
    }

    private static String formatKeyId(long keyId) {
        return String.format("%0" + KEY_ID_HEX_DIGITS + "X", keyId);
    }

    private static final class InstantTypeAdapter extends TypeAdapter<Instant> {
        @Override
        public void write(JsonWriter out, Instant value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.toString());
            }
        }

        @Override
        public Instant read(JsonReader in) throws IOException {
            return Instant.parse(in.nextString());
        }
    }
}
