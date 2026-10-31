package com.botwithus.bot.core.launcher;

import java.util.List;

/**
 * The two calls that move a Store bundle over the launcher's automation pipe
 * (amendment B1), and whether the launcher on the other end has them.
 * {@link LauncherService} is the one implementation; the seam is here so the
 * download can be held to the contract without a pipe.
 */
public interface SdnBundleChannel {

    /**
     * @return whether a registered connection is up now and its launcher listed
     *         {@code sdn.bundle} in {@code hello}. False means an older launcher, or none.
     */
    boolean offersSdnBundles();

    /**
     * {@code sdn.bundle}, asking for a partial delivery.
     *
     * @param publicKey the key the bundle is sealed to
     * @param scriptIds the catalogue ids wanted, in the order the jars should arrive
     * @return the offer, or how the call ended without one; never throws for a refusal
     */
    SdnCall<SdnBundleOffer> requestSdnBundle(byte[] publicKey, List<String> scriptIds);

    /**
     * {@code sdn.bundle_chunk}.
     *
     * @return the chunk's bytes exactly as the service sent them, or how the call ended
     */
    SdnCall<byte[]> sdnBundleChunk(long deliveryId, long index);
}
