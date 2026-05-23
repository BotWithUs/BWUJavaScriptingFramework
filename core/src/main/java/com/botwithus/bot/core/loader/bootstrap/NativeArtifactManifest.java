package com.botwithus.bot.core.loader.bootstrap;

import java.net.URI;
import java.util.List;
import java.util.Objects;

/**
 * Declarative list of native artifacts the framework downloads on first
 * launch. Each entry pairs a remote URL with the expected SHA-256 digest
 * of the bytes; the downloader will not place an artifact into the cache
 * unless the digest matches.
 *
 * <p>URLs and digests are stubbed via the sentinel host
 * {@link #STUB_HOST} and the sentinel digest {@link #STUB_DIGEST}. The
 * downloader treats any entry whose URL host equals the sentinel host —
 * or whose digest equals the sentinel string — as "not configured yet"
 * and skips it silently. This lets the bootstrap run end-to-end before
 * real hosting is in place.</p>
 *
 * <p>When real URLs become available, replace the placeholders in
 * {@link #defaults()} with concrete URIs and the SHA-256 of the served
 * bytes; no other code change is required.</p>
 */
public record NativeArtifactManifest(List<Entry> entries) {

    /** Sentinel host substring; treated as "not configured" by the downloader. */
    public static final String STUB_HOST = "TODO.example";

    /** Sentinel SHA-256 placeholder; treated as "not configured" by the downloader. */
    public static final String STUB_DIGEST = "TODO";

    public NativeArtifactManifest {
        Objects.requireNonNull(entries, "entries");
        entries = List.copyOf(entries);
    }

    /** Built-in manifest used by the CLI bootstrap. URLs are stubbed until real hosting lands. */
    public static NativeArtifactManifest defaults() {
        return new NativeArtifactManifest(List.of(
                new Entry.Dll(
                        "bwu.dll",
                        URI.create("https://" + STUB_HOST + "/bwu.dll"),
                        STUB_DIGEST),
                new Entry.Zip(
                        "native-libs.zip",
                        URI.create("https://" + STUB_HOST + "/native-libs.zip"),
                        STUB_DIGEST)));
    }

    /**
     * A single artifact to download. The two shapes have distinct
     * post-download semantics: a {@link Entry.Dll} is placed into the
     * cache directory as-is; a {@link Entry.Zip} is extracted into the
     * cache directory and the staged archive is then deleted.
     */
    public sealed interface Entry permits Entry.Dll, Entry.Zip {

        /** Filename under the cache directory (for {@link Zip}, the staging archive name). */
        String destFilename();

        /** Source URL. The host {@link #STUB_HOST} marks an unconfigured entry. */
        URI url();

        /** Expected SHA-256 of the downloaded bytes, lowercase hex. The value {@link #STUB_DIGEST} marks an unconfigured entry. */
        String sha256Hex();

        /** A standalone native library; placed at {@code <cacheDir>/<destFilename>}. */
        record Dll(String destFilename, URI url, String sha256Hex) implements Entry {
            public Dll {
                Objects.requireNonNull(destFilename, "destFilename");
                Objects.requireNonNull(url, "url");
                Objects.requireNonNull(sha256Hex, "sha256Hex");
            }
        }

        /** A zip of native libraries; extracted into {@code <cacheDir>/}, staging archive deleted on success. */
        record Zip(String destFilename, URI url, String sha256Hex) implements Entry {
            public Zip {
                Objects.requireNonNull(destFilename, "destFilename");
                Objects.requireNonNull(url, "url");
                Objects.requireNonNull(sha256Hex, "sha256Hex");
            }
        }
    }
}
