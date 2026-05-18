package com.botwithus.bot.core.resolver.transport;

import com.botwithus.bot.core.resolver.Credentials;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Functional interface that fetches one Maven artifact piece into a local
 * file. The transport is responsible for handling auth (HTTP), and for
 * never leaking the destination path on failure (write to a temp file and
 * rename only on success).
 *
 * <p>Implementations: {@link FileMavenTransport} for {@code file://} URIs
 * used by tests, {@code HttpMavenTransport} for everything else (shipped
 * in 12.2).</p>
 *
 * <p>Async by design — Maven Central is on the public internet and a
 * sequential blocking-fetch tour through multiple repositories per
 * install would be visibly slow. Callers join via {@code .join()} in the
 * 12.1 codepath; future parallel fetches across mirrors slot in without
 * changing the signature.</p>
 */
@FunctionalInterface
public interface MavenTransport {

    CompletableFuture<TransportResult> fetch(URI source, Path destination, Optional<Credentials> credentials);
}
