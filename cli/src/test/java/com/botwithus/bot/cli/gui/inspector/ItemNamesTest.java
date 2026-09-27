package com.botwithus.bot.cli.gui.inspector;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.gameval.GamevalIndex;
import com.botwithus.bot.api.gameval.GamevalType;
import com.botwithus.bot.api.model.ItemType;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The line under an item-id field: the item's gameval first, its display name as the fallback. */
class ItemNamesTest {

    private static final int YEW_LOGS = 1515;
    private static final int COINS = 995;
    private static final int NOTHING = 4;

    private static ItemType item(int id, String name) {
        return new ItemType(id, name, false, false, 0, 0, 0, -1, -1, false, List.of(), List.of(), Map.of());
    }

    private static GameAPI client(GamevalIndex gamevals) {
        GameAPI api = mock(GameAPI.class);
        when(api.gamevals()).thenReturn(gamevals);
        when(api.getItemType(YEW_LOGS)).thenReturn(item(YEW_LOGS, "Yew logs"));
        when(api.getItemType(COINS)).thenReturn(item(COINS, "Coins"));
        when(api.getItemType(NOTHING)).thenReturn(item(NOTHING, "null"));
        return api;
    }

    @Test
    void theGamevalWins_whenAnIndexIsDeployed() {
        GamevalIndex gamevals = mock(GamevalIndex.class);
        when(gamevals.gameval(GamevalType.ITEM, YEW_LOGS)).thenReturn(Optional.of("YEW_LOGS"));
        when(gamevals.gameval(GamevalType.ITEM, COINS)).thenReturn(Optional.empty());
        when(gamevals.gameval(GamevalType.ITEM, NOTHING)).thenReturn(Optional.empty());
        ItemNames names = new ItemNames(() -> Optional.of(client(gamevals)));

        assertAll(
                () -> assertEquals(Optional.of("YEW_LOGS"), names.of(YEW_LOGS)),
                () -> assertEquals(Optional.of("Coins"), names.of(COINS), "no gameval: the display name"),
                () -> assertEquals(Optional.empty(), names.of(NOTHING), "the cache's \"null\" is no name"));
    }

    @Test
    void withNoIndexDeployed_theDisplayNameStandsIn() {
        ItemNames names = new ItemNames(() -> Optional.of(client(GamevalIndex.empty())));

        assertEquals(Optional.of("Yew logs"), names.of(YEW_LOGS));
    }

    @Test
    void answersAreRemembered() {
        GameAPI api = client(GamevalIndex.empty());
        ItemNames names = new ItemNames(() -> Optional.of(api));

        names.of(YEW_LOGS);
        names.of(YEW_LOGS);

        verify(api, times(1)).getItemType(YEW_LOGS);
    }

    @Test
    void withNoClient_nothingIsRemembered_soTheFirstConnectionCanAnswer() {
        AtomicReference<Optional<GameAPI>> connected = new AtomicReference<>(Optional.empty());
        ItemNames names = new ItemNames(connected::get);

        assertEquals(Optional.empty(), names.of(YEW_LOGS));
        connected.set(Optional.of(client(GamevalIndex.empty())));

        assertEquals(Optional.of("Yew logs"), names.of(YEW_LOGS));
    }
}
