package com.botwithus.bot.cli.gui.pages.store;

import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.core.sdn.SdnCatalogueEntry.Pricing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreSelectionTest {

    private static StoreRow row(String id, RowState state) {
        return new StoreRow(id, id, "author", "", "", ScriptCategory.UTILITY, Pricing.FREE, "", "1.0", "a." + id,
                true, false, false, state, false, false);
    }

    private static StoreRow notInstalled(String id) {
        return row(id, new RowState.NotInstalled(false));
    }

    @Test
    void toggle_twice_unticks() {
        StoreSelection selection = new StoreSelection();
        StoreRow a = notInstalled("a");

        selection.toggle(a);
        selection.toggle(a);

        assertTrue(selection.isEmpty());
    }

    @Test
    void retainInstallable_dropsTicksThatLandedLeftOrAreOnTheirWay() {
        StoreSelection selection = new StoreSelection();
        List.of(notInstalled("landed"), notInstalled("gone"), notInstalled("busy"), notInstalled("kept"))
                .forEach(selection::toggle);

        selection.retainInstallable(List.of(row("landed", new RowState.Installed()),
                notInstalled("busy").withInstalling(true), notInstalled("kept")));

        assertEquals(List.of("kept"), selection.ids());
    }
}
