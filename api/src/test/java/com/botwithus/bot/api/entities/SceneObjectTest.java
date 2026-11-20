package com.botwithus.bot.api.entities;

import com.botwithus.bot.api.model.SceneObjectInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SceneObject#isHidden()} and {@link SceneObject#animationId()} must read the
 * snapshot row, not a constant. Both rows here go through the real {@link SceneObjects}
 * query path, so the test fails if either the mapping or the accessor drops the field.
 */
class SceneObjectTest {

    private static final int TREE = 1276;
    private static final int FIRE = 26185;
    private static final int FIRE_ANIMATION = 475;
    private static final int X = 3200;
    private static final int Y = 3200;

    @Test
    void isHidden_hiddenRowWithIncludeHidden_readsTheSnapshotFlag() {
        SceneFixture scene = new SceneFixture().withLocations(
                SceneFixture.loc(TREE, X, Y, SceneObjectInfo.NO_ANIMATION, SceneFixture.LOC_FLAG_HIDDEN),
                SceneFixture.loc(FIRE, X + 1, Y, FIRE_ANIMATION, 0));

        List<SceneObject> all = new SceneObjects(scene.api).query().includeHidden().all();

        assertEquals(2, all.size());
        assertTrue(all.get(0).isHidden(), "hidden flag on the row must reach the wrapper");
        assertFalse(all.get(1).isHidden());
    }

    @Test
    void query_withoutIncludeHidden_dropsHiddenRows() {
        SceneFixture scene = new SceneFixture().withLocations(
                SceneFixture.loc(TREE, X, Y, SceneObjectInfo.NO_ANIMATION, SceneFixture.LOC_FLAG_HIDDEN),
                SceneFixture.loc(FIRE, X + 1, Y, FIRE_ANIMATION, 0));

        List<SceneObject> visible = new SceneObjects(scene.api).query().all();

        assertEquals(1, visible.size());
        assertEquals(FIRE, visible.getFirst().typeId());
    }

    @Test
    void animationId_animatingRow_readsTheSnapshotAnimation() {
        SceneFixture scene = new SceneFixture().withLocations(
                SceneFixture.loc(FIRE, X, Y, FIRE_ANIMATION, 0),
                SceneFixture.loc(TREE, X + 1, Y, SceneObjectInfo.NO_ANIMATION, 0));

        List<SceneObject> all = new SceneObjects(scene.api).query().all();

        assertEquals(FIRE_ANIMATION, all.get(0).animationId());
        assertEquals(SceneObjectInfo.NO_ANIMATION, all.get(1).animationId());
    }
}
