package com.botwithus.bot.cli.sdnlive;

import com.botwithus.bot.cli.gui.pages.store.InstallActivity;
import com.botwithus.bot.cli.gui.usermode.board.SubscriptionState;
import com.botwithus.bot.core.runtime.ScriptRunner;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The installs one run makes, through each screen, and what each must leave
 * behind on both.
 */
final class InstallScenarios {

    private static final Duration LISTED_WAIT = Duration.ofSeconds(30);
    /** Longer than the host's 30-second courier timeout, so a timeout reads as a failure, not a hang. */
    private static final Duration INSTALL_WAIT = Duration.ofSeconds(75);
    private static final Duration START_WAIT = Duration.ofSeconds(15);

    private final Screens screens;
    private final LiveHost host;
    private final SdnLiveCases cases;
    private final Verdict verdict;

    InstallScenarios(Screens screens, LiveHost host, SdnLiveCases cases, Verdict verdict) {
        this.screens = screens;
        this.host = host;
        this.cases = cases;
        this.verdict = verdict;
    }

    /** Both screens list every entry, and none is installed yet. Nothing else is worth checking otherwise. */
    boolean catalogueIsListed() {
        List<String> ids = cases.all().stream().map(SdnLiveCase::id).toList();
        boolean listed = screens.awaitListed(ids, LISTED_WAIT);
        verdict.expect("both screens list the courier's catalogue", listed,
                () -> "store rows " + ids.stream().map(screens::storeRow).toList()
                        + ", picker rows " + ids.stream().map(screens::pickerRow).toList());
        if (!listed) {
            return false;
        }
        for (SdnLiveCase c : cases.all()) {
            verdict.expect(c.id() + ": not installed before the run",
                    screens.storeRow(c.id()).map(ScreenStates::isNotInstalled).orElse(false)
                            && screens.pickerRow(c.id()).map(SubscriptionState::needsInstall).orElse(false),
                    () -> "store " + screens.storeRow(c.id()) + ", picker " + screens.pickerRow(c.id()));
        }
        return true;
    }

    /**
     * A bundle signed by a key the runtime does not trust is delivered, and must
     * load nothing. Without this the run could not tell a sealed delivery from
     * one the runtime never checked.
     */
    void negativeControl(TestCourier courier) {
        SdnLiveCase neg = cases.negative();
        screens.installFromStore(List.of(neg.id()));
        settleStoreInstall("negative control");
        InstallActivity activity = screens.storeActivity();
        verdict.expect("negative: the courier delivered the wrongly signed bundle",
                courier.deliveriesNaming(neg.id()) == 1, () -> "deliveries " + courier.deliveriesNaming(neg.id()));
        verdict.expect("negative: the Store reports the install failed", ScreenStates.isFailed(activity),
                () -> "activity " + activity);
        verdict.expect("negative: the Store row is still not installed",
                screens.storeRow(neg.id()).map(ScreenStates::isNotInstalled).orElse(false),
                () -> "row " + screens.storeRow(neg.id()));
        verdict.expect("negative: nothing from the bundle was registered",
                host.runnerOf(neg.deliveredClass()).isEmpty(), () -> "found " + host.runnerOf(neg.deliveredClass()));
    }

    /**
     * Installs two entries from the Store page in one batch, which is also what
     * proves the pairing goes by position in the delivery, then starts each from
     * the picker, which must not ask the launcher again.
     */
    void storePath(TestCourier courier) {
        List<SdnLiveCase> batch = List.of(cases.craftFromStore(), cases.blankFromStore());
        int before = screens.installRequests();
        screens.installFromStore(batch.stream().map(SdnLiveCase::id).toList());
        settleStoreInstall("store install");
        InstallActivity activity = screens.storeActivity();
        verdict.expect("store: the install finished", ScreenStates.isFinished(activity),
                () -> "activity " + activity);
        for (SdnLiveCase c : batch) {
            verdict.expect(c.id() + " (store): delivered once", courier.deliveriesNaming(c.id()) == 1,
                    () -> "deliveries " + courier.deliveriesNaming(c.id()));
            expectInstalledOnBothScreens("store", c);
            expectOwnModule("store", c);
        }
        for (SdnLiveCase c : batch) {
            screens.startFromPicker(c.id());
            expectStarted("store, then picker start", c);
            verdict.expect(c.id() + " (store, then picker start): not delivered again",
                    courier.deliveriesNaming(c.id()) == 1, () -> "deliveries " + courier.deliveriesNaming(c.id()));
        }
        verdict.expect("store: exactly one launcher request for the batch, none for the picker starts",
                screens.installRequests() == before + 1, () -> "requests " + (screens.installRequests() - before));
    }

