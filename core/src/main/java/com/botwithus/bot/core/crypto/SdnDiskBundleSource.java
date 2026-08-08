package com.botwithus.bot.core.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
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
 *
 * <p>Disabled unless {@code -Dbotwithus.sdn.disk=true} (the launcher sets it when
 * launching with SDN delivery active), so local-only startups pay nothing.</p>
 */
public final class SdnDiskBundleSource {

    private static final Logger log = LoggerFactory.getLogger(SdnDiskBundleSource.class);

    /** System-property gate; the launcher sets this when SDN delivery is active. */
    public static final String ENABLE_PROP = "botwithus.sdn.disk";
    /** Optional shared-directory override (absolute path). */
    public static final String DIR_PROP = "botwithus.sdn.dir";

    private static final int ENVELOPE_LEN = 168;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final long POLL_MILLIS = 100;

    private SdnDiskBundleSource() {
    }

    /** True iff disk SDN delivery is enabled for this process. */
    public static boolean isEnabled() {
        return Boolean.getBoolean(ENABLE_PROP);
    }

    /** The shared directory the courier and host rendezvous in. */
    public static Path directory() {
        String override = System.getProperty(DIR_PROP);
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of(System.getProperty("user.home"), ".botwithus", "sdn");
    }

    /**
     * Publishes this JVM's public key into the default directory, waits for the
     * courier-delivered bundle, and returns a class loader with the decrypted
     * script classes defined. Empty if disabled, not delivered within the
     * timeout, or on any error (callers fall back to local scripts).
     */
    public static Optional<ClassLoader> awaitBundle(ClassLoader parent) {
        return awaitBundle(directory(), DEFAULT_TIMEOUT, parent);
    }

    public static Optional<ClassLoader> awaitBundle(Path dir, Duration timeout, ClassLoader parent) {
        long pid = ProcessHandle.current().pid();
        Path pubFile = dir.resolve(pid + ".pub");
        Path bundleFile = dir.resolve(pid + ".sdn");
        try {
            Files.createDirectories(dir);
            byte[] pub = SdnLoader.clientPublicKey();
            atomicWrite(pubFile, pub);
            log.info("SDN: published {} ({} bytes); awaiting bundle (timeout {})",
                    pubFile.getFileName(), pub.length, timeout);

            byte[] bundle = awaitFile(bundleFile, timeout);
            if (bundle == null) {
                log.info("SDN: no bundle delivered within {} — is the courier running?", timeout);
                return Optional.empty();
            }

            ByteBuffer bb = ByteBuffer.wrap(bundle).order(ByteOrder.BIG_ENDIAN);
            if (bb.remaining() < 4) {
                log.warn("SDN: malformed bundle file (too short)");
                return Optional.empty();
            }
            int jarLen = bb.getInt();
            if (jarLen < 0 || bb.remaining() != jarLen + ENVELOPE_LEN) {
                log.warn("SDN: malformed bundle (jarLen={}, remaining={})", jarLen, bb.remaining());
                return Optional.empty();
            }
            byte[] jar = new byte[jarLen];
            bb.get(jar);
            byte[] envelope = new byte[ENVELOPE_LEN];
            bb.get(envelope);

            ClassLoader loader = SdnLoader.defineLoader(jar, envelope, parent);
            log.info("SDN: bundle delivered ({} B jar) and decrypted", jarLen);
            return Optional.of(loader);
        } catch (Exception e) {
            log.error("SDN disk bundle load failed: {}", e.getMessage());
            return Optional.empty();
        } finally {
            deleteQuietly(pubFile);
            deleteQuietly(bundleFile);
        }
    }

    private static byte[] awaitFile(Path file, Duration timeout)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(file)) {
                return Files.readAllBytes(file);
            }
            Thread.sleep(POLL_MILLIS);
        }
        return null;
    }

    private static void atomicWrite(Path target, byte[] data) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, data);
        try {
            Files.move(tmp, target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.debug("SDN: could not delete {}: {}", p, e.getMessage());
        }
    }
}
