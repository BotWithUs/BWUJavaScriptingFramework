package com.botwithus.bot.cli.gui.pages.store;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The rows ticked for a batch install, in the order they were ticked. Only a row
 * that can be installed can be ticked, and a tick that stops being installable (it
 * landed, it left the catalogue, or it is on its way) is dropped, so the install
 * bar never counts something it would not install. Render thread only.
 */
public final class StoreSelection {

    private final Set<String> picked = new LinkedHashSet<>();

    /** Ticks {@code row}, or unticks it if it is ticked. A row that cannot be installed is never ticked. */
    public void toggle(StoreRow row) {
        if (!picked.remove(row.id()) && row.isInstallable()) {
            picked.add(row.id());
        }
    }

    public boolean isPicked(String id) {
        return picked.contains(id);
    }

    public int size() {
        return picked.size();
    }

    public boolean isEmpty() {
        return picked.isEmpty();
    }

    /** The ticked ids, in the order they were ticked. */
    public List<String> ids() {
        return List.copyOf(picked);
    }

    public void clear() {
        picked.clear();
    }

    /** Drops every tick on a row that is no longer in {@code catalogue} or no longer installable. */
    public void retainInstallable(List<StoreRow> catalogue) {
        Set<String> installable = catalogue.stream().filter(StoreRow::isInstallable).map(StoreRow::id)
                .collect(Collectors.toSet());
        picked.retainAll(installable);
    }
}
