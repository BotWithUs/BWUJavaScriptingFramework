package com.botwithus.bot.core.sdn;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.script.LauncherException;
import com.botwithus.bot.core.launcher.LauncherProtocol;
import com.botwithus.bot.core.launcher.SdnBundleChannel;
import com.botwithus.bot.core.launcher.SdnBundleOffer;
import com.botwithus.bot.core.runtime.SdnDelivery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Which route an install takes (amendment B1 of the launcher's ADR 0007): the
 * launcher's pipe whenever its {@code hello} listed {@code sdn.bundle}, and the
 * file courier only when the method is absent or no launcher answers.
 */
class SdnInstallerPipeTest {

    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");
    private static final String ENTRY_ID = "7";
    private static final int BUNDLE_BYTES = 3000;
    private static final int CHUNK = 1024;
    private static final byte[] KEY = new byte[LauncherProtocol.SDN_PUBLIC_KEY_BYTES];

    @TempDir
    Path dir;

    @TempDir
    Path home;

    private final byte[] bundle = bundleBytes();
    private final List<byte[]> defined = new ArrayList<>();
    private final AtomicInteger fileDeliveries = new AtomicInteger();
    private final List<List<String>> requestFilesSeen = new ArrayList<>();

    @Test
    void aLauncherThatOffersBundles_isUsed_andNoFileIsTouched() throws IOException {
        ServingChannel channel = new ServingChannel(bundle, true);

        SdnInstallResult result = installer(Optional.of(channel)).install(List.of(entry()));

        SdnInstallResult.Installed installed = assertInstanceOf(SdnInstallResult.Installed.class, result);
        assertAll(() -> assertEquals(1, defined.size()),
                () -> assertArrayEquals(bundle, defined.getFirst(), "the decoder gets the bytes unchanged"),
                () -> assertEquals(List.of(ENTRY_ID), channel.requestedIds),
                () -> assertEquals(0, fileDeliveries.get(), "the file courier is never asked"),
                () -> assertEquals(List.of(), filesIn(dir), "nothing is written to the rendezvous"),
                () -> assertEquals(1, installed.scriptsFor(ENTRY_ID).size()),
                () -> assertEquals(Optional.of(ENTRY_ID), ledger().find(PipeScript.class.getName())
                        .map(InstalledSdnScript::catalogueId)));
    }

    @Test
    void anOlderLauncher_withoutTheMethod_usesTheFiles() {
        ServingChannel channel = new ServingChannel(bundle, false);

        SdnInstallResult result = installer(Optional.of(channel)).install(List.of(entry()));

        assertAll(() -> assertInstanceOf(SdnInstallResult.Installed.class, result),
                () -> assertEquals(1, fileDeliveries.get()),
                () -> assertEquals(List.of(List.of(ENTRY_ID + SdnInstaller.REQUEST_SUFFIX)), requestFilesSeen),
                () -> assertEquals(0, channel.bundleRequests, "the pipe is not asked"),
                () -> assertEquals(List.of(), defined));
    }

    @Test
    void noLauncherConnection_usesTheFiles() {
        SdnInstallResult result = installer(Optional.empty()).install(List.of(entry()));

        assertAll(() -> assertInstanceOf(SdnInstallResult.Installed.class, result),
                () -> assertEquals(1, fileDeliveries.get()));
    }

    @Test
    void sdnNotDelivered_isACouldNotInstallCarryingOnlyTheCode_andFilesAreNotTried() {
        ServingChannel channel = new ServingChannel(bundle, true);
        channel.refusal = LauncherProtocol.ERROR_SDN_NOT_DELIVERED;

        SdnInstallResult result = installer(Optional.of(channel)).install(List.of(entry()));

        assertAll(() -> assertEquals(new SdnInstallResult.Failed(LauncherProtocol.ERROR_SDN_NOT_DELIVERED), result),
                () -> assertEquals(0, fileDeliveries.get()));
    }

    @Test
    void aLauncherLostMidDelivery_isUnavailable_andFilesAreNotTried() {
        ServingChannel channel = new ServingChannel(bundle, true);
        channel.refusal = LauncherException.SERVICE_UNAVAILABLE;

        SdnInstallResult result = installer(Optional.of(channel)).install(List.of(entry()));

        assertAll(() -> assertInstanceOf(SdnInstallResult.CourierUnavailable.class, result),
                () -> assertEquals(0, fileDeliveries.get()));
    }

    @Test
    void aBundleThatLoadsNothing_isAFailure_notAMissingLauncher() {
        SdnInstaller installer = new SdnInstaller(dir, () -> true, this::fileDelivery,
                new SdnPipeRoute(() -> Optional.of(new ServingChannel(bundle, true)), () -> KEY,
                        bytes -> SdnDelivery.none()), ledger());

        assertInstanceOf(SdnInstallResult.Failed.class, installer.install(List.of(entry())));
    }

    @Test
    void aHostTheLauncherDidNotStart_isStillDeliveryDisabled() {
        SdnInstaller installer = new SdnInstaller(dir, () -> false, this::fileDelivery,
                route(Optional.of(new ServingChannel(bundle, true))), ledger());

        assertInstanceOf(SdnInstallResult.DeliveryDisabled.class, installer.install(List.of(entry())));
    }

    private SdnInstaller installer(Optional<SdnBundleChannel> channel) {
        return new SdnInstaller(dir, () -> true, this::fileDelivery, route(channel), ledger());
    }

    private SdnPipeRoute route(Optional<SdnBundleChannel> channel) {
        return new SdnPipeRoute(() -> channel, () -> KEY, bytes -> {
            defined.add(bytes.clone());
            return oneJar();
        });
    }

    /** The file courier's stand-in: notes the request file it can see, then delivers. */
    private SdnDelivery fileDelivery() {
        fileDeliveries.incrementAndGet();
        requestFilesSeen.add(filesIn(dir).stream()
                .map(name -> name.replace(String.valueOf(SdnRendezvous.currentPid()), ENTRY_ID)).toList());
        return oneJar();
    }

    private InstalledScriptsLedger ledger() {
        return new InstalledScriptsLedger(home, InstantSource.fixed(NOW));
    }

    private static SdnDelivery oneJar() {
        return new SdnDelivery(1, List.of(new SdnDelivery.Jar(0, List.of(new PipeScript()))));
    }

    private static SdnCatalogueEntry entry() {
        return new SdnCatalogueEntry(ENTRY_ID, "Pipe", "author", "me", "1.0", "2", "", "",
                PipeScript.class.getName(), false, true, true, false, null, null, 1);
    }

    private static List<String> filesIn(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(p -> p.getFileName().toString()).toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] bundleBytes() {
        byte[] b = new byte[BUNDLE_BYTES];
        Arrays.fill(b, (byte) 'b');
        return b;
    }

    /** A launcher connection that serves one bundle, or refuses with {@link #refusal}. */
    private static final class ServingChannel implements SdnBundleChannel {

        final List<String> requestedIds = new ArrayList<>();
        int bundleRequests;
        String refusal;
        private final byte[] bundle;
        private final boolean isOffered;

        ServingChannel(byte[] bundle, boolean isOffered) {
            this.bundle = bundle.clone();
            this.isOffered = isOffered;
        }

        @Override
        public boolean offersSdnBundles() {
            return isOffered;
        }

        @Override
        public SdnBundleOffer requestSdnBundle(byte[] publicKey, List<String> scriptIds) {
            bundleRequests++;
            requestedIds.addAll(scriptIds);
            if (refusal != null) {
                throw new LauncherException(refusal, "sdn.bundle: refused");
            }
            try {
                return new SdnBundleOffer(1, bundle.length, MessageDigest.getInstance("SHA-256").digest(bundle),
                        CHUNK);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public byte[] sdnBundleChunk(long deliveryId, long index) {
            int from = Math.toIntExact(index * CHUNK);
            return Arrays.copyOfRange(bundle, from, Math.min(bundle.length, from + CHUNK));
        }
    }

    /** What the delivery carries. */
    @ScriptManifest(name = "Pipe")
    public static final class PipeScript implements BotScript {

        @Override
        public void onStart(ScriptContext ctx) {
        }

        @Override
        public int onLoop() {
            return -1;
        }

        @Override
        public void onStop() {
        }
    }
}
