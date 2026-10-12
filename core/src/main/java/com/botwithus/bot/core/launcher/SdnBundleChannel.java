package com.botwithus.bot.core.launcher;

import com.botwithus.bot.api.script.LauncherException;

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
     * {@code sdn.bundle}.
     *
     * @param publicKey the key the bundle is sealed to
     * @param scriptIds the catalogue ids wanted, in the order the jars should arrive
     * @throws LauncherException the service's error, or {@code service_unavailable}
     *                           / {@code service_stopped} when no service answered
     */
    SdnBundleOffer requestSdnBundle(byte[] publicKey, List<String> scriptIds);

    /**
     * {@code sdn.bundle_chunk}.
     *
     * @return the chunk's bytes, exactly as the service sent them
     * @throws LauncherException as {@link #requestSdnBundle}
     */
    byte[] sdnBundleChunk(long deliveryId, long index);
}
