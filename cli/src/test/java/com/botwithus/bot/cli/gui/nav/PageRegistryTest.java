package com.botwithus.bot.cli.gui.nav;

import com.botwithus.bot.cli.gui.AppMode;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageRegistryTest {

    /** A page with nothing to draw; the registry only reads its id. */
    private record StubPage(PageId id) implements Page {
        @Override
        public void render() {
        }
    }

    private static List<Page> pagesFor(PageId... ids) {
        return Arrays.stream(ids).<Page>map(StubPage::new).toList();
    }

    private static PageRegistry everyPageShuffled() {
        List<Page> pages = new ArrayList<>(pagesFor(PageId.values()));
        Collections.reverse(pages);
        return new PageRegistry(pages);
    }

    private static List<PageId> ids(List<Page> pages) {
        return pages.stream().map(Page::id).toList();
    }

    @Test
    void sections_followTheDesign_withTheOnlineBlockAndSettingsLast() {
        PageRegistry registry = everyPageShuffled();

        assertAll(
                () -> assertEquals(List.of(NavSection.CLIENTS, NavSection.ON_THIS_PC, NavSection.ONLINE,
                        NavSection.FOOTER), registry.sections()),
                () -> assertEquals(List.of(PageId.DASHBOARD, PageId.CLIENTS, PageId.CONNECTIONS, PageId.GROUPS),
                        ids(registry.pagesIn(NavSection.CLIENTS))),
                () -> assertEquals(List.of(PageId.INSTALLED, PageId.MANAGEMENT),
                        ids(registry.pagesIn(NavSection.ON_THIS_PC))),
                () -> assertEquals(List.of(PageId.STORE), ids(registry.pagesIn(NavSection.ONLINE))),
                () -> assertEquals(List.of(PageId.SETTINGS), ids(registry.pagesIn(NavSection.FOOTER))),
                () -> assertEquals(List.of(PageId.values()), ids(registry.pages())));
    }

    @Test
    void onlyTheOnlineBlockAndSettings_arePinnedToTheBottom() {
        assertAll(
                () -> assertFalse(NavSection.CLIENTS.isPinnedToBottom()),
                () -> assertFalse(NavSection.ON_THIS_PC.isPinnedToBottom()),
                () -> assertTrue(NavSection.ONLINE.isPinnedToBottom()),
                () -> assertTrue(NavSection.ONLINE.isBoxed()),
                () -> assertTrue(NavSection.FOOTER.isPinnedToBottom()),
                () -> assertFalse(NavSection.FOOTER.hasHeading()));
    }

    @Test
    void aSectionWithNoPages_isLeftOut() {
        PageRegistry registry = new PageRegistry(pagesFor(PageId.CLIENTS, PageId.SETTINGS));

        assertEquals(List.of(NavSection.CLIENTS, NavSection.FOOTER), registry.sections());
    }

    @Test
    void advancedOpensOnClients() {
        PageRegistry registry = everyPageShuffled();

        assertAll(
                () -> assertEquals(PageId.CLIENTS, registry.selected()),
                () -> assertEquals(PageId.CLIENTS, registry.bodyFor(AppMode.ADVANCED).id()));
    }

    @Test
    void normalAndAdvanced_drawTheSameClientsPage() {
        PageRegistry registry = everyPageShuffled();

        assertSame(registry.bodyFor(AppMode.NORMAL), registry.bodyFor(AppMode.ADVANCED));
    }

    @Test
    void selectingAPage_changesAdvancedOnly() {
        PageRegistry registry = everyPageShuffled();

        assertTrue(registry.select(PageId.CONNECTIONS));

        assertAll(
                () -> assertEquals(PageId.CONNECTIONS, registry.bodyFor(AppMode.ADVANCED).id()),
                () -> assertEquals(PageId.CLIENTS, registry.bodyFor(AppMode.NORMAL).id()));
    }

    @Test
    void theSelection_survivesATripToNormalAndBack() {
        PageRegistry registry = everyPageShuffled();
        registry.select(PageId.INSTALLED);

        // F12 to Normal draws the Clients page for a while, then F12 back again.
        registry.bodyFor(AppMode.NORMAL);
        registry.bodyFor(AppMode.NORMAL);

        assertAll(
                () -> assertEquals(PageId.INSTALLED, registry.selected()),
                () -> assertEquals(PageId.INSTALLED, registry.bodyFor(AppMode.ADVANCED).id()));
    }

    @Test
    void selectingAPageThatIsNotRegistered_changesNothing() {
        PageRegistry registry = new PageRegistry(pagesFor(PageId.CLIENTS, PageId.DASHBOARD));
        registry.select(PageId.DASHBOARD);

        assertFalse(registry.select(PageId.STORE));

        assertEquals(PageId.DASHBOARD, registry.bodyFor(AppMode.ADVANCED).id());
    }

    @Test
    void twoPagesForOneId_areRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new PageRegistry(pagesFor(PageId.CLIENTS, PageId.GROUPS, PageId.GROUPS)));
    }

    @Test
    void aRegistryWithoutTheClientsPage_isRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new PageRegistry(pagesFor(PageId.DASHBOARD, PageId.SETTINGS)));
    }
}
