package com.botwithus.bot.core.gameval;

import com.botwithus.bot.api.gameval.GamevalEntry;
import com.botwithus.bot.api.gameval.GamevalIndex;
import com.botwithus.bot.api.gameval.GamevalType;
import com.botwithus.bot.core.util.NativeCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@link GamevalIndex} over the baked {@code gameval.sqlite} — the name index
 * the launcher drops in {@code ~/.botwithus/native/}, produced offline by
 * {@code build_gameval_db.py} from the game's own index-67 tables.
 *
 * <p>One process-wide instance is shared by every connected client; construct it
 * at the composition root and pass it to each {@code GameAPIImpl}. Lookups are
 * lazy and memoised: the file holds ~814k names and eagerly loading them would
 * retain a couple of hundred megabytes, whereas a script touches a few dozen. A
 * miss is cached as readily as a hit, because scripts poll names in loops.</p>
 *
 * <p>Safe for concurrent use. One JDBC connection is shared under a lock, which
 * the memo keeps almost entirely uncontended after warm-up; an uncached lookup
 * is a native call that pins a virtual thread's carrier for its duration.</p>
 */
public final class SqliteGamevalIndex implements GamevalIndex {

    /** Schema this reader understands; {@code meta.schema_version} must match. */
    private static final String SUPPORTED_SCHEMA_VERSION = "1";

    private static final String SQL_ID =
            "SELECT eid FROM gameval WHERE etype = ? AND name = ? LIMIT 1";
    private static final String SQL_NAME =
            "SELECT name FROM gameval WHERE etype = ? AND eid = ?";
    private static final String SQL_PREFIX =
            "SELECT name, eid FROM gameval WHERE etype = ? AND name >= ? AND name < ? "
                    + "ORDER BY name LIMIT ?";
    private static final String SQL_TYPE_ALL =
            "SELECT name, eid FROM gameval WHERE etype = ? ORDER BY name LIMIT ?";
    private static final String SQL_META = "SELECT key, value FROM meta";

    private static final Logger log = LoggerFactory.getLogger(SqliteGamevalIndex.class);

    private record NameKey(GamevalType type, String gameval) {
    }

    private record IdKey(GamevalType type, int id) {
    }

    private final Connection con;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<NameKey, OptionalInt> idMemo = new ConcurrentHashMap<>();
    private final Map<IdKey, Optional<String>> nameMemo = new ConcurrentHashMap<>();
    private final Map<String, String> meta;

    private SqliteGamevalIndex(Connection con, Map<String, String> meta) {
        this.con = con;
        this.meta = meta;
    }

    /**
     * Open the index from its default location, or empty when no file is
     * deployed. Precedence is {@code -Dbotwithus.gameval} then the native cache
     * entry — see {@link NativeCache#locateGamevalDb()}.
     */
    public static Optional<SqliteGamevalIndex> openDefault() {
        return NativeCache.locateGamevalDb().map(SqliteGamevalIndex::open);
    }

    /**
     * Open a specific index file read-only.
     *
     * @throws GamevalIndexException when the file cannot be opened or its schema
     *                               version is not {@value #SUPPORTED_SCHEMA_VERSION}
     */
    public static SqliteGamevalIndex open(Path dbFile) {
        SQLiteConfig cfg = new SQLiteConfig();
        cfg.setReadOnly(true);
        SQLiteDataSource ds = new SQLiteDataSource(cfg);
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        Connection con = null;
        try {
            con = ds.getConnection();
            Map<String, String> meta = readMeta(con);
            String version = meta.get("schema_version");
            if (!SUPPORTED_SCHEMA_VERSION.equals(version)) {
                throw new GamevalIndexException("gameval index at " + dbFile + " has schema_version "
                        + version + "; this host reads " + SUPPORTED_SCHEMA_VERSION
                        + " — rebuild it with build_gameval_db.py");
            }
            log.info("gameval index {} ({} names across {} types, built {})",
                    dbFile, meta.get("rows"), meta.get("groups"), meta.get("built"));
            return new SqliteGamevalIndex(con, meta);
        } catch (SQLException e) {
            closeQuietly(con);
            throw new GamevalIndexException("failed to open gameval index at " + dbFile, e);
        } catch (GamevalIndexException e) {
            closeQuietly(con);
            throw e;
        }
    }

