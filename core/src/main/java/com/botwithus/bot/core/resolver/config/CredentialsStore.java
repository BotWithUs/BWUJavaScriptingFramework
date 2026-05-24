package com.botwithus.bot.core.resolver.config;

import com.botwithus.bot.core.resolver.Credentials;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Persists per-repository basic-auth credentials at
 * {@code ~/.botwithus/credentials.json}.
 *
 * <p>The file is hardened on first save: on POSIX it gets mode {@code 600}
 * (owner read/write only), on Windows the ACL is replaced with a single
 * full-control entry for the current user. Hardening failures are logged
 * at WARN but do not block startup — the user can still operate the CLI,
 * they just see one warning.</p>
 */
public final class CredentialsStore {

    private static final Logger log = LoggerFactory.getLogger(CredentialsStore.class);
    private static final String TMP_SUFFIX = ".tmp";

    public static final Path DEFAULT_PATH = Path.of(System.getProperty("user.home"), ".botwithus", "credentials.json");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Credentials>>() {}.getType();

    private final Path file;
    private final Map<String, Credentials> byRepoId = new LinkedHashMap<>();

    public CredentialsStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    public synchronized void load() throws IOException {
        byRepoId.clear();
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            Map<String, Credentials> loaded = GSON.fromJson(reader, MAP_TYPE);
            if (loaded != null) {
                byRepoId.putAll(loaded);
            }
        } catch (JsonIOException | JsonSyntaxException e) {
            throw new IOException("malformed " + file + ": " + e.getMessage(), e);
        }
    }

    public synchronized void save() throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = (parent != null ? parent : Path.of(".")).resolve(file.getFileName() + TMP_SUFFIX);
        try (Writer writer = Files.newBufferedWriter(tmp)) {
            GSON.toJson(byRepoId, MAP_TYPE, writer);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException atomicFailed) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        hardenPermissions(file);
    }

    public synchronized Optional<Credentials> lookup(String repoId) {
        return Optional.ofNullable(byRepoId.get(Objects.requireNonNull(repoId, "repoId")));
    }

    public synchronized void put(String repoId, Credentials credentials) {
        Objects.requireNonNull(repoId, "repoId");
        Objects.requireNonNull(credentials, "credentials");
        byRepoId.put(repoId, credentials);
    }

    public synchronized boolean remove(String repoId) {
        return byRepoId.remove(Objects.requireNonNull(repoId, "repoId")) != null;
    }

    public synchronized Set<String> repoIds() {
        return Set.copyOf(byRepoId.keySet());
    }

    static void hardenPermissions(Path target) {
        try {
            PosixFileAttributeView posix = Files.getFileAttributeView(target, PosixFileAttributeView.class);
            if (posix != null) {
                posix.setPermissions(EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE));
                return;
            }
        } catch (IOException e) {
            log.warn("could not apply POSIX 600 to {}: {}", target, e.getMessage());
            return;
        }

        try {
            AclFileAttributeView acl = Files.getFileAttributeView(target, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (acl == null) {
                log.warn("no ACL view available on {}; credentials file permissions not hardened", target);
                return;
            }
            UserPrincipal owner = Files.getOwner(target);
            AclEntry ownerFull = AclEntry.newBuilder()
                    .setPrincipal(owner)
                    .setType(AclEntryType.ALLOW)
                    .setPermissions(
                            AclEntryPermission.READ_DATA,
                            AclEntryPermission.WRITE_DATA,
                            AclEntryPermission.APPEND_DATA,
                            AclEntryPermission.READ_ATTRIBUTES,
                            AclEntryPermission.WRITE_ATTRIBUTES,
                            AclEntryPermission.READ_NAMED_ATTRS,
                            AclEntryPermission.WRITE_NAMED_ATTRS,
                            AclEntryPermission.READ_ACL,
                            AclEntryPermission.WRITE_ACL,
                            AclEntryPermission.DELETE,
                            AclEntryPermission.SYNCHRONIZE)
                    .build();
            acl.setAcl(List.of(ownerFull));
        } catch (IOException e) {
            log.warn("could not harden Windows ACL on {}: {}", target, e.getMessage());
        }
    }
}
