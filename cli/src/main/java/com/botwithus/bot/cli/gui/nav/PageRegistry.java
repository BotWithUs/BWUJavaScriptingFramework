package com.botwithus.bot.cli.gui.nav;

import com.botwithus.bot.cli.gui.AppMode;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The app's pages and which one Advanced mode shows.
 *
 * <p>Advanced adds, it never replaces: Normal mode's whole screen is the
 * {@link PageId#CLIENTS} page, and Advanced shows the same page object next to
 * the sidebar, opening on it. The selection belongs to the registry, not to a
 * mode, so switching to Normal and back returns to the page you left.</p>
 *
 * <p>Render thread only.</p>
 */
public final class PageRegistry {

    /** The page Advanced opens on, and the page Normal mode always shows. */
    public static final PageId DEFAULT_PAGE = PageId.CLIENTS;

    private final Map<PageId, Page> pages = new EnumMap<>(PageId.class);
    private PageId selected = DEFAULT_PAGE;

    /**
     * @param pages at most one page per id, in any order; must include {@link #DEFAULT_PAGE}
     * @throws IllegalArgumentException when an id repeats or the Clients page is missing
     */
    public PageRegistry(List<? extends Page> pages) {
        for (Page page : pages) {
            if (this.pages.putIfAbsent(page.id(), page) != null) {
                throw new IllegalArgumentException("Two pages registered for " + page.id());
            }
        }
        if (!this.pages.containsKey(DEFAULT_PAGE)) {
            throw new IllegalArgumentException(
                    "No " + DEFAULT_PAGE + " page: Normal mode shows it and Advanced opens on it");
        }
    }

    /** Every registered page, in sidebar order. */
    public List<Page> pages() {
        return List.copyOf(pages.values());
    }

    /** The sections that hold at least one page, in sidebar order. */
    public List<NavSection> sections() {
        List<NavSection> out = new ArrayList<>();
        for (PageId id : pages.keySet()) {
            if (!out.contains(id.section())) {
                out.add(id.section());
            }
        }
        return List.copyOf(out);
    }

    /** The pages in {@code section}, in sidebar order. */
    public List<Page> pagesIn(NavSection section) {
        return pages.values().stream().filter(p -> p.id().section() == section).toList();
    }

    public Optional<Page> find(PageId id) {
        return Optional.ofNullable(pages.get(id));
    }

    /** The page Advanced mode shows. */
    public PageId selected() {
        return selected;
    }

    /**
     * Makes {@code id} the page Advanced mode shows.
     *
     * @return false, changing nothing, when no page is registered for {@code id}
     */
    public boolean select(PageId id) {
        if (!pages.containsKey(id)) {
            return false;
        }
        selected = id;
        return true;
    }

    /** The page whose body fills the window in {@code mode}. */
    public Page bodyFor(AppMode mode) {
        return switch (mode) {
            case NORMAL -> pages.get(DEFAULT_PAGE);
            case ADVANCED -> pages.get(selected);
        };
    }
}
