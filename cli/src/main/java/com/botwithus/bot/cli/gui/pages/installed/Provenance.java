package com.botwithus.bot.cli.gui.pages.installed;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Where an installed script is on this PC and where it came from.
 *
 * @param source   Store or local build
 * @param isLoaded false for a Store script the host installed before a restart and
 *                 has not loaded since: Store deliveries live in memory only
 * @param jar      the JAR in the scripts folder; empty for a Store delivery
 * @param changed  when that JAR last changed, as the page shows it ({@code "today 13:58"})
 * @param update   the Store's newer build, when it has one
 */
public record Provenance(ScriptSource source, boolean isLoaded, Optional<Path> jar, Optional<String> changed,
                         Optional<UpdateBadge> update) {

    public Provenance {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(jar, "jar");
        Objects.requireNonNull(changed, "changed");
        Objects.requireNonNull(update, "update");
    }

    /** The JAR's file name, or empty for a Store delivery. */
    public Optional<String> jarName() {
        return jar.map(p -> p.getFileName().toString());
    }

    /** A Store script this host installed but has not loaded since it restarted. */
    public boolean isStoreNotLoaded() {
        return source == ScriptSource.STORE && !isLoaded;
    }
}
