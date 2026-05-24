package com.botwithus.bot.core.resolver.transport;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Result of fetching a single Maven artifact piece (metadata XML, JAR,
 * SHA-256 file, .asc, etc.) from a transport.
 *
 * <p>Modeled as a sealed interface so callers can pattern-match: success
 * carries the local path the bytes were written to, the three failure
 * modes are distinct so the orchestrator (Resolver) can decide whether to
 * fall through to the next repository, retry, or surface to the user.</p>
 */
public sealed interface TransportResult
        permits TransportResult.Ok,
                TransportResult.NotFound,
                TransportResult.HttpError,
                TransportResult.Network {

    /** Bytes successfully written to {@link #localPath}. */
    record Ok(Path localPath, long bytesWritten) implements TransportResult {
        public Ok {
            Objects.requireNonNull(localPath, "localPath");
            if (bytesWritten < 0) {
                throw new IllegalArgumentException("bytesWritten must be non-negative");
            }
        }
    }

    /** 404 / file-not-found. The resolver treats this as "try next repo". */
    record NotFound(String url) implements TransportResult {
        public NotFound {
            Objects.requireNonNull(url, "url");
        }
    }

    /**
     * Non-404 HTTP error response. {@link #statusCode} is the HTTP status;
     * the resolver surfaces these as {@link com.botwithus.bot.core.resolver.ResolveOutcome.TransportFailure}.
     */
    record HttpError(String url, int statusCode, String reason) implements TransportResult {
        public HttpError {
            Objects.requireNonNull(url, "url");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * Network or filesystem IO error (connection refused, DNS failure,
     * disk full, etc.). The underlying {@link IOException} is preserved
     * for diagnostic logging.
     */
    record Network(String url, IOException cause) implements TransportResult {
        public Network {
            Objects.requireNonNull(url, "url");
            Objects.requireNonNull(cause, "cause");
        }
    }
}
