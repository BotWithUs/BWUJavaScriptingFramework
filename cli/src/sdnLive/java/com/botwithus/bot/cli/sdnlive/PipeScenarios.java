package com.botwithus.bot.cli.sdnlive;

import com.botwithus.bot.cli.gui.pages.store.InstallActivity;
import com.botwithus.bot.cli.gui.usermode.board.SubscriptionState;
import com.botwithus.bot.core.launcher.LauncherProtocol;
import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.core.sdn.SdnCatalogueEntry;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * What one pipe run does through the screens, for each side of the launcher's
 * ownership rule. Uses the account's real catalogue, so it asserts on what the
 * screens and the runtime say rather than on a fixture's class names.
 */
final class PipeScenarios {

    private static final Duration LISTED_WAIT = Duration.ofSeconds(60);
    /** Sealing downloads the jars, and the host's call timeout is 40 s. */
    private static final Duration INSTALL_WAIT = Duration.ofSeconds(90);
    private static final Duration START_WAIT = Duration.ofSeconds(15);
    /** All the Store shows for a launcher refusal: the code, and nothing it was told. */
    private static final String REFUSAL = "Could not install: " + LauncherProtocol.ERROR_SDN_NOT_DELIVERED;

    private final Screens screens;
    private final LiveHost host;
    private final Verdict verdict;

    PipeScenarios(Screens screens, LiveHost host, Verdict verdict) {
        this.screens = screens;
        this.host = host;
        this.verdict = verdict;
    }

    void run(SdnPipeLiveCheck.Mode mode, Optional<String> chosenId) {
        Optional<String> id = pick(chosenId);
        if (id.isEmpty()) {
            return;
        }
        switch (mode) {
            case STORE -> storePath(id.get());
            case PICKER -> pickerPath(id.get());
            case REFUSED -> refused(id.get());
        }
    }

    /**
     * The entry to install: the one asked for, or the first subscription. The
     * picker lists only subscriptions, so a free entry the account does not
     * subscribe to would exercise the Store page alone.
     */
    private Optional<String> pick(Optional<String> chosenId) {
        screens.refreshCatalogue();
        boolean listed = Waits.until(() -> !screens.catalogueEntries().isEmpty(), LISTED_WAIT);
        verdict.expect("the launcher answered the catalogue", listed, () -> "no catalogue in " + LISTED_WAIT);
        Optional<String> id = chosenId.or(() -> screens.catalogueEntries().stream()
                .filter(PipeScenarios::isSubscription).map(SdnCatalogueEntry::id)
                .min(Comparator.naturalOrder()));
        verdict.expect("an entry to install", id.isPresent(),
                () -> "no subscription among " + screens.catalogueEntries().size());
        id.ifPresent(chosen -> {
            boolean onBoth = Waits.until(() -> screens.storeRow(chosen).isPresent()
                    && screens.pickerRow(chosen).isPresent(), LISTED_WAIT);
            verdict.expect(chosen + ": both screens list it, not installed", onBoth
                    && screens.storeRow(chosen).map(ScreenStates::isNotInstalled).orElse(false)
                    && screens.pickerRow(chosen).map(SubscriptionState::needsInstall).orElse(false),
                    () -> "store " + screens.storeRow(chosen) + ", picker " + screens.pickerRow(chosen));
        });
        return id;
    }

    private void storePath(String id) {
        screens.installFromStore(List.of(id));
        settleStore();
        InstallActivity activity = screens.storeActivity();
        verdict.expect(id + " (store): the install finished", ScreenStates.isFinished(activity),
                () -> "activity " + activity);
        expectInstalled("store", id);
    }

    private void pickerPath(String id) {
        screens.startFromPicker(id);
        boolean settled = Waits.until(() -> screens.pickerRow(id)
                .map(s -> !ScreenStates.isInstalling(s)).orElse(false), INSTALL_WAIT);
        verdict.expect(id + " (picker): the row settles", settled, () -> "row " + screens.pickerRow(id));
        verdict.expect(id + " (picker): no failure on the row",
                screens.pickerRow(id).map(s -> !ScreenStates.isFailed(s)).orElse(false),
                () -> "row " + screens.pickerRow(id));
        expectInstalled("picker", id);
        boolean started = Waits.until(() -> runner(id).map(ScriptRunner::lastStartedAt).isPresent(), START_WAIT);
        verdict.expect(id + " (picker): the delivered script started", started,
                () -> "runner " + runner(id).map(ScriptRunner::getScriptName).orElse("none"));
    }

    private void refused(String id) {
        screens.installFromStore(List.of(id));
        settleStore();
        InstallActivity activity = screens.storeActivity();
        verdict.expect(id + " (refused): the Store says only " + REFUSAL,
                activity.equals(new InstallActivity.Failed(REFUSAL)), () -> "activity " + activity);
        verdict.expect(id + " (refused): the row is still not installed",
                screens.storeRow(id).map(ScreenStates::isNotInstalled).orElse(false),
                () -> "row " + screens.storeRow(id));
        verdict.expect(id + " (refused): nothing was recorded or registered",
                screens.ledgerClassesFor(id).isEmpty() && runner(id).isEmpty(),
                () -> "ledger " + screens.ledgerClassesFor(id));
    }

    private void settleStore() {
        boolean settled = Waits.until(() -> !ScreenStates.isInstalling(screens.storeActivity()), INSTALL_WAIT);
        verdict.expect("the Store install settles", settled, () -> "activity " + screens.storeActivity());
    }

    private void expectInstalled(String path, String id) {
        String tag = id + " (" + path + "): ";
        verdict.expect(tag + "the Store row says installed",
                screens.storeRow(id).map(ScreenStates::isInstalled).orElse(false),
                () -> "row " + screens.storeRow(id));
        verdict.expect(tag + "the picker row says installed",
                screens.pickerRow(id).map(ScreenStates::isInstalled).orElse(false),
                () -> "row " + screens.pickerRow(id));
        verdict.expect(tag + "the ledger pairs a delivered class with this entry",
                !screens.ledgerClassesFor(id).isEmpty(), () -> "ledger empty for " + id);
        verdict.expect(tag + "the delivered script is registered on the client", runner(id).isPresent(),
                () -> "no runner for " + screens.ledgerClassesFor(id));
    }

    /** The runner of the script the ledger recorded for {@code id}. */
    private Optional<ScriptRunner> runner(String id) {
        return screens.ledgerClassesFor(id).stream().map(host::runnerOf).flatMap(Optional::stream).findFirst();
    }

    private static boolean isSubscription(SdnCatalogueEntry entry) {
        return entry.agentv2Support() && Boolean.TRUE.equals(entry.subscribed());
    }
}
