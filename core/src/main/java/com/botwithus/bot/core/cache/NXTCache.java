package com.botwithus.bot.core.cache;

import com.botwithus.bot.api.model.EnumType;
import com.botwithus.bot.api.model.ItemType;
import com.botwithus.bot.api.model.LocationType;
import com.botwithus.bot.api.model.NpcType;
import com.botwithus.bot.api.model.QuestType;
import com.botwithus.bot.api.model.SequenceType;
import com.botwithus.bot.api.model.StructType;
import com.botwithus.bot.core.loader.bootstrap.NativeCache;
import com.botwithus.bot.core.util.Throwables;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Java wrapper around the NXTCache C ABI ({@code NXTCache.dll}).
 *
 * <p>Thin Panama/FFM binding over the 23-symbol surface defined in
 * {@code NXTCacheLibrary/src/c_api/nxtcache_c.h}. Loads the native
 * library on first use and exposes typed config-type lookups returning
 * the same record types declared in {@code com.botwithus.bot.api.model}.</p>
 *
 * <h2>Loading the DLL</h2>
 * The library is resolved by {@code -Dnxtcache.dll=<absolute path>}
 * via Panama's {@link SymbolLookup#libraryLookup}. There is no
 * {@code System.loadLibrary} fallback — java-rules §Banned 2
 * (JNI / native code) rules out {@code loadLibrary} for project code;
 * Panama is the supported path for FFI.
 *
 * <h2>Required JVM flags</h2>
 * The CLI {@code run} task must pass:
 * <pre>--enable-native-access=com.botwithus.bot.core</pre>
 * Without it, the first downcall throws an
 * {@link IllegalCallerException}.
 *
 * <h2>Threading</h2>
 * A single instance is <b>not</b> safe for concurrent use — the
 * underlying handle wraps a sqlite connection. Serialize calls
 * externally or open one handle per worker thread.
 */
