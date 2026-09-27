package com.botwithus.bot.cli.gui.pages.store;

import java.util.Comparator;
import java.util.Locale;

/** How the Store orders its list. Every order breaks ties by name, ignoring case. */
public enum StoreSort {

    FAVOURITES_FIRST("Favourites first"),
    NAME("Name A–Z"),
    AUTHOR("Author");

    private static final Comparator<StoreRow> BY_NAME =
            Comparator.comparing((StoreRow r) -> r.name().toLowerCase(Locale.ROOT));

    private final String label;

    StoreSort(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public Comparator<StoreRow> comparator() {
        return switch (this) {
            case FAVOURITES_FIRST -> Comparator.comparing((StoreRow r) -> !r.isFavourite()).thenComparing(BY_NAME);
            case NAME -> BY_NAME;
            case AUTHOR -> Comparator.comparing((StoreRow r) -> r.author().toLowerCase(Locale.ROOT))
                    .thenComparing(BY_NAME);
        };
    }
}
