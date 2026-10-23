package com.botwithus.bot.cli.gui;

import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.SettingKeys;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Animations follow the "Reduce motion" setting: with it off they move by frame
 * time, with it on they snap. Driven through a real settings file and a
 * stand-in frame clock, so no ImGui context is needed.
 */
class MotionTest {

    private static final float EPSILON = 1e-5f;
    /** One frame of 0.1 s at speed 5 covers half the distance. */
    private static final double FRAME_S = 0.1;
    private static final float SPEED = 5f;
    private static final float DURATION_S = 0.2f;
    /** A quarter of a 1 Hz pulse: the sine's peak. */
    private static final double QUARTER_TURN_S = 0.25;

    @TempDir
    Path dir;

    private HostSettings settings;
    private Motion motion;

    @BeforeEach
    void open() {
        settings = HostSettings.open(dir);
        motion = Motion.following(settings, new Motion.FrameClock(() -> FRAME_S, () -> QUARTER_TURN_S));
    }

    @AfterEach
    void close() {
        settings.close();
    }

    @Test
    void withFullMotion_aFadeMovesPartWayEachFrame() {
        motion.step("fade", 0f, SPEED);

        assertEquals(0.5f, motion.step("fade", 1f, SPEED), EPSILON);
        assertEquals(0.75f, motion.step("fade", 1f, SPEED), EPSILON);
    }

    @Test
    void withReducedMotion_aFadeLandsOnItsTargetAtOnce() {
        motion.step("fade", 0f, SPEED);
        settings.set(SettingKeys.REDUCE_MOTION, true);

        assertTrue(motion.isReduced());
        assertEquals(1f, motion.step("fade", 1f, SPEED), EPSILON);
    }

    @Test
    void turningReducedMotionOff_resumesEasingFromWhereTheSnapLeftIt() {
        settings.set(SettingKeys.REDUCE_MOTION, true);
        motion.step("fade", 1f, SPEED);
        settings.set(SettingKeys.REDUCE_MOTION, false);

        assertFalse(motion.isReduced());
        assertEquals(0.5f, motion.step("fade", 0f, SPEED), EPSILON);
    }

    @Test
    void aPulseMovesWithFullMotion_andHoldsStillWhenReduced() {
        float moving = motion.pulse(1.0);
        settings.set(SettingKeys.REDUCE_MOTION, true);

        assertAll(
                () -> assertEquals(1f, moving, EPSILON),
                () -> assertEquals(0.5f, motion.pulse(1.0), EPSILON));
    }

    @Test
    void aSlideEasesWithFullMotion_andIsOutOrInWhenReduced() {
        float easing = motion.ease(0.5f);
        settings.set(SettingKeys.REDUCE_MOTION, true);

        assertAll(
                () -> assertEquals(0.875f, easing, EPSILON),
                () -> assertEquals(0f, motion.ease(0f), EPSILON),
                () -> assertEquals(1f, motion.ease(0.01f), EPSILON));
    }

    @Test
    void aDrawerAdvancesByFrameTime_andJumpsToItsEndWhenReduced() {
        float opening = motion.advance(0f, true, DURATION_S);
        float closing = motion.advance(1f, false, DURATION_S);
        settings.set(SettingKeys.REDUCE_MOTION, true);

        assertAll(
                () -> assertEquals(0.5f, opening, EPSILON),
                () -> assertEquals(0.5f, closing, EPSILON),
                () -> assertEquals(1f, motion.advance(0f, true, DURATION_S), EPSILON),
                () -> assertEquals(0f, motion.advance(1f, false, DURATION_S), EPSILON));
    }
}
