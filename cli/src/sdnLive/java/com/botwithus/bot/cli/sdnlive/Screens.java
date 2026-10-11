package com.botwithus.bot.cli.sdnlive;

import com.botwithus.bot.cli.gui.pages.store.InstallActivity;
import com.botwithus.bot.cli.gui.pages.store.LiveStoreModel;
import com.botwithus.bot.cli.gui.pages.store.RowState;
import com.botwithus.bot.cli.gui.pages.store.StoreCatalogue;
import com.botwithus.bot.cli.gui.pages.store.StoreQuery;
import com.botwithus.bot.cli.gui.pages.store.StoreRow;
import com.botwithus.bot.cli.gui.pages.store.StoreView;
import com.botwithus.bot.cli.gui.usermode.board.PickerScreen;
import com.botwithus.bot.cli.gui.usermode.board.SubscriptionState;
import com.botwithus.bot.cli.sdn.FavouritesStore;
import com.botwithus.bot.core.sdn.InstalledSdnScript;
import com.botwithus.bot.core.sdn.SdnCatalogueEntry;
import com.botwithus.bot.core.sdn.SdnCatalogueRefresher;
import com.botwithus.bot.core.sdn.SdnCatalogueSource;
import com.botwithus.bot.core.sdn.SdnInstallResult;
import com.botwithus.bot.core.sdn.SdnInstaller;
import com.botwithus.bot.core.sdn.StoreCopies;

import java.time.Duration;
import java.time.InstantSource;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Both install screens, built over the same parts the GUI builds them over:
 * one catalogue loop, one installer and its ledger, shared by the Store page
 * and the picker. What a click on either does, this calls; what either shows,
 * this reads.
 *
 * <p>The one addition is a counter on the installer, which is how the check
 * knows whether a click asked the launcher for a script.
 */
final class Screens {

    private final AtomicInteger installRequests = new AtomicInteger();
    private final SdnCatalogueRefresher catalogue = StoreCatalogue.refresherFor(new SdnCatalogueSource());
    private final SdnInstaller installer = new SdnInstaller();
    private final String clientId;
    private final LiveStoreModel store;
    private final PickerScreen picker;

    Screens(LiveHost host) {
        this.clientId = host.clientId();
        Function<List<SdnCatalogueEntry>, SdnInstallResult> counted = entries -> {
            installRequests.incrementAndGet();
            return installer.install(entries);
        };
        StoreCopies copies = StoreCopies.of(installer.ledger());
        // No local scripts, as in PickerScreen: only the Store may supply these entries.
        this.store = new LiveStoreModel(catalogue, counted, copies, installer::isDeliveryEnabled,
                FavouritesStore.inUserHome(), host.context()::getConnections, List::of,
                task -> Thread.ofVirtual().name("sdn-store-install").start(task), InstantSource.system());
        this.picker = new PickerScreen(host.context()::getConnections, catalogue, counted, copies);
    }

    /** Asks for the catalogue and waits until both screens list every one of {@code ids}. */
    boolean awaitListed(Collection<String> ids, Duration timeout) {
        catalogue.requestNow();
        return Waits.until(() -> ids.stream().allMatch(id -> storeRow(id).isPresent() && pickerRow(id).isPresent()),
                timeout);
    }

    /** How many times either screen has asked the launcher for scripts. */
    int installRequests() {
        return installRequests.get();
    }

    // ── The Store page ─────────────────────────────────────────────────────

    /** The Store's Install button, for one row or a selection. */
    void installFromStore(List<String> ids) {
        store.install(ids);
    }

    Optional<RowState> storeRow(String id) {
        return storeView().find(id).map(StoreRow::state);
    }

    /** The Store's install banner. */
    InstallActivity storeActivity() {
        return storeView().install();
    }

    private StoreView storeView() {
        return store.view(StoreQuery.DEFAULT);
    }

    // ── The picker ─────────────────────────────────────────────────────────

    /** The picker's click on a "Your subscriptions" row. */
    void startFromPicker(String id) {
        picker.start(clientId, id);
    }

    Optional<SubscriptionState> pickerRow(String id) {
        return picker.state(clientId, id);
    }

    // ── What the Store installed ───────────────────────────────────────────

    /** The ledger's record of the delivered class {@code className}. */
    Optional<InstalledSdnScript> ledgerRecord(String className) {
        return installer.ledger().find(className);
    }
}
