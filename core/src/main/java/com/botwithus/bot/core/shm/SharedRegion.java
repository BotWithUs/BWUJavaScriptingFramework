package com.botwithus.bot.core.shm;

import com.botwithus.bot.core.pipe.PipeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.OptionalLong;

/**
 * Opens and owns the kernel-named file mapping
 * {@code Local\nxt_snapshot_<pid>} that the injected NXTLibrary DLL
 * publishes. Validates magic and protocol version, computes the snapshot
 * and event-ring slices once, and exposes them as {@link MemorySegment}
 * views the higher-level readers consume.
 *
 * <p>Lifetime is bound to {@link #close()} — must be called on shutdown to
 * unmap the view and close the kernel handle. The class is not safe for
 * concurrent close from multiple threads, but reads from the exposed
 * segments are race-free as long as readers honour the publish protocol
 * (acquire-load on {@code frontIdx}, acquire-load on {@code slot.seq}).</p>
 *
 * <p>The mapping starts as a single contiguous region the producer sized to
 * exactly cover {@code header + 2*snapshot_stride + ring_stride}; we map the
 * whole thing once and slice it locally. Mapping by full length lets us
 * surface a clear error if the producer published a smaller layout than
 * we know how to read, instead of silently returning short reads.</p>
 */
public final class SharedRegion implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SharedRegion.class);

    private MemorySegment mappingHandle;
    private MemorySegment baseView;
    private MemorySegment header;
    private MemorySegment snapshot0;
    private MemorySegment snapshot1;
    private MemorySegment ring;

    private long expectedSize;
    private boolean closed;

    /**
     * Pipe-name prefix the producer uses; matches NXTLibrary's pipe-server
     * format {@code BotWithUs_<pid>}. Discovery here piggy-backs on the same
     * scan that {@link PipeClient#scanPipes(String)} does for the RPC side —
     * if the DLL is up, both the pipe and the snapshot mapping exist.
     */
    public static final String PIPE_PREFIX = "BotWithUs_";

    /**
     * Scan running pipes whose names match the producer's prefix and return
     * the embedded pids in scan order. Empty list if no game has the DLL
     * injected. Each returned pid is plumbable into {@link #open(long)}.
     */
    public static List<Long> discoverPids() {
        return PipeClient.scanPipes(PIPE_PREFIX).stream()
                .map(SharedRegion::parsePid)
                .filter(OptionalLong::isPresent)
                .map(OptionalLong::getAsLong)
                .toList();
    }

    /**
     * Convenience for the single-game case: returns the first discovered
     * pid wrapped in a region, or empty if none. Throws if the discovered
     * pid exists but the mapping fails to bind.
     */
    public static java.util.Optional<SharedRegion> openFirstAvailable() {
        return discoverPids().stream().findFirst().map(SharedRegion::open);
    }

    /**
     * Parse the embedded pid out of a {@code BotWithUs_<pid>} pipe name as
     * returned by {@link PipeClient#scanPipes(String)}. Returns empty if
     * the suffix isn't a valid u32 decimal.
     */
    public static OptionalLong parsePid(String pipeName) {
        if (pipeName == null || !pipeName.startsWith(PIPE_PREFIX)) {
            return OptionalLong.empty();
        }
        String suffix = pipeName.substring(PIPE_PREFIX.length());
        if (suffix.isEmpty()) return OptionalLong.empty();
        for (int i = 0; i < suffix.length(); ++i) {
            if (!Character.isDigit(suffix.charAt(i))) return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(suffix));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    /**
     * Open the mapping for the game process with the given pid.
     *
     * @throws SharedMemoryException if the mapping doesn't exist (DLL not
     *         injected, wrong pid), can't be mapped, or fails magic/version
     *         validation. The exception's {@link Throwable#getMessage()}
     *         disambiguates which step failed.
     */
    public static SharedRegion open(long pid) {
        String name = Layout.MAPPING_NAME_PREFIX + pid;
        MemorySegment mapping = Kernel32.openFileMapping(
                Kernel32.FILE_MAP_READ, false, name);
        if (mapping.address() == 0) {
            int err = Kernel32.getLastError();
            throw new SharedMemoryException(
                    "OpenFileMappingW(\"" + name + "\") failed: GetLastError=" + err
                            + " (is the DLL injected into pid " + pid + "?)");
        }

        // First pass: map just the header so we can read snapshotSize/ringSize
        // and compute the full region size. Mapping the entire address space
        // up front would also work — kernel mappings expose only their
        // committed length anyway — but the two-step probe makes the failure
        // message clearer when sizes don't match.
        MemorySegment headerView = Kernel32.mapViewOfFile(
                mapping, Kernel32.FILE_MAP_READ, 0, Layout.HEADER_SIZE);
        if (headerView.address() == 0) {
            int err = Kernel32.getLastError();
            Kernel32.closeHandle(mapping);
            throw new SharedMemoryException(
                    "MapViewOfFile(header) failed: GetLastError=" + err);
        }
        // The MemorySegment returned by Panama is unsized — reinterpret with
        // the known byte length so JAVA_INT/JAVA_LONG accessors succeed.
        MemorySegment hdrSlice = headerView.reinterpret(Layout.HEADER_SIZE);

        try {
            validateMagicAndVersion(hdrSlice);
        } catch (RuntimeException ex) {
            Kernel32.unmapViewOfFile(headerView);
            Kernel32.closeHandle(mapping);
            throw ex;
        }

        long snapshotSize = Integer.toUnsignedLong(hdrSlice.get(ValueLayout.JAVA_INT, Layout.HEADER_SNAPSHOTSIZE_OFFSET));
        long snapshotOff0 = Integer.toUnsignedLong(hdrSlice.get(ValueLayout.JAVA_INT, Layout.HEADER_SNAPSHOTOFF0_OFFSET));
        long snapshotOff1 = Integer.toUnsignedLong(hdrSlice.get(ValueLayout.JAVA_INT, Layout.HEADER_SNAPSHOTOFF1_OFFSET));
        long ringOff      = Integer.toUnsignedLong(hdrSlice.get(ValueLayout.JAVA_INT, Layout.HEADER_RINGOFF_OFFSET));
        long ringSize     = Integer.toUnsignedLong(hdrSlice.get(ValueLayout.JAVA_INT, Layout.HEADER_RINGSIZE_OFFSET));

        long totalSize = ringOff + ringSize;
        // Drop the header-only view — re-map the full region in one shot so
        // pointers we hand out remain stable for the lifetime of this object.
        Kernel32.unmapViewOfFile(headerView);

        MemorySegment fullView = Kernel32.mapViewOfFile(
                mapping, Kernel32.FILE_MAP_READ, 0, totalSize);
        if (fullView.address() == 0) {
            int err = Kernel32.getLastError();
            Kernel32.closeHandle(mapping);
            throw new SharedMemoryException(
                    "MapViewOfFile(full=" + totalSize + ") failed: GetLastError=" + err);
        }
        MemorySegment fullSlice = fullView.reinterpret(totalSize);

        SharedRegion r = new SharedRegion();
        r.mappingHandle = mapping;
        r.baseView      = fullView;
        r.expectedSize  = totalSize;
        r.header        = fullSlice.asSlice(0, Layout.HEADER_SIZE);
        r.snapshot0     = fullSlice.asSlice(snapshotOff0, snapshotSize);
        r.snapshot1     = fullSlice.asSlice(snapshotOff1, snapshotSize);
        r.ring          = fullSlice.asSlice(ringOff,      ringSize);

        log.info("Opened shared region for pid {} (snapshot={} bytes, ring={} bytes, total={} bytes)",
                pid, snapshotSize, ringSize, totalSize);
        return r;
    }

    private static void validateMagicAndVersion(MemorySegment header) {
        int magic = header.get(ValueLayout.JAVA_INT, Layout.HEADER_MAGIC_OFFSET);
        if (magic != Layout.MAGIC) {
            throw new SharedMemoryException(String.format(
                    "Bad magic: 0x%08X (expected 0x%08X 'NXTS')", magic, Layout.MAGIC));
        }
        int version = header.get(ValueLayout.JAVA_INT, Layout.HEADER_VERSION_OFFSET);
        if (version != Layout.PROTOCOL_VERSION) {
            throw new SharedMemoryException(
                    "Protocol version mismatch: producer=" + version
                            + " consumer=" + Layout.PROTOCOL_VERSION
                            + " (rebuild whichever side is behind)");
        }
        int headerSize = header.get(ValueLayout.JAVA_INT, Layout.HEADER_HEADERSIZE_OFFSET);
        if (headerSize != Layout.HEADER_SIZE) {
            throw new SharedMemoryException(
                    "Header size mismatch: producer=" + headerSize
                            + " consumer=" + Layout.HEADER_SIZE);
        }
    }

    public MemorySegment header()    { return header; }
    public MemorySegment snapshot0() { return snapshot0; }
    public MemorySegment snapshot1() { return snapshot1; }
    public MemorySegment ring()      { return ring; }

    /**
     * Reads the published front-buffer index. Volatile semantics on x64 are
     * provided by the {@code JAVA_INT} load — Panama documents that
     * {@code MemorySegment#get} on aligned scalar types is sequentially
     * consistent with respect to writes from native code that use
     * {@code InterlockedExchange}-class instructions.
     */
    public int frontIdx() {
        return header.get(ValueLayout.JAVA_INT, Layout.HEADER_FRONTIDX_OFFSET);
    }

    public MemorySegment currentSnapshot() {
        return frontIdx() == 0 ? snapshot0 : snapshot1;
    }

    /**
     * Convenience: returns a typed view over the currently-published
     * snapshot. The underlying slice may be overwritten by a future
     * publish, so don't cache the returned view across ticks.
     */
    public SnapshotView snapshot() {
        return new SnapshotView(currentSnapshot());
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (baseView != null && baseView.address() != 0) {
            Kernel32.unmapViewOfFile(baseView);
        }
        if (mappingHandle != null && mappingHandle.address() != 0) {
            Kernel32.closeHandle(mappingHandle);
        }
        baseView      = null;
        mappingHandle = null;
        header = snapshot0 = snapshot1 = ring = null;
    }
}
