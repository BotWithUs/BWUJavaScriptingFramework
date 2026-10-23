package com.botwithus.bot.core.sdn;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.script.LauncherException;
import com.botwithus.bot.core.launcher.LauncherProtocol;
import com.botwithus.bot.core.launcher.SdnBundleChannel;
import com.botwithus.bot.core.launcher.SdnBundleOffer;
import com.botwithus.bot.core.launcher.SdnCall;
import com.botwithus.bot.core.launcher.SdnRefusal;
import com.botwithus.bot.core.launcher.SdnScriptFailure;
import com.botwithus.bot.core.runtime.SdnDelivery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which route an install takes (amendment B1 of the launcher's ADR 0007): the
 * launcher's pipe whenever its {@code hello} listed {@code sdn.bundle}, and the
 * file courier only when the method is absent or no launcher answers.
 */
class SdnInstallerPipeTest {

    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");
    private static final String ENTRY_ID = "7";
    private static final String OTHER_ID = "12";
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
    /** What defining the pipe's bundle yields; one jar of {@link PipeScript} unless a test says otherwise. */
    private Supplier<SdnDelivery> definedAs = SdnInstallerPipeTest::oneJar;

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
    void sdnNotDelivered_fromAnOlderLauncher_isARefusalWithTheGenericText_andFilesAreNotTried() {
        ServingChannel channel = new ServingChannel(bundle, true);
        channel.refusal = LauncherProtocol.ERROR_SDN_NOT_DELIVERED;

        SdnInstallResult result = installer(Optional.of(channel)).install(List.of(entry()));

        SdnInstallResult.Refused refused = assertInstanceOf(SdnInstallResult.Refused.class, result);
        assertAll(() -> assertEquals(SdnReasonText.of(Optional.empty(), "Pipe", Optional.empty()), refused.text()),
                () -> assertFalse(refused.text().toLowerCase(Locale.ROOT).contains("subscri"),
                        "an unexplained refusal must not send the user to pay"),
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

    // ── Typed reasons and partial delivery ─────────────────────────────────

    /** The delivered script is paired by the launcher's list; the one it could not deliver says why. */
    @Test
    void aPartialDelivery_installsWhatCame_andSaysWhyTheRestDidNot() {
        ServingChannel channel = new ServingChannel(bundle, true);
        channel.delivered = Optional.of(List.of(ENTRY_ID));
        channel.failed = List.of(new SdnScriptFailure(OTHER_ID, SdnReasonText.NOT_ENTITLED, OptionalLong.of(403),
                Optional.empty()));

        SdnInstallResult.Installed installed = assertInstanceOf(SdnInstallResult.Installed.class,
                installer(Optional.of(channel)).install(List.of(entry(), other())));

        assertAll(() -> assertEquals(1, installed.scriptsFor(ENTRY_ID).size()),
                () -> assertTrue(installed.scriptsFor(OTHER_ID).isEmpty()),
                () -> assertEquals(Optional.of("Subscription needed: your account doesn't have Other. "
                        + "Subscribe on the website, then try again."),
                        installed.refusalFor(OTHER_ID).map(SdnInstallResult.ScriptRefusal::text)),
                () -> assertEquals(Set.of(PipeScript.class.getName()), ledger().all().keySet()));
    }

    /** Fails if jars are paired by request position when the launcher listed them in another order. */
    @Test
    void jars_arePairedByTheLaunchersList_notTheRequestOrder() {
        ServingChannel channel = new ServingChannel(bundle, true);
        channel.delivered = Optional.of(List.of(OTHER_ID, ENTRY_ID));
        definedAs = () -> new SdnDelivery(2, List.of(new SdnDelivery.Jar(0, List.of(new OtherScript())),
                new SdnDelivery.Jar(1, List.of(new PipeScript()))));

        SdnInstallResult.Installed installed = assertInstanceOf(SdnInstallResult.Installed.class,
                installer(Optional.of(channel)).install(List.of(entry(), other())));

        assertAll(() -> assertInstanceOf(PipeScript.class, installed.scriptsFor(ENTRY_ID).getFirst()),
                () -> assertInstanceOf(OtherScript.class, installed.scriptsFor(OTHER_ID).getFirst()));
    }

    /** A bundle that holds a different number of jars than were listed is refused before anything is made. */
    @Test
    void aBundleWhoseJarCountDisagreesWithTheList_isRefusedBeforeDefining() {
        ServingChannel channel = new ServingChannel(bundleClaimingJars(2), true);
        channel.delivered = Optional.of(List.of(ENTRY_ID));

        SdnInstallResult result = installer(Optional.of(channel)).install(List.of(entry()));

        assertAll(() -> assertEquals(new SdnInstallResult.Failed(SdnReasonText.MISMATCH_TEXT), result),
                () -> assertEquals(List.of(), defined, "nothing in it is defined or constructed"));
    }

    @Test
    void aTypedRefusal_isTheUsersText_andOnlyNotEntitledSaysSubscription() {
        ServingChannel entitled = new ServingChannel(bundle, true);
        entitled.typedRefusal = refusal(SdnReasonText.NOT_ENTITLED);
        ServingChannel noLink = new ServingChannel(bundle, true);
        noLink.typedRefusal = refusal(SdnReasonText.NO_LINK);

        SdnInstallResult.Refused notEntitled = assertInstanceOf(SdnInstallResult.Refused.class,
                installer(Optional.of(entitled)).install(List.of(entry())));
        SdnInstallResult.Refused linkDown = assertInstanceOf(SdnInstallResult.Refused.class,
                installer(Optional.of(noLink)).install(List.of(entry())));

        assertAll(() -> assertTrue(notEntitled.text().startsWith("Subscription needed")),
                () -> assertEquals(Optional.of(SdnReasonText.NOT_ENTITLED),
                        notEntitled.refusals().getFirst().reason()),
                () -> assertFalse(linkDown.text().toLowerCase(Locale.ROOT).contains("subscri")));
    }

    @Test
    void noAnswer_saysWhetherTheLauncherWasSlowOrNotConnected() {
        ServingChannel slow = new ServingChannel(bundle, true);
        slow.isTimedOut = true;
        ServingChannel gone = new ServingChannel(bundle, true);
        gone.refusal = LauncherException.SERVICE_UNAVAILABLE;

        assertAll(() -> assertEquals(SdnReasonText.TIMED_OUT_TEXT, assertInstanceOf(
                        SdnInstallResult.CourierUnavailable.class,
                        installer(Optional.of(slow)).install(List.of(entry()))).text()),
                () -> assertEquals(SdnReasonText.NOT_CONNECTED_TEXT, assertInstanceOf(
                        SdnInstallResult.CourierUnavailable.class,
                        installer(Optional.of(gone)).install(List.of(entry()))).text()));
    }

    /** The file route's courier answered "no" with a reason: it is said at once, in the user's words. */
    @Test
    void aFileRouteRefusal_isTheUsersText() {
        SdnInstaller installer = new SdnInstaller(dir, () -> true,
                () -> SdnDelivery.refused(SdnReasonText.HOST_NOT_ON_PIPE), route(Optional.empty()), ledger());

        SdnInstallResult.Refused refused = assertInstanceOf(SdnInstallResult.Refused.class,
                installer.install(List.of(entry())));

        assertEquals(SdnReasonText.of(Optional.of(SdnReasonText.HOST_NOT_ON_PIPE), "Pipe", Optional.empty()),
                refused.text());
    }

    private static SdnRefusal refusal(String reason) {
        return new SdnRefusal(Optional.of(reason), Optional.of(ENTRY_ID), OptionalLong.empty(), Optional.empty(),
                List.of());
    }

    /** A bundle whose header claims {@code jars} jars; nothing past the header is real. */
    private static byte[] bundleClaimingJars(int jars) {
        byte[] b = bundleBytes();
        ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).putInt(jars);
        return b;
    }

    private static SdnCatalogueEntry other() {
        return new SdnCatalogueEntry(OTHER_ID, "Other", "author", "me", "1.0", "2", "", "",
                OtherScript.class.getName(), false, true, true, false, null, null, 1);
    }

    private SdnInstaller installer(Optional<SdnBundleChannel> channel) {
        return new SdnInstaller(dir, () -> true, this::fileDelivery, route(channel), ledger());
    }

    private SdnPipeRoute route(Optional<SdnBundleChannel> channel) {
        return new SdnPipeRoute(() -> channel, () -> KEY, bytes -> {
            defined.add(bytes.clone());
            return definedAs.get();
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
        SdnRefusal typedRefusal;
        boolean isTimedOut;
        Optional<List<String>> delivered = Optional.empty();
        List<SdnScriptFailure> failed = List.of();
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
        public SdnCall<SdnBundleOffer> requestSdnBundle(byte[] publicKey, List<String> scriptIds) {
            bundleRequests++;
            requestedIds.addAll(scriptIds);
            if (typedRefusal != null) {
                return new SdnCall.Refused<>(typedRefusal);
            }
            if (isTimedOut) {
                return new SdnCall.Unavailable<>(true);
            }
            if (refusal != null) {
                return switch (refusal) {
                    case LauncherProtocol.ERROR_SDN_NOT_DELIVERED -> new SdnCall.Refused<>(SdnRefusal.unexplained());
                    case LauncherException.SERVICE_UNAVAILABLE, LauncherException.SERVICE_STOPPED ->
                            new SdnCall.Unavailable<>(false);
                    default -> new SdnCall.Failed<>(refusal);
                };
            }
            try {
                return new SdnCall.Answered<>(new SdnBundleOffer(1, bundle.length,
                        MessageDigest.getInstance("SHA-256").digest(bundle), CHUNK, delivered, failed));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public SdnCall<byte[]> sdnBundleChunk(long deliveryId, long index) {
            int from = Math.toIntExact(index * CHUNK);
            return new SdnCall.Answered<>(Arrays.copyOfRange(bundle, from, Math.min(bundle.length, from + CHUNK)));
        }
    }

    /** A second delivered script, so pairing can be told apart. */
    @ScriptManifest(name = "Other")
    public static final class OtherScript implements BotScript {

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
