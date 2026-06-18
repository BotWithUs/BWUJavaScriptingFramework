package com.botwithus.bot.core.shm;

import com.botwithus.bot.core.pipe.PipeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.Optional;
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

    private SharedRegion() {}

    private static final Logger log = LoggerFactory.getLogger(SharedRegion.class);

    private MemorySegment mappingHandle;
    private MemorySegment baseView;
    private MemorySegment header;
    private MemorySegment snapshot0;
    private MemorySegment snapshot1;
    private MemorySegment ring;

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
    public static Optional<SharedRegion> openFirstAvailable() {
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
        if (suffix.isEmpty()) {
            return OptionalLong.empty();
        }
        for (int i = 0; i < suffix.length(); ++i) {
            if (!Character.isDigit(suffix.charAt(i))) {
                return OptionalLong.empty();
            }
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
        MemorySegment mapping = openMappingOrThrow(name, pid);
        RegionLayout layout = probeHeader(mapping, pid);
        MemorySegment fullSlice = mapFullRegion(mapping, layout.totalSize());

        SharedRegion r = new SharedRegion();
        r.mappingHandle = mapping;
        r.baseView      = fullSlice;
        populateSlices(r, fullSlice, layout);

        log.info("Opened shared region for pid {} (snapshot={} bytes, ring={} bytes, total={} bytes)",
                pid, layout.snapshotSize(), layout.ringSize(), layout.totalSize());
        return r;
    }

    /** Resolved offsets/sizes captured from the header view during {@link #probeHeader}. */
    private record RegionLayout(
            long snapshotOff0, long snapshotOff1, long snapshotSize,
            long ringOff, long ringSize, long totalSize) {}

    private static MemorySegment openMappingOrThrow(String name, long pid) {
        MemorySegment mapping = Kernel32.openFileMapping(
                Kernel32.FILE_MAP_READ, false, name);
        if (mapping.address() == 0) {
            int err = Kernel32.getLastError();
            throw new SharedMemoryException(
                    "OpenFileMappingW(\"" + name + "\") failed: GetLastError=" + err
                            + " (is the DLL injected into pid " + pid + "?)");
        }
        return mapping;
    }

    /**
     * Map only the header, validate magic / version / target pid, read the
     * snapshot and ring layout, then drop the header view so the caller can
     * remap the full region in one shot (pointers stay stable for life).
     */
    private static RegionLayout probeHeader(MemorySegment mapping, long pid) {
        MemorySegment headerView = Kernel32.mapViewOfFile(
                mapping, Kernel32.FILE_MAP_READ, 0, Layout.HEADER_SIZE);
        if (headerView.address() == 0) {
            int err = Kernel32.getLastError();
            Kernel32.closeHandle(mapping);
            throw new SharedMemoryException(
                    "MapViewOfFile(header) failed: GetLastError=" + err);
        }
        MemorySegment hdrSlice = headerView.reinterpret(Layout.HEADER_SIZE);

        RegionLayout layout;
        try {
            validateMagicAndVersion(hdrSlice);
            validateTargetPid(hdrSlice, pid);

            long snapshotSize = Integer.toUnsignedLong(hdrSlice.get(ValueLayout.JAVA_INT, Layout.HEADER_SNAPSHOTSIZE_OFFSET));
            long snapshotOff0 = Integer.toUnsignedLong(hdrSlice.get(ValueLayout.JAVA_INT, Layout.HEADER_SNAPSHOTOFF0_OFFSET));
            long snapshotOff1 = Integer.toUnsignedLong(hdrSlice.get(ValueLayout.JAVA_INT, Layout.HEADER_SNAPSHOTOFF1_OFFSET));
            long ringOff      = Integer.toUnsignedLong(hdrSlice.get(ValueLayout.JAVA_INT, Layout.HEADER_RINGOFF_OFFSET));
            long ringSize     = Integer.toUnsignedLong(hdrSlice.get(ValueLayout.JAVA_INT, Layout.HEADER_RINGSIZE_OFFSET));
            long totalSize    = ringOff + ringSize;

            validateGeometry(snapshotSize, snapshotOff0, snapshotOff1, ringOff, ringSize, totalSize);
            layout = new RegionLayout(snapshotOff0, snapshotOff1, snapshotSize, ringOff, ringSize, totalSize);
        } catch (RuntimeException ex) {
            Kernel32.unmapViewOfFile(headerView);
            Kernel32.closeHandle(mapping);
            throw ex;
        }

        Kernel32.unmapViewOfFile(headerView);
        return layout;
    }

    private static MemorySegment mapFullRegion(MemorySegment mapping, long totalSize) {
        MemorySegment fullView = Kernel32.mapViewOfFile(
                mapping, Kernel32.FILE_MAP_READ, 0, totalSize);
        if (fullView.address() == 0) {
            int err = Kernel32.getLastError();
            Kernel32.closeHandle(mapping);
            throw new SharedMemoryException(
                    "MapViewOfFile(full=" + totalSize + ") failed: GetLastError=" + err);
        }
        return fullView.reinterpret(totalSize);
    }

    private static void populateSlices(SharedRegion r, MemorySegment fullSlice, RegionLayout layout) {
        r.header    = fullSlice.asSlice(0, Layout.HEADER_SIZE);
        r.snapshot0 = fullSlice.asSlice(layout.snapshotOff0(), layout.snapshotSize());
        r.snapshot1 = fullSlice.asSlice(layout.snapshotOff1(), layout.snapshotSize());
        r.ring      = fullSlice.asSlice(layout.ringOff(),      layout.ringSize());
    }

    /** Upper bound on a plausible total mapping size (guards a bogus huge ringOff+ringSize). */
    private static final long MAX_REGION_BYTES = 64L * 1024 * 1024;

    /**
     * Rejects a mapping whose producer-published geometry is inconsistent with
     * the layout this host was compiled against. The header passed magic /
     * version / pid, but a buggy or hostile producer can still publish offsets
     * and sizes that would make the per-tick {@code asSlice} reads run off the
     * mapping. Panama bounds-checks each slice, so the failure mode is a thrown
     * exception (a DoS), not a foreign-memory read — but we surface it loudly at
     * bind time rather than on every snapshot read. Snapshot sizes use
     * {@code >=} because the producer pads each snapshot up to a 64-byte stride,
     * so the published size is at least the host's compiled {@code SNAPSHOT_SIZE}.
     */
    // Package-private (not private) so SharedRegionGeometryTest can exercise it
    // directly with crafted values, mirroring validateTargetPid.
    static void validateGeometry(long snapshotSize, long snapshotOff0,
            long snapshotOff1, long ringOff, long ringSize, long totalSize) {
        if (totalSize <= 0 || totalSize > MAX_REGION_BYTES) {
            throw new SharedMemoryException(
                    "Implausible region size: " + totalSize + " bytes (cap " + MAX_REGION_BYTES + ")");
        }
        if (snapshotSize < Layout.SNAPSHOT_SIZE) {
            throw new SharedMemoryException(
                    "Snapshot too small: producer=" + snapshotSize
                            + " consumer needs >=" + Layout.SNAPSHOT_SIZE);
        }
        long minRing = Layout.RING_SLOTS_OFFSET
                + (long) Layout.EVENT_RING_SLOTS * Layout.EVENT_SLOT_SIZE;
        if (ringSize < minRing) {
            throw new SharedMemoryException(
                    "Ring too small: producer=" + ringSize + " consumer needs >=" + minRing);
        }
        if (snapshotOff0 + snapshotSize > totalSize || snapshotOff1 + snapshotSize > totalSize) {
            throw new SharedMemoryException(
                    "Snapshot slice out of bounds: off0=" + snapshotOff0 + " off1=" + snapshotOff1
                            + " snapSize=" + snapshotSize + " total=" + totalSize);
        }
        if (overlaps(snapshotOff0, snapshotSize, snapshotOff1, snapshotSize)
                || overlaps(snapshotOff0, snapshotSize, ringOff, ringSize)
                || overlaps(snapshotOff1, snapshotSize, ringOff, ringSize)) {
            throw new SharedMemoryException(
                    "Overlapping regions: off0=" + snapshotOff0 + " off1=" + snapshotOff1
                            + " ringOff=" + ringOff);
        }
    }

    private static boolean overlaps(long aOff, long aLen, long bOff, long bLen) {
        return aOff < bOff + bLen && bOff < aOff + aLen;
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

    /**
     * Rejects a mapping whose embedded {@code targetPid} doesn't match the pid
     * we asked for. The producer writes {@code GetCurrentProcessId()} (the
     * injected game's pid) into the header, which must equal the pid in the
     * {@code Local\nxt_snapshot_<pid>} name. A mismatch means the mapping is
     * stale or doesn't belong to the requested process. This is a consistency
     * guard, not a proof of producer identity — a local process can forge the
     * field; verifying the section owner's SID would be required for that.
     */
    static void validateTargetPid(MemorySegment header, long pid) {
        long targetPid = header.get(ValueLayout.JAVA_LONG, Layout.HEADER_TARGETPID_OFFSET);
        if (targetPid != pid) {
            throw new SharedMemoryException(
                    "Target pid mismatch: header targetPid=" + targetPid
                            + " requested pid=" + pid
                            + " (stale or foreign mapping)");
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
        if (closed) {
            return;
        }
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