    /** Installs and starts each picker entry from the picker alone. */
    void pickerPath(TestCourier courier) {
        for (SdnLiveCase c : List.of(cases.craftFromPicker(), cases.blankFromPicker())) {
            int before = screens.installRequests();
            screens.startFromPicker(c.id());
            boolean settled = Waits.until(() -> screens.pickerRow(c.id())
                    .map(s -> !ScreenStates.isInstalling(s)).orElse(false), INSTALL_WAIT);
            verdict.expect(c.id() + " (picker): the row settles", settled, () -> "row " + screens.pickerRow(c.id()));
            verdict.expect(c.id() + " (picker): no failure on the row",
                    screens.pickerRow(c.id()).map(s -> !ScreenStates.isFailed(s)).orElse(false),
                    () -> "row " + screens.pickerRow(c.id()));
            expectStarted("picker", c);
            expectInstalledOnBothScreens("picker", c);
            expectOwnModule("picker", c);
            verdict.expect(c.id() + " (picker): one launcher request, one delivery",
                    screens.installRequests() == before + 1 && courier.deliveriesNaming(c.id()) == 1,
                    () -> "requests " + (screens.installRequests() - before)
                            + ", deliveries " + courier.deliveriesNaming(c.id()));
        }
    }

    private void settleStoreInstall(String what) {
        boolean settled = Waits.until(() -> !ScreenStates.isInstalling(screens.storeActivity()), INSTALL_WAIT);
        verdict.expect(what + ": the install settles", settled, () -> "activity " + screens.storeActivity());
    }

    /**
     * The Store row and the picker row both say installed, and the ledger pairs
     * the delivered class with the entry it was requested as. The picker row is
     * what a regression shows first: it re-offers Install & start.
     */
    private void expectInstalledOnBothScreens(String path, SdnLiveCase c) {
        String tag = c.id() + " (" + path + "): ";
        verdict.expect(tag + "the Store row says installed",
                screens.storeRow(c.id()).map(ScreenStates::isInstalled).orElse(false),
                () -> "row " + screens.storeRow(c.id()));
        verdict.expect(tag + "the picker row says installed and offers no install",
                screens.pickerRow(c.id()).map(ScreenStates::isInstalled).orElse(false),
                () -> "row " + screens.pickerRow(c.id()));
        verdict.expect(tag + "the ledger pairs " + c.deliveredClass() + " with this entry",
                screens.ledgerRecord(c.deliveredClass()).map(r -> r.catalogueId().equals(c.id())).orElse(false),
                () -> "record " + screens.ledgerRecord(c.deliveredClass()));
    }

    /** The delivered script runs from a named module, in a layer of its own: (a). */
    private void expectOwnModule(String path, SdnLiveCase c) {
        Optional<Module> module = host.runnerOf(c.deliveredClass()).map(r -> r.getScript().getClass().getModule());
        verdict.expect(c.id() + " (" + path + "): loaded as module " + c.moduleName() + " in its own layer",
                module.map(m -> m.isNamed() && c.moduleName().equals(m.getName())
                        && m.getLayer() != null && m.getLayer() != ModuleLayer.boot()).orElse(false),
                () -> "module " + module.map(m -> m + " layer " + m.getLayer()).orElse("none: no runner"));
    }

    private void expectStarted(String path, SdnLiveCase c) {
        boolean started = Waits.until(() -> host.runnerOf(c.deliveredClass())
                .map(ScriptRunner::lastStartedAt).isPresent(), START_WAIT);
        verdict.expect(c.id() + " (" + path + "): the delivered script started", started,
                () -> "runner " + host.runnerOf(c.deliveredClass()).map(r -> r.getScriptName() + " started "
                        + r.lastStartedAt()).orElse("none"));
    }
}
