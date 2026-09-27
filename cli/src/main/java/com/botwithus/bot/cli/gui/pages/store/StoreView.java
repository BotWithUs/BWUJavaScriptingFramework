package com.botwithus.bot.cli.gui.pages.store;

import com.botwithus.bot.api.ScriptCategory;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * One frame of the Store: the catalogue's rows, the list the query leaves, and the
 * state around them. Immutable.
 *
 * @param all         every catalogue script, in catalogue order
 * @param listed      the scripts that pass the query, in its order
 * @param favourites  the starred scripts, in the order they were starred; the query
 *                    does not touch them
 * @param categories  the categories present in the catalogue, by display name
 * @param isRefreshing a catalogue fetch is running
 * @param canInstall  this host can take a delivery from the launcher at all
 */
public record StoreView(
        StoreStatus status,
        StoreQuery query,
        List<StoreRow> all,
        List<StoreRow> listed,
        List<StoreRow> favourites,
        StoreCounts counts,
        List<ScriptCategory> categories,
        boolean isRefreshing,
        boolean canInstall,
        InstallActivity install) {

    public StoreView {
        all = List.copyOf(all);
        listed = List.copyOf(listed);
        favourites = List.copyOf(favourites);
        categories = List.copyOf(categories);
    }

    /**
     * Builds the frame from the catalogue's rows.
     *
     * @param rows            every catalogue script, each with its favourite flag set
     * @param favouriteOrder  the favourite ids in the order they were starred; ids the
     *                        catalogue no longer lists are skipped
     */
    public static StoreView of(StoreStatus status, StoreQuery query, List<StoreRow> rows,
                               Collection<String> favouriteOrder, boolean isRefreshing, boolean canInstall,
                               InstallActivity install) {
        List<StoreRow> listed = rows.stream().filter(query::admits).sorted(query.sort().comparator()).toList();
        Map<String, StoreRow> byId = rows.stream().collect(Collectors.toMap(StoreRow::id, Function.identity(),
                (first, second) -> first));
        List<StoreRow> favourites = favouriteOrder.stream().map(byId::get).filter(r -> r != null).toList();
        List<ScriptCategory> categories = rows.stream().map(StoreRow::category).distinct()
                .sorted(Comparator.comparing(ScriptCategory::getDisplayName)).toList();
        return new StoreView(status, query, rows, listed, favourites, StoreCounts.of(rows), categories,
                isRefreshing, canInstall, install);
    }

    /** The catalogue script {@code id}, listed or not. */
    public Optional<StoreRow> find(String id) {
        return all.stream().filter(r -> r.id().equals(id)).findFirst();
    }

    /** Whether there is a catalogue to list, fresh or stale. */
    public boolean hasCatalogue() {
        return switch (status) {
            case StoreStatus.Ready ignored -> true;
            case StoreStatus.Loading ignored -> false;
            case StoreStatus.Unavailable ignored -> false;
        };
    }
}
