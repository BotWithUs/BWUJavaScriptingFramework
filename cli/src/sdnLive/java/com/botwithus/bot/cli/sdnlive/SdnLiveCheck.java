package com.botwithus.bot.cli.sdnlive;

import com.botwithus.bot.core.sdn.SdnRendezvous;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The live check of the SDN install path, through both screens that install:
 * the Store page and the "Start a script" picker.
 *
 * <p>It runs against a real injected game client and the host's real install
 * code: the installer, its ledger, the pairing of delivered scripts with the
 * entries they were requested as, and the runtime that registers and starts
 * them. The launcher's courier is replaced by a test courier that signs with a
 * key the run chose, which only a debug runtime accepts. Launched by
 * {@code :cli:sdnLiveCheck}, which supplies everything it needs.
 *
 * <p>What it does not exercise: the ImGui widgets (each is a one-line forward to
 * the screen models driven here) and the launcher's own courier.
 *
 * <p>Exit status: 0 pass, 1 fail, 2 could not run.
 */
public final class SdnLiveCheck {

    private static final Logger log = LoggerFactory.getLogger(SdnLiveCheck.class);

    private static final int EXIT_PASS = 0;
    private static final int EXIT_FAIL = 1;
    private static final int EXIT_UNUSABLE = 2;

    private SdnLiveCheck() {
    }

    public static void main(String[] args) {
        // Exits explicitly: a connected host leaves non-daemon threads behind.
        System.exit(runReportingStatus());
    }

    private static int runReportingStatus() {
        try {
            return run(SdnLiveConfig.fromSystemProperties()) ? EXIT_PASS : EXIT_FAIL;
        } catch (IllegalArgumentException e) {
            log.error("SDN-LIVE cannot run: {}", e.getMessage());
            return EXIT_UNUSABLE;
        } catch (IOException | RuntimeException e) {
            log.error("SDN-LIVE FAIL the run broke", e);
            return EXIT_FAIL;
        }
    }

    private static boolean run(SdnLiveConfig config) throws IOException {
        Verdict verdict = new Verdict();
        if (Preconditions.hold(config, verdict)) {
            SdnLiveCases cases = SdnLiveCases.over(config.jarsByVariant());
            exercise(config, cases, verdict);
            HostLogEvidence.check(config.hostLog(), cases, verdict);
        }
        verdict.summarise();
        return verdict.passed();
    }

    private static void exercise(SdnLiveConfig config, SdnLiveCases cases, Verdict verdict) throws IOException {
        Path catalogue = SdnLiveCatalogue.write(config.workDir().resolve("fixture"), cases.all());
        Path rendezvous = SdnRendezvous.directory();
        try (LiveHost host = LiveHost.connect(config.pid())) {
            InstallScenarios scenarios = new InstallScenarios(new Screens(host), host, cases, verdict);
            try (TestCourier untrusted = TestCourier.start(config.courier(), config.wrongSeed(), rendezvous,
                    catalogue, cases.jarsById())) {
                if (!scenarios.catalogueIsListed()) {
                    return;
                }
                scenarios.negativeControl(untrusted);
            }
            try (TestCourier courier = TestCourier.start(config.courier(), config.seed(), rendezvous, catalogue,
                    cases.jarsById())) {
                scenarios.storePath(courier);
                scenarios.pickerPath(courier);
            }
        }
    }
}
