package com.botwithus.bot.core.resolver.transport;

import com.botwithus.bot.core.resolver.Credentials;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Functional interface that fetches a single resource over a transport
 * (HTTP, {@code file://}, etc.) into a local file. Transports carry no
 * layout knowledge — they receive an absolute URI from a
 * {@link com.botwithus.bot.core.resolver.driver.RepositoryDriver driver}
 * and stream the bytes to disk.
 *
 * <p>Implementations: {@link FileTransport} for {@code file://} URIs used
 * by tests, {@code HttpTransport} for HTTP(S) (shipped in 12.2).</p>
 *
 * <p>Implementations must never leave the destination path partially
 * written on failure — write to a sibling temp file and atomically
 * rename only on success.</p>
 *
 * <p>Async by design — a sequential blocking-fetch tour through multiple
 * repositories per install would be visibly slow. Callers join via
 * {@code .join()} on the 12.1 codepath; future parallel fetches across
 * mirrors slot in without changing the signature.</p>
 */
@FunctionalInterface
public interface Transport {

    CompletableFuture<TransportResult> fetch(URI source, Path destination, Optional<Credentials> credentials);
}
