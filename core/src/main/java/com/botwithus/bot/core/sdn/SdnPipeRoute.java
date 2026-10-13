package com.botwithus.bot.core.sdn;

import com.botwithus.bot.core.crypto.SdnLoader;
import com.botwithus.bot.core.launcher.SdnBundleChannel;
import com.botwithus.bot.core.runtime.SDNScriptLoader;
import com.botwithus.bot.core.runtime.SdnDelivery;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * What an install needs to take a Store bundle over the launcher's automation
 * pipe instead of the file courier (amendment B1 of the launcher's ADR 0007).
 *
 * @param channel   the launcher connection, when this host has one; read at each
 *                  install, since the host registers after it builds the installer
 * @param publicKey this JVM's ephemeral key, which the bundle is sealed to
 * @param define    loads the scripts in a bundle that has arrived
 */
public record SdnPipeRoute(Supplier<Optional<SdnBundleChannel>> channel, Supplier<byte[]> publicKey,
                           Function<byte[], SdnDelivery> define) {

    public SdnPipeRoute {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(publicKey, "publicKey");
        Objects.requireNonNull(define, "define");
    }

    /** The production route over {@code channel}, the patched runtime's key and the bundle loader. */
    public static SdnPipeRoute over(Supplier<Optional<SdnBundleChannel>> channel) {
        return new SdnPipeRoute(channel, SdnLoader::clientPublicKey, SDNScriptLoader::loadSdnDelivery);
    }

    /** No launcher connection: every install uses the file courier. */
    public static SdnPipeRoute none() {
        return over(Optional::empty);
    }

    /** @return the connection, when it is up and its launcher serves bundles over the pipe */
    Optional<SdnBundleChannel> offering() {
        return channel.get().filter(SdnBundleChannel::offersSdnBundles);
    }
}
