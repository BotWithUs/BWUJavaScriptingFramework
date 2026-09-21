package com.botwithus.bot.api.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SceneObjectInfoTest {

    private static final int LOC_ID = 1276;
    private static final int TILE_X = 3200;
    private static final int TILE_Y = 3201;
    private static final int SHAPE = 10;
    private static final int ROTATION = 2;

    @Test
    void compatConstructor_noShapeOrRotation_readsBackTheUnknownSentinels() {
        SceneObjectInfo info = new SceneObjectInfo(
                LOC_ID, LOC_ID, LOC_ID, TILE_X, TILE_Y, 0, "Tree", List.of());

        assertEquals(SceneObjectInfo.UNKNOWN_SHAPE, info.shape());
        assertEquals(SceneObjectInfo.UNKNOWN_ROTATION, info.rotation());
    }

    @Test
    void canonicalConstructor_shapeAndRotation_areKeptInTheirOwnSlots() {
        SceneObjectInfo info = new SceneObjectInfo(
                LOC_ID, LOC_ID, LOC_ID, TILE_X, TILE_Y, 0, "Tree", List.of(), SHAPE, ROTATION);

        assertEquals(SHAPE, info.shape());
        assertEquals(ROTATION, info.rotation());
    }
}
