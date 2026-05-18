package com.botwithus.bot.core.resolver.transport;

import com.botwithus.bot.core.resolver.Credentials;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * {@code file://} transport used by tests. Maps the {@link URI} to a path
 * via {@link Paths#get(URI)} and copies the bytes into the destination.
 *
 * <p>Atomic in the failure sense: the destination is either fully written
 * or absent — never half-written.</p>
 */
public final class FileMavenTransport implements MavenTransport {

    @Override
    public CompletableFuture<TransportResult> fetch(URI source, Path destination, Optional<Credentials> credentials) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(credentials, "credentials");
        return CompletableFuture.supplyAsync(() -> doFetch(source, destination));
    }

    private TransportResult doFetch(URI source, Path destination) {
        Path src;
        try {
            src = Paths.get(source);
        } catch (IllegalArgumentException | FileSystemNotFoundException e) {
            return new TransportResult.Network(source.toString(), new IOException(e));
        }
        if (!Files.exists(src)) {
            return new TransportResult.NotFound(source.toString());
        }
        Path parent = destination.getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = Files.createTempFile(
                    parent != null ? parent : Path.of("."),
                    destination.getFileName().toString(),
                    ".part");
            long bytes;
            try {
                Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING);
                bytes = Files.size(tmp);
                try {
                    Files.move(tmp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicFailed) {
                    Files.move(tmp, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException moveError) {
                Files.deleteIfExists(tmp);
                throw moveError;
            }
            return new TransportResult.Ok(destination, bytes);
        } catch (NoSuchFileException e) {
            return new TransportResult.NotFound(source.toString());
        } catch (IOException e) {
            return new TransportResult.Network(source.toString(), e);
        }
    }
}
