package com.botwithus.bot.core.launcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;

/**
 * Fetches one Store bundle over the launcher's automation pipe (amendment B1):
 * {@code sdn.bundle} for the offer, then every chunk in order, then a check of
 * the whole against the offer's length and SHA-256. The bytes stay in memory;
 * nothing is written to disk.
 *
 * <p>The offer is checked before any chunk is asked for, and each chunk must be
 * exactly as long as its place says, so a service that disagrees about the
 * framing is caught at the first wrong chunk instead of after 64 MiB.</p>
 *
 * <p>Only codes, typed reasons and support references are logged or returned; the
 * service's own text is never passed on. When the launcher names which script each
 * jar is, that list is checked against the request before any chunk is asked for.</p>
 */
public final class SdnBundleDownload {

    private static final Logger log = LoggerFactory.getLogger(SdnBundleDownload.class);
    private static final String SHA_256 = "SHA-256";

    private SdnBundleDownload() {
    }

    /**
     * @param channel   the launcher connection; the caller has checked it offers bundles
     * @param publicKey the key to seal to, {@link LauncherProtocol#SDN_PUBLIC_KEY_BYTES} long
     * @param scriptIds the catalogue ids wanted
     * @return the bundle, or how it was not obtained
     */
    public static SdnBundleFetch fetch(SdnBundleChannel channel, byte[] publicKey, List<String> scriptIds) {
        return switch (channel.requestSdnBundle(publicKey, scriptIds)) {
            case SdnCall.Answered<SdnBundleOffer> answered -> fromOffer(channel, answered.value(), scriptIds);
            case SdnCall.Refused<SdnBundleOffer> refused -> notDelivered(refused.refusal());
            case SdnCall.Unavailable<SdnBundleOffer> none -> unavailable(none.isTimedOut());
            case SdnCall.Failed<SdnBundleOffer> failed -> failedWith(failed.code());
        };
    }

    private static SdnBundleFetch fromOffer(SdnBundleChannel channel, SdnBundleOffer offer, List<String> scriptIds) {
        Optional<String> refusal = checkOffer(offer)
                .or(() -> offer.delivered().flatMap(ids -> SdnWire.checkDelivered(ids, scriptIds)));
        if (refusal.isPresent()) {
            return refused(refusal.get());
        }
        offer.failed().forEach(f -> log.info("SDN: the launcher could not deliver script {} ({}{})", f.scriptId(),
                f.reason(), f.cfRay().map(ray -> ", cf-ray " + ray).orElse("")));
        return download(channel, offer);
    }

    /** @return why the offer cannot be downloaded, or empty when it can */
    static Optional<String> checkOffer(SdnBundleOffer offer) {
        if (offer.deliveryId() < 1) {
            return Optional.of("the launcher offered delivery id " + offer.deliveryId());
        }
        if (offer.size() < 1 || offer.size() > LauncherProtocol.SDN_MAX_BUNDLE_BYTES) {
            return Optional.of("the launcher offered a bundle of " + offer.size() + " bytes");
        }
        if (offer.chunkSize() < 1 || offer.chunkSize() > LauncherProtocol.MAX_BODY_BYTES) {
            return Optional.of("the launcher offered chunks of " + offer.chunkSize() + " bytes");
        }
        if (offer.sha256().length != LauncherProtocol.SDN_SHA256_BYTES) {
            return Optional.of("the launcher offered a " + offer.sha256().length + "-byte digest");
        }
        return Optional.empty();
    }

    private static SdnBundleFetch download(SdnBundleChannel channel, SdnBundleOffer offer) {
        byte[] bundle = new byte[Math.toIntExact(offer.size())];
        long count = offer.chunkCount();
        for (long index = 0; index < count; index++) {
            SdnCall<byte[]> call = channel.sdnBundleChunk(offer.deliveryId(), index);
            byte[] chunk;
            switch (call) {
                case SdnCall.Answered<byte[]> answered -> chunk = answered.value();
                case SdnCall.Refused<byte[]> refusedChunk -> {
                    return notDelivered(refusedChunk.refusal());
                }
                case SdnCall.Unavailable<byte[]> none -> {
                    return unavailable(none.isTimedOut());
                }
                case SdnCall.Failed<byte[]> failed -> {
                    return failedWith(failed.code());
                }
            }
            long expected = offer.chunkLength(index);
            if (chunk.length != expected) {
                return refused("chunk " + index + " of " + count + " was " + chunk.length
                        + " bytes, not " + expected);
            }
            System.arraycopy(chunk, 0, bundle, Math.toIntExact(index * offer.chunkSize()), chunk.length);
        }
        if (!MessageDigest.isEqual(sha256(bundle), offer.sha256())) {
            return refused("the bundle did not match its SHA-256");
        }
        log.info("SDN: received a {}-byte bundle from the launcher in {} chunk(s)", bundle.length, count);
        return new SdnBundleFetch.Fetched(bundle, offer.delivered(), offer.failed());
    }

    /** Logs the reason and the support reference, never the launcher's own text. */
    private static SdnBundleFetch notDelivered(SdnRefusal refusal) {
        log.info("SDN: the launcher did not deliver the bundle (reason {}{}{})",
                refusal.reason().orElse("not given"),
                refusal.scriptId().map(id -> ", script " + id).orElse(""),
                refusal.cfRay().map(ray -> ", cf-ray " + ray).orElse(""));
        return new SdnBundleFetch.NotDelivered(refusal);
    }

    private static SdnBundleFetch unavailable(boolean isTimedOut) {
        log.info("SDN: the launcher {}", isTimedOut ? "did not answer in time" : "is not connected");
        return new SdnBundleFetch.Unavailable(isTimedOut);
    }

    private static SdnBundleFetch failedWith(String code) {
        log.info("SDN: the launcher did not deliver the bundle ({})", code);
        return new SdnBundleFetch.Failed(code);
    }

    private static SdnBundleFetch refused(String reason) {
        log.warn("SDN: refused the launcher's bundle: {}", reason);
        return new SdnBundleFetch.Failed(reason);
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance(SHA_256).digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java platform provides " + SHA_256, e);
        }
    }
}
