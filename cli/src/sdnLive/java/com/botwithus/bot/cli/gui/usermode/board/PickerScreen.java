package com.botwithus.bot.cli.gui.usermode.board;

import com.botwithus.bot.cli.sdn.StoreRegistrar;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.core.sdn.SdnCatalogueEntry;
import com.botwithus.bot.core.sdn.SdnCatalogueRefresher;
import com.botwithus.bot.core.sdn.SdnInstallResult;
import com.botwithus.bot.core.sdn.StoreCopies;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The "Start a script" picker's subscriptions group, for the SDN live check:
 * {@link LiveSubscriptions} wired the way {@link LiveClientBoard} wires it, with
 * the reads and the click the picker makes.
 *
 * <p>It lives in this package only because {@code LiveSubscriptions} is
 * package-private. It is in the {@code sdnLive} source set, which nothing ships.
 */
public final class PickerScreen {

    private final LiveSubscriptions subscriptions;

    /**
     * @param connections the host's clients, read fresh on every call
     * @param catalogue   the catalogue loop the Store page shares
     * @param installer   asks the launcher for catalogue scripts and loads them; blocks
     * @param registrar   registers a delivery on a client, a new instance per client
     * @param storeCopies recognises a loaded Store copy by its install record
     */
    public PickerScreen(Supplier<? extends Collection<Connection>> connections, SdnCatalogueRefresher catalogue,
                        Function<List<SdnCatalogueEntry>, SdnInstallResult> installer, StoreRegistrar registrar,
                        StoreCopies storeCopies) {
        // No local scripts: the check's premise is that only the Store can supply
        // these entries, so a local copy would decide the very state under test.
        this.subscriptions = new LiveSubscriptions(connections, catalogue, installer, registrar, storeCopies, List::of,
                task -> Thread.ofVirtual().name("sdn-subscription-install").start(task));
    }

    /** The row {@code scriptId} shows on {@code clientId}'s picker, if the group lists it. */
    public Optional<SubscriptionState> state(String clientId, String scriptId) {
        return subscriptions.group(clientId).entries().stream()
                .filter(e -> e.id().equals(scriptId))
                .map(SubscriptionEntry::state)
                .findFirst();
    }

    /** What the row's click does: start it, installing it first when it needs one. */
    public void start(String clientId, String scriptId) {
        subscriptions.start(clientId, scriptId);
    }
}
