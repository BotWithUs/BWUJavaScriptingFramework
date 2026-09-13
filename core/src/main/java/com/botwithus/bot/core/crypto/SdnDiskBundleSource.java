package com.botwithus.bot.core.crypto;

import com.botwithus.bot.core.sdn.SdnRendezvous;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Disk-based SDN bundle source — the file-courier half of the production
 * delivery path. The launcher (which holds the credential and the heartbeat
 * session) fetches the encrypted jar + the per-session key envelope and drops
 * them in a shared directory; this class publishes the JVM's ephemeral public
 * key, waits for the courier to deliver the bundle, and constructs the
 * {@code SdnClassLoader} from it. The host itself never touches the network.
 *
 * <p>The two files are keyed by this JVM's pid: the host writes {@code <pid>.pub}
 * (its ephemeral public key) and the courier answers with {@code <pid>.sdn}
 * (the bundle). Both are written atomically (temp file + atomic rename) so a
 * reader never observes a partial file, and both are deleted once consumed.
 * The mechanics of that exchange live in {@link SdnRendezvous}, which the
 * catalogue courier shares.
 *
 * <p>Disabled unless {@code -Dbotwithus.sdn.disk=true} (the launcher sets it when
 * launching with SDN delivery active), so local-only startups pay nothing.</p>
 */
public final class SdnDiskBundleSource {

    private static final Logger log = LoggerFactory.getLogger(SdnDiskBundleSource.class);

    /** System-property gate; the launcher sets this when SDN delivery is active. */
    public static final String ENABLE_PROP = "botwithus.sdn.disk";
    /** Optional shared-directory override (absolute path). */
    public static final String DIR_PROP = SdnRendezvous.DIR_PROP;

    private static final int ENVELOPE_LEN = 168;

    /**
     * Sanity bound on the jar count, so a mis-framed file is rejected rather
     * than being read as a request to allocate an absurd list. A subscription
     * larger than this is a framing disagreement, not a real delivery.
     */
    private static final int MAX_BUNDLE_JARS = 64;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private SdnDiskBundleSource() {
    }

    /** True iff disk SDN delivery is enabled for this process. */
    public static boolean isEnabled() {
        return Boolean.getBoolean(ENABLE_PROP);
    }

    /** The shared directory the courier and host rendezvous in. */
    public static Path directory() {
        return SdnRendezvous.directory();
    }

    /**
     * Publishes this JVM's public key into the default directory, waits for the
     * courier-delivered bundle, and returns one class loader per jar it carried.
     * Empty if disabled, not delivered within the timeout, or on any error
     * (callers fall back to local scripts).
     */
    public static List<ClassLoader> awaitBundle(ClassLoader parent) {
        return awaitBundle(directory(), DEFAULT_TIMEOUT, parent);
    }

    public static List<ClassLoader> awaitBundle(Path dir, Duration timeout, ClassLoader parent) {
        long pid = SdnRendezvous.currentPid();
        Path pubFile = dir.resolve(pid + ".pub");
        Path bundleFile = dir.resolve(pid + ".sdn");
        try {
            Files.createDirectories(dir);
            byte[] pub = SdnLoader.clientPublicKey();
            SdnRendezvous.atomicWrite(pubFile, pub);
            log.info("SDN: published {} ({} bytes); awaiting bundle in {} (timeout {})",
                    pubFile.getFileName(), pub.length, dir, timeout);

            byte[] bundle = SdnRendezvous.awaitFile(bundleFile, timeout);
            if (bundle == null) {
                // Naming the directory matters: the launcher resolves it from the
                // BWU_SDN_DIR environment variable while this process reads the
                // botwithus.sdn.dir system property, so the two can disagree and
                // the only symptom is this timeout. Without the path it reads as
                // "the courier is not running" and sends you to the wrong process.
                log.info("SDN: no bundle delivered within {} in {} — is the courier "
                        + "running, and is it writing to the same directory?", timeout, dir);
                return List.of();
            }
            return defineAll(bundle, parent);
        } catch (Exception e) {
            log.error("SDN disk bundle load failed: {}", e.getMessage());
            return List.of();
        } finally {
            SdnRendezvous.deleteQuietly(pubFile);
            SdnRendezvous.deleteQuietly(bundleFile);
        }
    }

