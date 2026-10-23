package com.botwithus.bot.cli.sdn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FavouritesStoreTest {

    @TempDir
    Path home;

    /** Writes run on the calling thread, so each assertion sees the file as it was left. */
    private FavouritesStore store() {
        return new FavouritesStore(home, Runnable::run);
    }

    // Favourites -------------------------------------------------------------

    @Test
    void setFavourite_persistsAcrossInstances() {
        store().setFavourite("7", true);

        FavouritesStore reopened = store();

        assertTrue(reopened.isFavourite("7"));
        assertEquals(Set.of("7"), reopened.favourites());
    }

    @Test
    void setFavouriteFalse_removesItOnDisk() {
        FavouritesStore store = store();
        store.setFavourite("7", true);
        store.setFavourite("12", true);

        store.setFavourite("7", false);

        assertEquals(Set.of("12"), store().favourites());
    }

    @Test
    void toggleFavourite_flipsAndReportsTheNewState() {
        FavouritesStore store = store();

        assertTrue(store.toggleFavourite("7"));
        assertFalse(store.toggleFavourite("7"));
        assertFalse(store().isFavourite("7"));
    }

    @Test
    void favourites_isACopyTheCallerCannotChange() {
        FavouritesStore store = store();
        store.setFavourite("7", true);
        Set<String> favourites = store.favourites();

        assertThrows(UnsupportedOperationException.class, () -> favourites.add("12"));
    }

    // Seen -------------------------------------------------------------------

    /** The first catalogue a user ever opens is the baseline, not a wall of "new". */
    @Test
    void unseen_beforeAnythingWasEverMarkedSeen_isEmpty() {
        assertEquals(List.of(), store().unseen(List.of("7", "12")));
    }

    @Test
    void unseen_afterABaseline_listsOnlyIdsNotSeenBefore_inTheCallersOrder() {
        store().markSeen(List.of("7"));

        assertEquals(List.of("30", "12"), store().unseen(List.of("30", "7", "12")));
    }

    @Test
    void markSeen_anEmptyCatalogue_stillSetsTheBaseline() {
        store().markSeen(List.of());

        assertEquals(List.of("7"), store().unseen(List.of("7")));
    }

    @Test
    void markSeen_addsToWhatWasSeenBefore() {
        FavouritesStore store = store();
        store.markSeen(List.of("7"));
        store.markSeen(List.of("12"));

        assertEquals(List.of("30"), store().unseen(List.of("7", "12", "30")));
    }

    @Test
    void favouritesAndSeen_doNotOverwriteEachOther() {
        FavouritesStore store = store();
        store.setFavourite("7", true);
        store.markSeen(List.of("7", "12"));

        FavouritesStore reopened = store();

        assertEquals(Set.of("7"), reopened.favourites());
        assertEquals(List.of("30"), reopened.unseen(List.of("7", "12", "30")));
    }

    // Files ------------------------------------------------------------------

    @Test
    void save_createsTheBaseDirectory() {
        Path nested = home.resolve("missing").resolve("dir");

        new FavouritesStore(nested, Runnable::run).setFavourite("7", true);

        assertTrue(Files.isRegularFile(nested.resolve(FavouritesStore.FILE_NAME)));
    }

    @Test
    void load_corruptFile_startsEmptyAndTheNextSaveReplacesIt() throws IOException {
        Files.writeString(home.resolve(FavouritesStore.FILE_NAME), "{not json");

        FavouritesStore store = store();
        assertEquals(Set.of(), store.favourites());
        store.setFavourite("7", true);

        assertEquals(Set.of("7"), store().favourites());
    }

    @Test
    void load_wrongTypedValues_keepsTheValidIds() throws IOException {
        Files.writeString(home.resolve(FavouritesStore.FILE_NAME), """
                {"version":1,"favourites":["7",12,null,"30"],"seen":"all"}""");

        FavouritesStore store = store();

        assertEquals(Set.of("7", "30"), store.favourites());
        assertEquals(List.of(), store.unseen(List.of("99")), "an unreadable seen list is no baseline");
    }

    /** A save that fails costs the file, never the click: the choice stays in memory. */
    @Test
    void save_failure_keepsTheChoiceInMemory() throws IOException {
        Path blocked = home.resolve("not-a-directory");
        Files.writeString(blocked, "a file where the store directory should be");
        FavouritesStore store = new FavouritesStore(blocked, Runnable::run);

        store.setFavourite("7", true);

        assertTrue(store.isFavourite("7"));
    }

    /** Saves are handed to the writer, never done on the caller's (render) thread. */
    @Test
    void setFavourite_savesThroughTheWriter() {
        List<Runnable> queued = new ArrayList<>();
        FavouritesStore store = new FavouritesStore(home, queued::add);

        store.setFavourite("7", true);
        assertFalse(Files.exists(store.file()), "nothing may be written before the writer runs");
        queued.forEach(Runnable::run);

        assertTrue(store().isFavourite("7"));
    }
}
