package com.botwithus.bot.core.cache;

import com.botwithus.bot.api.model.EnumType;
import com.botwithus.bot.api.model.ItemType;
import com.botwithus.bot.api.model.LocationType;
import com.botwithus.bot.api.model.NpcType;
import com.botwithus.bot.api.model.QuestType;
import com.botwithus.bot.api.model.SequenceType;
import com.botwithus.bot.api.model.StructType;
import com.botwithus.bot.api.model.VarbitType;
import com.botwithus.bot.core.util.NativeCache;
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
 * The native handle is <b>not</b> safe for concurrent use (it wraps a sqlite
 * connection and decoder state), but this class is: one instance is shared by
 * every connection and script thread, so every call on the handle runs under
 * one lock per instance ({@link HandleGuard}), and {@link #close()} takes the
 * same lock. Callers need no external synchronisation.
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

    /** Upper bound on a single native-returned blob; guards a garbage length from SIGSEGV-ing the JVM. */
    private static final long MAX_NATIVE_BLOB_BYTES = 256L * 1024 * 1024;
    /** Cap on the thread-local last-error C string scan (truncates a non-terminated buffer instead of faulting). */
    private static final long MAX_LAST_ERROR_BYTES = 4096;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIB = locateLibrary();

    private static SymbolLookup locateLibrary() {
        Path resolved = NativeCache.verifyIntegrity(resolveDllPath());
        log.debug("Loading {} from {}", NativeCache.NXTCACHE_DLL_NAME, resolved);
        Arena scope = Arena.ofShared();
        return SymbolLookup.libraryLookup(resolved, scope);
    }

    private static Path resolveDllPath() {
        return NativeCache.locateNxtCacheDll().orElseThrow(() -> new IllegalStateException(
                "NXTCache binary not located. Set -Dnxtcache.dll=<path>, "
                        + "or let the loader populate "
                        + "~/.botwithus/native/" + NativeCache.NXTCACHE_DLL_NAME
                        + " (no System.loadLibrary fallback — see java-rules §Banned 2)."));
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
    private final HandleGuard guard;

    private NXTCache(MemorySegment handle, HandleGuard guard) {
        this.handle = handle;
        this.guard = guard;
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
     *
     * <p>This is the properties-only half, and stays that way. Host wiring
     * wants {@link #openForHost()}, which uses this for the override leg and
     * then discovers or falls back instead of returning {@code null}.</p>
     */
    public static NXTCache tryOpenFromSystemProperty() throws IOException {
        String path = System.getProperty(CacheSourceResolver.PATH_PROPERTY);
        if (path != null && !path.isBlank()) {
            return openLocalWithFallback(Path.of(path));
        }
        if (Boolean.getBoolean(CacheSourceResolver.LIVE_PROPERTY)) {
            return openLive();
        }
        return null;
    }

    /**
     * Opens the cache a host process should run against, without requiring
     * anyone to pass a {@code -D} flag. Never returns {@code null}: an
     * explicit override wins, else a discovered NXT client cache directory,
     * else the live JS5 service. See {@link CacheSourceResolver} for the
     * precedence and for why a discovered directory has to hold index files
     * rather than merely exist.
     *
     * <p>This is the entry point host wiring should call.
     * {@link #tryOpenFromSystemProperty()} remains what its name says — the
     * properties-only half — and is what this delegates to for the override
     * leg. Widening that method in place would have made a {@code null} return
     * mean "no override" to one caller and "no cache anywhere" to another,
     * which is the kind of ambiguity a return type cannot carry.</p>
     *
     * <p>The live fallback is deliberately silent to the script: a network
     * dependency and slower lookups beat every config-type lookup throwing.
     * It is logged at {@code info} so the mode is legible in a log we are
     * sent, and never at {@code error} — it is a working state.</p>
     *
     * @throws IOException if the resolved source cannot be opened. An explicit
     *         override that names a bad directory fails here rather than
     *         degrading, so the operator sees their own typo.
     */
    public static NXTCache openForHost() throws IOException {
        return open(new CacheSourceResolver().resolve());
    }

    /**
     * Opens the cache for a host process that knows which client it is serving.
     * Same precedence as {@link #openForHost()} with one tier inserted: after
     * the {@code -D} overrides and before the known install locations, the
     * client running as {@code clientPid} is asked where its own cache is. That
     * is the only answer that stays correct for a user who relocated their
     * cache, since {@code cache_folder} is a preference nothing recomputes once
     * moved.
     *
     * <p>An overload rather than a widened signature, and rather than a resolver
     * that finds the pid itself. Both host call sites already hold the pid as a
     * local — they parsed it out of the agent's pipe name to open the shared
     * memory — so passing it costs nothing, whereas self-discovery would have to
     * re-enumerate pipes and could answer with a <em>different</em> client than
     * the one being connected. A silently wrong cache is worse than no pid.</p>
     *
     * @param clientPid the game client's process id, as carried by the agent's
     *                  pipe and shared-memory names
     * @throws IOException if the resolved source cannot be opened
     */
    public static NXTCache openForHost(long clientPid) throws IOException {
        return open(new CacheSourceResolver(clientPid).resolve());
    }

    /** Opens an already-decided {@link CacheSource}, logging which mode was taken. */
    public static NXTCache open(CacheSource source) throws IOException {
        Objects.requireNonNull(source, "source");
        return switch (source) {
            case CacheSource.LocalDirectory local -> openLocalReporting(local);
            case CacheSource.Live live -> openLiveReporting(live);
        };
    }

    private static NXTCache openLocalReporting(CacheSource.LocalDirectory local) throws IOException {
        NXTCache cache = openLocalWithFallback(local.directory());
        if (local.isExplicit()) {
            log.info("NXTCache: local cache {} named by -D{}",
                    local.directory(), CacheSourceResolver.PATH_PROPERTY);
        } else {
            log.info("NXTCache: discovered the client's local cache at {}", local.directory());
        }
        return cache;
    }

    private static NXTCache openLiveReporting(CacheSource.Live live) throws IOException {
        if (live.isExplicit()) {
            log.info("NXTCache: live JS5 only, named by -D{}", CacheSourceResolver.LIVE_PROPERTY);
        } else {
            log.info("NXTCache: no local NXT cache found; using live JS5 instead. Config-type "
                            + "lookups work, but each archive is fetched over the network. Pass "
                            + "-D{}=<cache dir> to read a local cache instead.",
                    CacheSourceResolver.PATH_PROPERTY);
        }
        return openLive();
    }

    /**
     * Opens a local cache and turns on live-JS5 fallback for archives it does
     * not hold. The fallback is best-effort: a local cache that answers most
     * lookups is worth keeping even when the network leg is unavailable, so a
     * failure there is reported and swallowed rather than failing the open.
     */
    private static NXTCache openLocalWithFallback(Path directory) throws IOException {
        NXTCache cache = openLocal(directory);
        try {
            cache.enableLiveFallback();
        } catch (IOException e) {
            log.info("NXTCache: live-JS5 fallback unavailable for {} ({}); lookups are limited "
                    + "to archives already cached locally", directory, e.getMessage());
        }
        return cache;
    }

    /** Opens a sqlite-backed cache from the given directory. */
    public static NXTCache openLocal(Path cachePath) throws IOException {
        return openLocal(cachePath, new HandleGuard());
    }

    /** As {@link #openLocal(Path)}, with the guard supplied; the test seam for serialisation. */
    static NXTCache openLocal(Path cachePath, HandleGuard guard) throws IOException {
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
            return new NXTCache(ptr, guard);
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
        return new NXTCache(ptr, new HandleGuard());
    }

    /** Enables transparent live-JS5 fallback for misses on a local cache. */
    public void enableLiveFallback() throws IOException {
        String failure;
        try {
            failure = guard.call(() -> {
                int rc = (int) MH_ENABLE_FALLBACK.invokeExact(handle);
                return rc == NXT_OK ? null : "enable_live_fallback rc=" + rc + ": " + lastError();
            });
        } catch (Throwable t) {
            throw rethrow(t);
        }
        if (failure != null) {
            throw new IOException(failure);
        }
    }

    /**
     * Frees the handle once. Safe to race with any other call: one in flight finishes
     * first, and any later call throws {@link IllegalStateException} rather than reaching
     * a freed handle.
     */
    @Override
    public void close() {
        try {
            guard.close(() -> {
                MH_CLOSE.invokeExact(handle);
                return null;
            });
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

    /** @return decoded varbit definition (base var + bit range), or {@code null}. */
    public VarbitType getVarbit(int id) {
        String json = getVarbitJson(id);
        return json == null ? null : NXTCacheMapper.toVarbitType(parseObject(json));
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
        try {
            return guard.call(() -> getJsonLocked(typeName, id));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private String getJsonLocked(String typeName, int id) throws Throwable {
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
        }
    }

    /** Bulk dump as a JSON array. {@code limit < 0} means unbounded. */
    public String dumpAllJson(String typeName, int limit) {
        try {
            return guard.call(() -> dumpAllJsonLocked(typeName, limit));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private String dumpAllJsonLocked(String typeName, int limit) throws Throwable {
        try (Arena tmp = Arena.ofConfined()) {
            MemorySegment type = tmp.allocateFrom(typeName);
            MemorySegment outPtr = tmp.allocate(ADDRESS);
            MemorySegment outLen = tmp.allocate(JAVA_LONG);
            int rc = (int) MH_DUMP_ALL.invokeExact(handle, type, limit, outPtr, outLen);
            if (rc != NXT_OK) {
                throw new NXTCacheException("dump_all_json(" + typeName + ") rc=" + rc + ": " + lastError());
            }
            return readAndFree(outPtr, outLen);
        }
    }

    /** Read raw bytes for a single (idx, archive, file) triple. */
    public byte[] readFileRaw(int indexId, int archiveId, int fileId) {
        try {
            return guard.call(() -> readFileRawLocked(indexId, archiveId, fileId));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private byte[] readFileRawLocked(int indexId, int archiveId, int fileId) throws Throwable {
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
            // Defensive (mirrors WorldWalker.decodePath): a misbehaving native
            // side returning NXT_OK with a null buffer or garbage length would
            // SIGSEGV inside reinterpret-and-read and take the JVM down. Surface
            // a typed exception. buf is non-null past here so the finally frees it.
            if (buf.address() == 0L) {
                throw new NXTCacheException("read_file_raw returned NXT_OK with null buffer (len=" + len + ")");
            }
            try {
                if (len < 0 || len > MAX_NATIVE_BLOB_BYTES) {
                    throw new NXTCacheException("read_file_raw returned implausible length: " + len);
                }
                return buf.reinterpret(len).toArray(JAVA_BYTE);
            } finally {
                MH_FREE.invokeExact(buf);
            }
        }
    }

    // -------------------------------------------------------- Internals

    private String jsonOrNull(MethodHandle mh, int id) {
        try {
            return guard.call(() -> jsonOrNullLocked(mh, id));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private String jsonOrNullLocked(MethodHandle mh, int id) throws Throwable {
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
        }
    }

    private static String readAndFree(MemorySegment outPtr, MemorySegment outLen) throws Throwable {
        MemorySegment buf = outPtr.get(ADDRESS, 0);
        long len = outLen.get(JAVA_LONG, 0);
        if (buf.address() == 0L) {
            throw new NXTCacheException("native read returned NXT_OK with null buffer (len=" + len + ")");
        }
        try {
            if (len < 0 || len > MAX_NATIVE_BLOB_BYTES) {
                throw new NXTCacheException("native read returned implausible length: " + len);
            }
            byte[] bytes = buf.reinterpret(len).toArray(JAVA_BYTE);
            return new String(bytes, StandardCharsets.UTF_8);
        } finally {
            MH_FREE.invokeExact(buf);
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
            return p.reinterpret(MAX_LAST_ERROR_BYTES).getString(0);
        } catch (Throwable t) {
            return "<lastError unavailable: " + t + ">";
        }
    }

    private static RuntimeException rethrow(Throwable t) {
        return Throwables.rethrow(t, cause -> new NXTCacheException("NXTCache invocation failed", cause));
    }
}
