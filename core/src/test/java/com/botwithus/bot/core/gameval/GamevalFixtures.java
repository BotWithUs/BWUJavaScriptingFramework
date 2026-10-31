package com.botwithus.bot.core.gameval;

import com.botwithus.bot.core.cache.GamevalIndexVersion;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Small schema-v1 gameval databases for the local-build tests. Each carries one
 * {@code item} row named {@link #MARKER_NAME} whose id says which file it is, so a
 * test can tell which database a host ended up reading.
 */
final class GamevalFixtures {

    static final String MARKER_NAME = "FIXTURE_MARKER";

    private GamevalFixtures() {
    }

    /**
     * Writes a gameval DB at {@code file}.
     *
     * @param indexVersion the {@code meta.index_version} value, or empty for an unversioned DB
     * @param marker       the id of the marker row
     */
    static Path writeDb(Path file, Optional<String> indexVersion, int marker) {
        try (Connection con = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement st = con.createStatement()) {
            st.executeUpdate("CREATE TABLE gameval (etype TEXT NOT NULL, eid INTEGER NOT NULL, "
                    + "name TEXT NOT NULL, PRIMARY KEY (etype, eid)) WITHOUT ROWID");
            st.executeUpdate("CREATE INDEX idx_gameval_lookup ON gameval(etype, name)");
            st.executeUpdate("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)");
            st.executeUpdate("INSERT INTO meta VALUES ('schema_version', '1'), ('rows', '1'), "
                    + "('groups', '1'), ('built', 'fixture')");
            try (PreparedStatement row = con.prepareStatement(
                    "INSERT INTO gameval VALUES ('item', ?, '" + MARKER_NAME + "')")) {
                row.setInt(1, marker);
                row.executeUpdate();
            }
            if (indexVersion.isPresent()) {
                try (PreparedStatement meta = con.prepareStatement(
                        "INSERT INTO meta VALUES ('" + HostGamevals.INDEX_VERSION_KEY + "', ?)")) {
                    meta.setString(1, indexVersion.get());
                    meta.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("fixture " + file, e);
        }
        return file;
    }

    static String sha256(Path file) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(file)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A {@link GamevalCacheSource} that answers a fixed probe and "builds" a fixture DB
     * stamped with that version and marker {@code buildMarker}.
     */
    static final class FakeSource implements GamevalCacheSource {

        private final GamevalIndexVersion version;
        private final int buildMarker;
        private final boolean isBuildFailing;
        private final List<Path> builds = new CopyOnWriteArrayList<>();
        private final AtomicBoolean isClosed = new AtomicBoolean();

        FakeSource(GamevalIndexVersion version, int buildMarker, boolean isBuildFailing) {
            this.version = version;
            this.buildMarker = buildMarker;
            this.isBuildFailing = isBuildFailing;
        }

        static FakeSource probing(int version, int buildMarker) {
            return new FakeSource(new GamevalIndexVersion.Present(version), buildMarker, false);
        }

        @Override
        public GamevalIndexVersion probe() {
            return version;
        }

        @Override
        public void build(Path out) {
            builds.add(out);
            if (isBuildFailing) {
                throw new IllegalStateException("fixture build failure");
            }
            Optional<String> stamp = switch (version) {
                case GamevalIndexVersion.Present present -> Optional.of(present.metaValue());
                case GamevalIndexVersion.Absent absent -> Optional.empty();
                case GamevalIndexVersion.Failed failed -> Optional.empty();
            };
            writeDb(out, stamp, buildMarker);
        }

        @Override
        public void close() {
            isClosed.set(true);
        }

        List<Path> builds() {
            return builds;
        }

        boolean isClosed() {
            return isClosed.get();
        }
    }
}
