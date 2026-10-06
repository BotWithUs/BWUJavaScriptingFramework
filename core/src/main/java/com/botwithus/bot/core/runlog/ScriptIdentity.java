package com.botwithus.bot.core.runlog;

import com.botwithus.bot.api.ScriptManifest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import java.util.Optional;

/**
 * The header values that describe the script: its manifest, where it came from
 * and a hash of what was loaded.
 *
 * <p><b>Source is inferred, not reported.</b> A script whose class came from a
 * {@code .jar} on disk is {@code local} and is hashed; the staged copy the host
 * loads from is byte-identical to the jar the scripter built, so the hash is the
 * scripter's. A class with no code source was defined in memory, which in this
 * host is the SDN loader: {@code sdn}, with {@code script_sha256} {@code unknown},
 * because the decrypted bytes never reach disk and the runner never sees the
 * bundle it came from. A code source that is a directory (an IDE run) is
 * {@code local} with no hash.</p>
 *
 * @param name    manifest name, else the class's simple name
 * @param version manifest version, or {@code unknown}
 * @param author  manifest author, or {@code unknown} when blank
 * @param source  {@code sdn}, {@code local} or {@code unknown}
 * @param sha256  lowercase hex SHA-256 of the script jar, or {@code unknown}
 */
public record ScriptIdentity(String name, String version, String author, String source,
                             String sha256) {

    private static final int HASH_BUFFER_BYTES = 64 * 1024;

    /** Reads the identity of {@code scriptClass}. Hashes its jar, so call it off any UI thread. */
    public static ScriptIdentity of(Class<?> scriptClass) {
        ScriptManifest manifest = scriptClass.getAnnotation(ScriptManifest.class);
        String name = manifest != null ? manifest.name() : scriptClass.getSimpleName();
        String version = manifest != null ? manifest.version() : null;
        String author = manifest != null ? manifest.author() : null;
        Optional<URL> location = codeLocation(scriptClass);
        if (location.isEmpty()) {
            return new ScriptIdentity(name, version, author, RunLogHeader.SOURCE_SDN, null);
        }
        String sha = asFile(location.get())
                .filter(Files::isRegularFile)
                .map(ScriptIdentity::sha256)
                .orElse(null);
        return new ScriptIdentity(name, version, author, RunLogHeader.SOURCE_LOCAL, sha);
    }

    private static Optional<URL> codeLocation(Class<?> type) {
        ProtectionDomain domain = type.getProtectionDomain();
        CodeSource source = domain != null ? domain.getCodeSource() : null;
        return Optional.ofNullable(source != null ? source.getLocation() : null);
    }

    private static Optional<Path> asFile(URL url) {
        try {
            return Optional.of(Path.of(url.toURI()));
        } catch (URISyntaxException | IllegalArgumentException | FileSystemNotFoundException e) {
            return Optional.empty();
        }
    }

    private static String sha256(Path jar) {
        try (InputStream in = Files.newInputStream(jar)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[HASH_BUFFER_BYTES];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            return null;
        }
    }
}
