package com.botwithus.bot.cli.gui.pages.installed;

import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.cli.gui.nav.PageId;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/** Where Install again and Update lead: the Store, with the script picked when its catalogue id is known. */
class InstalledStateTest {

    private final List<PageId> pages = new ArrayList<>();
    private final List<String> shownInStore = new ArrayList<>();
    private final InstalledState state = new InstalledState(mock(InstalledModel.class), pages::add,
            shownInStore::add);

    @Test
    void installAgainOrUpdate_onAStoreScript_showsThatScriptInTheStore() {
        state.showInStore(Rows.store("Divination", ScriptCategory.DIVINATION));

        assertEquals(List.of("divination"), shownInStore);
        assertEquals(List.of(), pages, "showing the script switches page itself");
    }

    @Test
    void aScriptWithNoCatalogueId_opensTheStoreAsItWas() {
        InstalledScript unknown = new InstalledScript("X", Rows.local("X", ScriptCategory.UTILITY, "x.jar").identity(),
                new Provenance(ScriptSource.STORE, false, Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty()), List.of(), Set.of(), List.of());

        state.showInStore(unknown);

        assertEquals(List.of(), shownInStore);
        assertEquals(List.of(PageId.STORE), pages);
    }
}
