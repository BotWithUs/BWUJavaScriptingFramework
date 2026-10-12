package com.botwithus.bot.core.launcher;

import java.util.Objects;

/**
 * The service's answer to {@code sdn.bundle}: what it will hand over, and how
 * (amendment B1). The bundle itself follows in chunks of {@link #chunkSize()},
 * the last one shorter when {@link #size()} is not a multiple of it.
 *
 * <p>Nothing here is trusted until {@link SdnBundleDownload} has checked it:
 * the record holds the values as the service sent them.</p>
 *
 * @param deliveryId names the delivery in {@code sdn.bundle_chunk}; scoped to the connection
 * @param size       the bundle's length in bytes
 * @param sha256     the SHA-256 of the whole bundle
 * @param chunkSize  the length of every chunk but the last
 */
public record SdnBundleOffer(long deliveryId, long size, byte[] sha256, long chunkSize) {

    public SdnBundleOffer {
        sha256 = Objects.requireNonNull(sha256, "sha256").clone();
    }

    /** @return a copy of the digest */
    @Override
    public byte[] sha256() {
        return sha256.clone();
    }

    /** @return how many chunks carry {@link #size()} bytes; only meaningful once the offer was checked */
    public long chunkCount() {
        return (size + chunkSize - 1) / chunkSize;
    }

    /** @return the length chunk {@code index} must have; only meaningful once the offer was checked */
    public long chunkLength(long index) {
        return Math.min(chunkSize, size - index * chunkSize);
    }
}
