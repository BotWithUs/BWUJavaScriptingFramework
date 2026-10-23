package com.botwithus.bot.core.sdn;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.core.runtime.SdnDelivery;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreShelfTest {

    private static final String ID = "42";

    public static final class Shelved implements BotScript {
        @Override public void onStart(ScriptContext context) { }
        @Override public int onLoop() { return 0; }
        @Override public void onStop() { }
    }

    public static final class Broken implements BotScript {
        @Override public void onStart(ScriptContext context) { }
        @Override public int onLoop() { return 0; }
        @Override public void onStop() { }
    }

    private static SdnDelivery.Jar jar(BotScript first, Supplier<BotScript> copies) {
        return new SdnDelivery.Jar(0, List.of(first), List.of(copies));
    }

    /** Fails if the shelf hands back the delivered object: two clients would share its per-run state. */
    @Test
    void freshCopies_areNewInstancesEachTime() {
        StoreShelf shelf = new StoreShelf();
        BotScript delivered = new Shelved();
        shelf.stock(Optional.of(ID), jar(delivered, Shelved::new));

        BotScript first = shelf.freshCopies().getFirst();
        BotScript second = shelf.freshCopies().getFirst();

        assertInstanceOf(Shelved.class, first);
        assertNotSame(delivered, first);
        assertNotSame(first, second);
        assertEquals(Set.of(ID), shelf.catalogueIds());
    }

    @Test
    void aCopyWhoseConstructorThrows_isSkippedAndTheRestStillCome() {
        StoreShelf shelf = new StoreShelf();
        shelf.stock(Optional.of(ID), jar(new Shelved(), Shelved::new));
        shelf.stock(Optional.of("43"), jar(new Broken(), () -> {
            throw new IllegalStateException("constructor threw");
        }));

        List<BotScript> copies = shelf.freshCopies();

        assertEquals(1, copies.size());
        assertInstanceOf(Shelved.class, copies.getFirst());
    }

    @Test
    void anUnpairedDelivery_isShelvedUnderNoCatalogueId() {
        StoreShelf shelf = new StoreShelf();
        shelf.stock(Optional.empty(), jar(new Shelved(), Shelved::new));

        assertTrue(shelf.catalogueIds().isEmpty());
        assertEquals(Set.of(Shelved.class.getName()), shelf.classes());
    }
}
