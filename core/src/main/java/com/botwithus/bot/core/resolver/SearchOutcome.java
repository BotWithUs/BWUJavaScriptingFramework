package com.botwithus.bot.core.resolver;

import com.botwithus.bot.core.resolver.transport.TransportResult;

import java.util.List;

/**
 * Result of a repository search. {@link NotSupported} lets the CLI render
 * "this repository does not support search" without dropping search
 * support entirely for the session.
 */
public sealed interface SearchOutcome
        permits SearchOutcome.Hits,
                SearchOutcome.NotSupported,
                SearchOutcome.TransportFailure {

    record Hit(MavenCoord coord, String latestVersion, String description) {}

    record Hits(List<Hit> hits) implements SearchOutcome {
        public Hits {
            hits = List.copyOf(hits);
        }
    }

    record NotSupported(Repository repository, String reason) implements SearchOutcome {}

    record TransportFailure(Repository repository, TransportResult cause) implements SearchOutcome {}
}