public final class NXTCache implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NXTCache.class);

    public static final int NXT_OK            = 0;
    public static final int NXT_ERR_INVALID   = 1;
    public static final int NXT_ERR_NOT_FOUND = 2;
    public static final int NXT_ERR_DECODE    = 3;
    public static final int NXT_ERR_IO        = 4;
    public static final int NXT_ERR_INTERNAL  = 5;

    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIB = locateLibrary();

    private static final String NXTCACHE_DLL_NAME = "NXTCache.dll";

    private static SymbolLookup locateLibrary() {
        Path resolved = resolveDllPath();
        Arena scope = Arena.ofShared();
        return SymbolLookup.libraryLookup(resolved, scope);
    }

    private static Path resolveDllPath() {
        String override = System.getProperty("nxtcache.dll");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        Path cached = new NativeCache().resolve(NXTCACHE_DLL_NAME);
        if (Files.isRegularFile(cached)) {
            log.debug("Using {} from native cache: {}", NXTCACHE_DLL_NAME, cached);
            return cached;
        }
        throw new IllegalStateException(
                "NXTCache binary not located. Set -Dnxtcache.dll=<path>, "
                        + "or let the native bootstrap download it into "
                        + "~/.botwithus/native/" + NXTCACHE_DLL_NAME
                        + " (no System.loadLibrary fallback — see java-rules §Banned 2).");
    }

    private static MethodHandle dl(String name, FunctionDescriptor fd) {
        MemorySegment sym = LIB.find(name).orElseThrow(
                () -> new UnsatisfiedLinkError("NXTCache: missing symbol " + name));
        return LINKER.downcallHandle(sym, fd);
    }

    /** Signature shared by every {@code nxt_get_<type>_json}: (cache*, int id, char** out, size_t* len) -> int. */
    private static FunctionDescriptor jsonFd() {
        return FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS);
    }

    private static final MethodHandle MH_OPEN_LOCAL =
            dl("nxt_cache_open_local", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle MH_OPEN_LIVE =
            dl("nxt_cache_open_live", FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle MH_ENABLE_FALLBACK =
            dl("nxt_cache_enable_live_fallback", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle MH_CLOSE =
            dl("nxt_cache_close", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle MH_LAST_ERROR =
            dl("nxt_last_error", FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle MH_FREE =
            dl("nxt_free", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle MH_READ_FILE_RAW =
            dl("nxt_read_file_raw", FunctionDescriptor.of(
                    JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle MH_GET_JSON_DISPATCH =
            dl("nxt_get_json", FunctionDescriptor.of(
                    JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle MH_DUMP_ALL =
            dl("nxt_dump_all_json", FunctionDescriptor.of(
                    JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));

    private static final MethodHandle MH_NPC      = dl("nxt_get_npc_json",      jsonFd());
    private static final MethodHandle MH_ITEM     = dl("nxt_get_item_json",     jsonFd());
    private static final MethodHandle MH_LOC      = dl("nxt_get_loc_json",      jsonFd());
    private static final MethodHandle MH_SEQ      = dl("nxt_get_seq_json",      jsonFd());
    private static final MethodHandle MH_VARBIT   = dl("nxt_get_varbit_json",   jsonFd());
    private static final MethodHandle MH_ENUM     = dl("nxt_get_enum_json",     jsonFd());
    private static final MethodHandle MH_STRUCT   = dl("nxt_get_struct_json",   jsonFd());
    private static final MethodHandle MH_INV      = dl("nxt_get_inv_json",      jsonFd());
    private static final MethodHandle MH_PARAM    = dl("nxt_get_param_json",    jsonFd());
    private static final MethodHandle MH_QUEST    = dl("nxt_get_quest_json",    jsonFd());
    private static final MethodHandle MH_UNDERLAY = dl("nxt_get_underlay_json", jsonFd());
    private static final MethodHandle MH_OVERLAY  = dl("nxt_get_overlay_json",  jsonFd());
    private static final MethodHandle MH_WORLDMAP = dl("nxt_get_worldmap_json", jsonFd());
    private static final MethodHandle MH_DBROW    = dl("nxt_get_dbrow_json",    jsonFd());

    private final MemorySegment handle;
    private volatile boolean closed;

    private NXTCache(MemorySegment handle) {
        this.handle = handle;
    }

    // ---------------------------------------------------------- Lifecycle

    /**
     * Opens a cache configured from system properties:
     * <ul>
     *   <li>{@code -Dnxtcache.path=<dir>} — open the local sqlite cache at
     *       this directory and enable live-JS5 fallback. Recommended.</li>
     *   <li>{@code -Dnxtcache.live=true} — open a live-only cache (no local
     *       sqlite). Slower but always current.</li>
     * </ul>
     * Returns {@code null} when neither is set, so callers can degrade
     * gracefully. Throws on actual open failure.
     */
    public static NXTCache tryOpenFromSystemProperty() throws IOException {
        String path = System.getProperty("nxtcache.path");
        if (path != null && !path.isBlank()) {
            NXTCache c = openLocal(Path.of(path));
            try {
                c.enableLiveFallback();
            } catch (IOException e) {
                // Fallback is best-effort — the local cache is still usable
                // for entries it has. Swallow but leave a trail.
            }
            return c;
        }
        if (Boolean.getBoolean("nxtcache.live")) {
            return openLive();
        }
        return null;
    }

    /** Opens a sqlite-backed cache from the given directory. */
    public static NXTCache openLocal(Path cachePath) throws IOException {
        Objects.requireNonNull(cachePath);
        try (Arena tmp = Arena.ofConfined()) {
            MemorySegment cstr = tmp.allocateFrom(cachePath.toString());
            MemorySegment ptr;
            try {
                ptr = (MemorySegment) MH_OPEN_LOCAL.invokeExact(cstr);
            } catch (Throwable t) {
                throw rethrow(t);
            }
            if (ptr.address() == 0) {
                throw new IOException("nxt_cache_open_local failed: " + lastError());
            }
            return new NXTCache(ptr);
        }
    }

    /** Opens a live JS5-backed cache; performs jav_config + handshake. */
    public static NXTCache openLive() throws IOException {
        MemorySegment ptr;
        try {
            ptr = (MemorySegment) MH_OPEN_LIVE.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
        if (ptr.address() == 0) {
            throw new IOException("nxt_cache_open_live failed: " + lastError());
        }
        return new NXTCache(ptr);
    }

    /** Enables transparent live-JS5 fallback for misses on a local cache. */
    public void enableLiveFallback() throws IOException {
        ensureOpen();
        int rc;
        try {
            rc = (int) MH_ENABLE_FALLBACK.invokeExact(handle);
        } catch (Throwable t) {
            throw rethrow(t);
        }
        if (rc != NXT_OK) {
            throw new IOException("enable_live_fallback rc=" + rc + ": " + lastError());
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            MH_CLOSE.invokeExact(handle);
        } catch (Throwable t) {
            // best-effort; close must not throw
            log.debug("nxt_cache_close threw", t);
        }
    }

    // ------------------------------------------------------ Typed getters

    /** @return decoded item, or {@code null} if no entry with this id. */
    public ItemType getItem(int id) {
        String json = getItemJson(id);
        return json == null ? null : NXTCacheMapper.toItemType(parseObject(json));
    }

    public NpcType getNpc(int id) {
        String json = getNpcJson(id);
        return json == null ? null : NXTCacheMapper.toNpcType(parseObject(json));
    }

    public LocationType getLocation(int id) {
        String json = getLocJson(id);
        return json == null ? null : NXTCacheMapper.toLocationType(parseObject(json));
    }

    public EnumType getEnum(int id) {
        String json = getEnumJson(id);
        return json == null ? null : NXTCacheMapper.toEnumType(parseObject(json));
    }

    public StructType getStruct(int id) {
        String json = getStructJson(id);
        return json == null ? null : NXTCacheMapper.toStructType(parseObject(json));
    }

    public SequenceType getSequence(int id) {
        String json = getSeqJson(id);
        return json == null ? null : NXTCacheMapper.toSequenceType(parseObject(json));
    }

    public QuestType getQuest(int id) {
        String json = getQuestJson(id);
        return json == null ? null : NXTCacheMapper.toQuestType(parseObject(json));
    }

    // ------------------------------------------------- Raw JSON getters
    // For types without a dedicated record on the Java side. Each returns
    // a UTF-8 JSON string matching the nxtcache-dumper output, or null
    // when the id doesn't resolve.

    public String getItemJson(int id)     { return jsonOrNull(MH_ITEM,     id); }
    public String getNpcJson(int id)      { return jsonOrNull(MH_NPC,      id); }
    public String getLocJson(int id)      { return jsonOrNull(MH_LOC,      id); }
    public String getSeqJson(int id)      { return jsonOrNull(MH_SEQ,      id); }
    public String getVarbitJson(int id)   { return jsonOrNull(MH_VARBIT,   id); }
    public String getEnumJson(int id)     { return jsonOrNull(MH_ENUM,     id); }
    public String getStructJson(int id)   { return jsonOrNull(MH_STRUCT,   id); }
    public String getInvJson(int id)      { return jsonOrNull(MH_INV,      id); }
    public String getParamJson(int id)    { return jsonOrNull(MH_PARAM,    id); }
    public String getQuestJson(int id)    { return jsonOrNull(MH_QUEST,    id); }
    public String getUnderlayJson(int id) { return jsonOrNull(MH_UNDERLAY, id); }
    public String getOverlayJson(int id)  { return jsonOrNull(MH_OVERLAY,  id); }
    public String getWorldmapJson(int id) { return jsonOrNull(MH_WORLDMAP, id); }
    public String getDbrowJson(int id)    { return jsonOrNull(MH_DBROW,    id); }

    /** Generic by-name dispatch — useful for wiring up new types without recompiling. */
    public String getJson(String typeName, int id) {
        ensureOpen();
        try (Arena tmp = Arena.ofConfined()) {
            MemorySegment type = tmp.allocateFrom(typeName);
            MemorySegment outPtr = tmp.allocate(ADDRESS);
            MemorySegment outLen = tmp.allocate(JAVA_LONG);
            int rc = (int) MH_GET_JSON_DISPATCH.invokeExact(handle, type, id, outPtr, outLen);
            if (rc == NXT_ERR_NOT_FOUND) {
                return null;
            }
            if (rc != NXT_OK) {
                throw new NXTCacheException("get_json(" + typeName + ", " + id + ") rc=" + rc + ": " + lastError());
            }
            return readAndFree(outPtr, outLen);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Bulk dump as a JSON array. {@code limit < 0} means unbounded. */
    public String dumpAllJson(String typeName, int limit) {
        ensureOpen();
        try (Arena tmp = Arena.ofConfined()) {
            MemorySegment type = tmp.allocateFrom(typeName);
            MemorySegment outPtr = tmp.allocate(ADDRESS);
            MemorySegment outLen = tmp.allocate(JAVA_LONG);
            int rc = (int) MH_DUMP_ALL.invokeExact(handle, type, limit, outPtr, outLen);
            if (rc != NXT_OK) {
                throw new NXTCacheException("dump_all_json(" + typeName + ") rc=" + rc + ": " + lastError());
            }
            return readAndFree(outPtr, outLen);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Read raw bytes for a single (idx, archive, file) triple. */
    public byte[] readFileRaw(int indexId, int archiveId, int fileId) {
        ensureOpen();
        try (Arena tmp = Arena.ofConfined()) {
            MemorySegment outPtr = tmp.allocate(ADDRESS);
            MemorySegment outLen = tmp.allocate(JAVA_LONG);
            int rc = (int) MH_READ_FILE_RAW.invokeExact(
                    handle, indexId, archiveId, fileId, outPtr, outLen);
            if (rc == NXT_ERR_NOT_FOUND) {
                return null;
            }
            if (rc != NXT_OK) {
                throw new NXTCacheException("read_file_raw rc=" + rc + ": " + lastError());
            }
            MemorySegment buf = outPtr.get(ADDRESS, 0);
            long len = outLen.get(JAVA_LONG, 0);
            try {
                return buf.reinterpret(len).toArray(JAVA_BYTE);
            } finally {
                MH_FREE.invokeExact(buf);
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // -------------------------------------------------------- Internals

    private String jsonOrNull(MethodHandle mh, int id) {
        ensureOpen();
        try (Arena tmp = Arena.ofConfined()) {
            MemorySegment outPtr = tmp.allocate(ADDRESS);
            MemorySegment outLen = tmp.allocate(JAVA_LONG);
            int rc = (int) mh.invokeExact(handle, id, outPtr, outLen);
            if (rc == NXT_ERR_NOT_FOUND) {
                return null;
            }
            if (rc != NXT_OK) {
                throw new NXTCacheException("nxt_get_*_json rc=" + rc + ": " + lastError());
            }
            return readAndFree(outPtr, outLen);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static String readAndFree(MemorySegment outPtr, MemorySegment outLen) throws Throwable {
        MemorySegment buf = outPtr.get(ADDRESS, 0);
        long len = outLen.get(JAVA_LONG, 0);
        try {
            byte[] bytes = buf.reinterpret(len).toArray(JAVA_BYTE);
            return new String(bytes, StandardCharsets.UTF_8);
        } finally {
            MH_FREE.invokeExact(buf);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("NXTCache handle is closed");
        }
    }

    private static Map<String, Object> parseObject(String json) {
        return GSON.fromJson(json, MAP_TYPE);
    }

    private static String lastError() {
        try {
            MemorySegment p = (MemorySegment) MH_LAST_ERROR.invokeExact();
            if (p.address() == 0) {
                return "";
            }
            return p.reinterpret(Long.MAX_VALUE).getString(0);
        } catch (Throwable t) {
            return "<lastError unavailable: " + t + ">";
        }
    }

    private static RuntimeException rethrow(Throwable t) {
        return Throwables.rethrow(t, cause -> new NXTCacheException("NXTCache invocation failed", cause));
    }
}