    private static Map<String, String> readMeta(Connection con) throws SQLException {
        Map<String, String> out = new HashMap<>();
        try (PreparedStatement ps = con.prepareStatement(SQL_META);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String value = rs.getString(2);
                if (value != null) {
                    out.put(rs.getString(1), value);
                }
            }
        }
        return Map.copyOf(out);
    }

    private static void closeQuietly(Connection con) {
        if (con == null) {
            return;
        }
        try {
            con.close();
        } catch (SQLException e) {
            log.debug("gameval index close failed", e);
        }
    }

    @Override
    public OptionalInt id(GamevalType type, String gameval) {
        NameKey key = new NameKey(type, gameval.toUpperCase(Locale.ROOT));
        OptionalInt cached = idMemo.get(key);
        if (cached != null) {
            return cached;
        }
        OptionalInt resolved = queryId(key);
        idMemo.put(key, resolved);
        return resolved;
    }

    private OptionalInt queryId(NameKey key) {
        lock.lock();
        try (PreparedStatement ps = con.prepareStatement(SQL_ID)) {
            ps.setString(1, key.type().wire());
            ps.setString(2, key.gameval());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? OptionalInt.of(rs.getInt(1)) : OptionalInt.empty();
            }
        } catch (SQLException e) {
            throw new GamevalIndexException(
                    "id(" + key.type().wire() + ", " + key.gameval() + ")", e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<String> gameval(GamevalType type, int id) {
        IdKey key = new IdKey(type, id);
        Optional<String> cached = nameMemo.get(key);
        if (cached != null) {
            return cached;
        }
        Optional<String> resolved = queryName(key);
        nameMemo.put(key, resolved);
        return resolved;
    }

    private Optional<String> queryName(IdKey key) {
        lock.lock();
        try (PreparedStatement ps = con.prepareStatement(SQL_NAME)) {
            ps.setString(1, key.type().wire());
            ps.setInt(2, key.id());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new GamevalIndexException(
                    "gameval(" + key.type().wire() + ", " + key.id() + ")", e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Prefix scan. Deliberately not memoised — results are unbounded and callers
     * are tooling, not hot script loops. The bounded range keeps the query on
     * {@code idx_gameval_lookup}; a {@code LIKE} would scan the whole type.
     */
    @Override
    public List<GamevalEntry> startingWith(GamevalType type, String prefix, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        String needle = prefix.toUpperCase(Locale.ROOT);
        String bound = needle.isEmpty() ? null : exclusiveUpperBound(needle);
        lock.lock();
        try (PreparedStatement ps = con.prepareStatement(bound == null ? SQL_TYPE_ALL : SQL_PREFIX)) {
            ps.setString(1, type.wire());
            if (bound == null) {
                ps.setInt(2, limit);
            } else {
                ps.setString(2, needle);
                ps.setString(3, bound);
                ps.setInt(4, limit);
            }
            return readEntries(ps, type);
        } catch (SQLException e) {
            throw new GamevalIndexException(
                    "startingWith(" + type.wire() + ", " + prefix + ")", e);
        } finally {
            lock.unlock();
        }
    }

    private static List<GamevalEntry> readEntries(PreparedStatement ps, GamevalType type)
            throws SQLException {
        List<GamevalEntry> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new GamevalEntry(type, rs.getInt(2), rs.getString(1)));
            }
        }
        return List.copyOf(out);
    }

    /**
     * Smallest string ordered after every string starting with {@code prefix},
     * i.e. the exclusive upper bound of the prefix range. Null when the prefix
     * ends at the top of the code-point space and no such bound exists — gameval
     * names are ASCII, so that never happens in practice.
     */
    private static String exclusiveUpperBound(String prefix) {
        int last = prefix.length() - 1;
        char tail = prefix.charAt(last);
        if (tail == Character.MAX_VALUE) {
            return null;
        }
        return prefix.substring(0, last) + (char) (tail + 1);
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public Optional<String> meta(String key) {
        return Optional.ofNullable(meta.get(key));
    }

    @Override
    public void close() {
        lock.lock();
        try {
            closeQuietly(con);
        } finally {
            lock.unlock();
        }
    }
}
