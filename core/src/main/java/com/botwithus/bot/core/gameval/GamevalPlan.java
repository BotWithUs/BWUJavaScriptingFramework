package com.botwithus.bot.core.gameval;

import com.botwithus.bot.core.cache.GamevalIndexVersion;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntFunction;

/**
 * Which gameval index a host should read, decided once at startup from what the
 * local game cache says and what is on disk. {@link #decide} is pure, so every
 * branch of the policy is unit-testable; {@link HostGamevals} carries it out.
 *
 * <p>The override ({@code -Dbotwithus.gameval}) is not a plan: it is checked
 * before anything here runs, so with it set no cache is even opened.</p>
 */
public sealed interface GamevalPlan {

    /** Read the shipped DB; there is nothing to build. {@code reason} goes to the log once. */
    record UseShipped(String reason) implements GamevalPlan {
        public UseShipped {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** Read the shipped DB: its {@code meta.index_version} already equals the cache's. */
    record UseShippedCurrent(int version) implements GamevalPlan {
    }

    /** Read a local build made earlier for this exact version. */
    record UseLocal(Path file, int version) implements GamevalPlan {
        public UseLocal {
            Objects.requireNonNull(file, "file");
        }
    }

    /** Read the shipped DB now, build {@code version} in the background, then swap to it. */
    record BuildThenSwap(int version) implements GamevalPlan {
    }

    /**
     * What the local cache said, or that it could not be asked at all.
     */
    sealed interface Probe {

        /** No source could be opened (old or missing DLL, no local cache). */
        record Unavailable(String reason) implements Probe {
            public Unavailable {
                Objects.requireNonNull(reason, "reason");
            }
        }

        /** The source answered. */
        record Probed(GamevalIndexVersion version) implements Probe {
            public Probed {
                Objects.requireNonNull(version, "version");
            }
        }
    }

    /**
     * Applies the policy, in order: no answer from the cache means the shipped DB; a
     * local build of the probed version wins; else the shipped DB if it is already that
     * version; else build.
     *
     * @param probe          what the local cache said
     * @param localBuild     the published local build for a version, if one exists
     * @param shippedVersion the shipped DB's {@code meta.index_version}, if it has one
     */
    static GamevalPlan decide(Probe probe, IntFunction<Optional<Path>> localBuild,
                              Optional<String> shippedVersion) {
        return switch (probe) {
            case Probe.Unavailable unavailable -> new UseShipped(unavailable.reason());
            case Probe.Probed probed -> decideProbed(probed.version(), localBuild, shippedVersion);
        };
    }

    private static GamevalPlan decideProbed(GamevalIndexVersion version,
                                            IntFunction<Optional<Path>> localBuild,
                                            Optional<String> shippedVersion) {
        return switch (version) {
            case GamevalIndexVersion.Absent absent -> new UseShipped(
                    "the local game cache has no gameval index (index 67; pre-951 or no "
                            + "js5-67.jcache)");
            case GamevalIndexVersion.Failed failed -> new UseShipped(
                    "probing the local game cache's gameval index failed (rc=" + failed.resultCode()
                            + ": " + failed.reason() + ")");
            case GamevalIndexVersion.Present present -> decidePresent(present, localBuild,
                    shippedVersion);
        };
    }

    private static GamevalPlan decidePresent(GamevalIndexVersion.Present present,
                                             IntFunction<Optional<Path>> localBuild,
                                             Optional<String> shippedVersion) {
        int version = present.value();
        Optional<Path> local = localBuild.apply(version);
        if (local.isPresent()) {
            return new UseLocal(local.get(), version);
        }
        if (shippedVersion.filter(present.metaValue()::equals).isPresent()) {
            return new UseShippedCurrent(version);
        }
        return new BuildThenSwap(version);
    }
}
