package com.botwithus.bot.api.entities;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.WorldMapElement;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The world map facade: the area keys it writes, and its sort and post-filter. */
class WorldMapElementsTest {

    private final GameAPI api = mock(GameAPI.class);
    private final WorldMapElements facade = new WorldMapElements(api);

    @Test
    void noAreaKey_byDefault_inAreaAndAllAreasWriteIt() {
        when(api.queryWorldMapElements(anyMap())).thenReturn(List.of());

        facade.query().all();
        facade.query().inArea(231).all();
        facade.query().allAreas().all();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> sent = ArgumentCaptor.forClass(Map.class);
        verify(api, times(3)).queryWorldMapElements(sent.capture());
        assertFalse(sent.getAllValues().get(0).containsKey(WorldMapElements.AREA_KEY));
        assertEquals(231, sent.getAllValues().get(1).get(WorldMapElements.AREA_KEY));
        assertEquals(WorldMapElements.ALL_AREAS, sent.getAllValues().get(2).get(WorldMapElements.AREA_KEY));
    }

    @Test
    void nearAndSort_nearestIsChebyshevClosest() {
        when(api.queryWorldMapElements(anyMap())).thenReturn(List.of(at(1, 3260, 3222), at(2, 3233, 3222),
                at(3, 3240, 3240)));

        WorldMapElement nearest = facade.query().near(3230, 3220, 64).nearest();

        assertEquals(2, nearest.id());
    }

    @Test
    void postFilter_appliesAfterTheQuery() {
        when(api.queryWorldMapElements(anyMap())).thenReturn(List.of(at(1, 0, 0), at(2, 0, 0)));

        assertEquals(2, facade.query().filter(e -> e.id() == 2).first().id());
    }

    private static WorldMapElement at(int id, int x, int y) {
        return new WorldMapElement(id, x, y, 0, 0, -1, id, "", "", "", -1, -1, -1, -1,
                List.of(), List.of(), List.of(), WorldMapElements.MAIN_AREA_ID, 0);
    }
}