    /**
     * Parses the courier payload and defines one loader per jar.
     *
     * <p>Wire format, big-endian, agreed with the launcher courier:
     * <pre>
     *   u32  count
     *   byte envelope[168]
     *   repeat count times:
     *       u32  jarLen
     *       byte jar[jarLen]
     * </pre>
     *
     * <p>One envelope for all jars, because the content key is per-SESSION: the
     * site seals every jar in a delivery under the same key.
     *
     * <p>One loader per jar rather than one loader over a merged jar. That is
     * forced, not chosen — {@code SdnClassLoader}'s constructor takes a single
     * jar and its native side parses a single zip, so merging would mean
     * concatenating each script's {@code META-INF/services} entries and
     * resolving class-name collisions between independently-authored scripts.
     *
     * <p>A jar that fails to define is skipped, not fatal: one bad script must
     * not cost the user every other script in the same delivery.
     */
    private static List<ClassLoader> defineAll(byte[] bundle, ClassLoader parent) {
        Optional<BundleFrame> parsed = parseFrame(bundle);
        if (parsed.isEmpty()) {
            return List.of();
        }
        BundleFrame frame = parsed.get();
        List<byte[]> jars = frame.jars();

        List<ClassLoader> loaders = new ArrayList<>(jars.size());
        for (int i = 0; i < jars.size(); i++) {
            defineOne(jars.get(i), frame.envelope(), parent, i + 1, jars.size())
                    .ifPresent(loaders::add);
        }
        log.info("SDN: bundle delivered, {} of {} jar(s) defined", loaders.size(), jars.size());
        return loaders;
    }

    /**
     * One delivery: the shared key envelope and the jars it covers.
     *
     * <p>Package-private so the framing can be tested without the patched JVM.
     * Everything past this point needs {@code SdnLoader.defineLoader}, which
     * only exists on the custom JDK, so a test that went through
     * {@link #defineAll} could not run on a stock toolchain — and the framing
     * is exactly the part where a disagreement with the launcher's writer would
     * be silent.
     */
    record BundleFrame(byte[] envelope, List<byte[]> jars) {
    }

    /**
     * Splits the courier payload. Returns empty on any framing disagreement,
     * having logged what it actually saw: this is a cross-language wire format
     * and "the numbers did not add up" is the only evidence available on this
     * side of it.
     */
    static Optional<BundleFrame> parseFrame(byte[] bundle) {
        ByteBuffer bb = ByteBuffer.wrap(bundle).order(ByteOrder.BIG_ENDIAN);
        if (bb.remaining() < Integer.BYTES + ENVELOPE_LEN) {
            log.warn("SDN: malformed bundle, {} bytes is shorter than a count plus an envelope",
                    bb.remaining());
            return Optional.empty();
        }
        int count = bb.getInt();
        if (count <= 0 || count > MAX_BUNDLE_JARS) {
            log.warn("SDN: malformed bundle, count={} outside 1..{}", count, MAX_BUNDLE_JARS);
            return Optional.empty();
        }
        byte[] envelope = new byte[ENVELOPE_LEN];
        bb.get(envelope);

        List<byte[]> jars = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (bb.remaining() < Integer.BYTES) {
                log.warn("SDN: truncated bundle, no length for jar {} of {}", i + 1, count);
                return Optional.empty();
            }
            int jarLen = bb.getInt();
            if (jarLen < 0 || bb.remaining() < jarLen) {
                log.warn("SDN: malformed bundle, jar {} declares {} bytes with {} remaining",
                        i + 1, jarLen, bb.remaining());
                return Optional.empty();
            }
            byte[] jar = new byte[jarLen];
            bb.get(jar);
            jars.add(jar);
        }
        if (bb.remaining() != 0) {
            log.warn("SDN: {} trailing bytes after the last jar; the courier and this "
                    + "parser disagree about the framing", bb.remaining());
            return Optional.empty();
        }
        return Optional.of(new BundleFrame(envelope, jars));
    }

    private static Optional<ClassLoader> defineOne(byte[] jar, byte[] envelope,
                                                   ClassLoader parent, int index, int count) {
        try {
            return Optional.of(SdnLoader.defineLoader(jar, envelope, parent));
        } catch (Exception e) {
            // Isolated on purpose. The envelope is shared, so a failure here is
            // the jar's own: a wrong content key would fail every jar, not one.
            log.warn("SDN: jar {} of {} ({} bytes) failed to define, skipping: {}",
                    index, count, jar.length, e.getMessage());
            return Optional.empty();
        }
    }
}
