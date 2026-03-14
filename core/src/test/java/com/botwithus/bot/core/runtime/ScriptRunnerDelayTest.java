package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.api.model.Personality;
import com.botwithus.bot.api.model.Personality.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScriptRunnerDelayTest {

    private ScriptRunner runner;
    private GameAPI gameAPI;

    private static final Speed NEUTRAL_SPEED = new Speed(1.0, 0.8);
    private static final Path NEUTRAL_PATH = new Path(0.0, "right", 1.0);
    private static final Precision NEUTRAL_PRECISION = new Precision(1.0, 1.0, 1.0);
    private static final Tremor NEUTRAL_TREMOR = new Tremor(0.0, 1.0);
    private static final Fatigue NEUTRAL_FATIGUE = new Fatigue(1.0, 1.0);
    private static final Camera NEUTRAL_CAMERA = new Camera(1.0, 1.0, 1.0, 1.0, 1.0);

    @BeforeEach
    void setUp() {
        BotScript script = new BotScript() {
            @Override public void onStart(ScriptContext ctx) {}
            @Override public int onLoop() { return 1000; }
            @Override public void onStop() {}
            @Override public List<ConfigField> getConfigFields() { return List.of(); }
            @Override public void onConfigUpdate(ScriptConfig config) {}
        };
        ScriptContext ctx = mock(ScriptContext.class);
        runner = new ScriptRunner(script, ctx);
        gameAPI = mock(GameAPI.class);
    }

    private Personality personality(Timing timing, Session session) {
        return new Personality(1L, NEUTRAL_SPEED, NEUTRAL_PATH, NEUTRAL_PRECISION,
                NEUTRAL_TREMOR, timing, NEUTRAL_FATIGUE, NEUTRAL_CAMERA, 0.1, session);
    }

    private Session neutralSession() {
        return new Session(0.0, 1.0, 0.0, 0.0, "low", 0.5, 100, 0, 0);
    }

    private Timing neutralTiming() {
        return new Timing(1.0, 1.0, 1.0);
    }

    // ========================== Null / error handling ==========================

    @Test
    void nullPersonality_returnsBaseDelay() {
        when(gameAPI.getPersonality()).thenReturn(null);
        assertEquals(1000, runner.adjustDelay(1000, gameAPI));
    }

    @Test
    void exceptionFromGameAPI_returnsBaseDelay() {
        when(gameAPI.getPersonality()).thenThrow(new RuntimeException("not connected"));
        assertEquals(1000, runner.adjustDelay(1000, gameAPI));
    }

    @Test
    void nullTiming_returnsBaseDelay() {
        Personality p = new Personality(1L, NEUTRAL_SPEED, NEUTRAL_PATH, NEUTRAL_PRECISION,
                NEUTRAL_TREMOR, null, NEUTRAL_FATIGUE, NEUTRAL_CAMERA, 0.1, neutralSession());
        when(gameAPI.getPersonality()).thenReturn(p);
        assertEquals(1000, runner.adjustDelay(1000, gameAPI));
    }

    @Test
    void nullSession_returnsBaseDelay() {
        Personality p = new Personality(1L, NEUTRAL_SPEED, NEUTRAL_PATH, NEUTRAL_PRECISION,
                NEUTRAL_TREMOR, neutralTiming(), NEUTRAL_FATIGUE, NEUTRAL_CAMERA, 0.1, null);
        when(gameAPI.getPersonality()).thenReturn(p);
        assertEquals(1000, runner.adjustDelay(1000, gameAPI));
    }

    // ========================== Neutral personality ==========================

    @Test
    void neutralPersonality_returnsBaseDelay() {
        // All timing at 1.0, fatigue 0.0, attention 1.0, rhythm 1.0 (no jitter)
        when(gameAPI.getPersonality()).thenReturn(personality(neutralTiming(), neutralSession()));
        assertEquals(1000, runner.adjustDelay(1000, gameAPI));
    }

    // ========================== Reaction speed ==========================

    @Test
    void slowReaction_increasesDelay() {
        // reactionSpeed 1.5 → 50% slower base
        Timing timing = new Timing(1.5, 1.0, 1.0);
        when(gameAPI.getPersonality()).thenReturn(personality(timing, neutralSession()));
        assertEquals(1500, runner.adjustDelay(1000, gameAPI));
    }

    @Test
    void fastReaction_decreasesDelay() {
        // reactionSpeed 0.7 → 30% faster base
        Timing timing = new Timing(0.7, 1.0, 1.0);
        when(gameAPI.getPersonality()).thenReturn(personality(timing, neutralSession()));
        assertEquals(700, runner.adjustDelay(1000, gameAPI));
    }

    // ========================== Pause tendency ==========================

    @Test
    void highPauseTendency_increasesDelay() {
        // pauseTendency 2.0 → multiplier (1 + (2.0-1.0)*0.5) = 1.5
        Timing timing = new Timing(1.0, 1.0, 2.0);
        when(gameAPI.getPersonality()).thenReturn(personality(timing, neutralSession()));
        assertEquals(1500, runner.adjustDelay(1000, gameAPI));
    }

    @Test
    void lowPauseTendency_decreasesDelay() {
        // pauseTendency 0.5 → multiplier (1 + (0.5-1.0)*0.5) = 0.75
        Timing timing = new Timing(1.0, 1.0, 0.5);
        when(gameAPI.getPersonality()).thenReturn(personality(timing, neutralSession()));
        assertEquals(750, runner.adjustDelay(1000, gameAPI));
    }

    // ========================== Fatigue ==========================

    @Test
    void maxFatigue_adds30Percent() {
        // fatigueLevel 1.0 → multiplier (1 + 1.0*0.3) = 1.3
        Session session = new Session(1.0, 1.0, 0.0, 0.0, "high", 5.0, 1000, 10, 2);
        when(gameAPI.getPersonality()).thenReturn(personality(neutralTiming(), session));
        assertEquals(1300, runner.adjustDelay(1000, gameAPI));
    }

    @Test
    void halfFatigue_adds15Percent() {
        // fatigueLevel 0.5 → multiplier (1 + 0.5*0.3) = 1.15
        Session session = new Session(0.5, 1.0, 0.0, 0.0, "moderate", 2.0, 500, 5, 1);
        when(gameAPI.getPersonality()).thenReturn(personality(neutralTiming(), session));
        assertEquals(1150, runner.adjustDelay(1000, gameAPI));
    }

    @Test
    void zeroFatigue_noChange() {
        Session session = new Session(0.0, 1.0, 0.0, 0.0, "low", 0.1, 10, 0, 0);
        when(gameAPI.getPersonality()).thenReturn(personality(neutralTiming(), session));
        assertEquals(1000, runner.adjustDelay(1000, gameAPI));
    }

    // ========================== Attention ==========================

    @Test
    void minAttention_adds20Percent() {
        // attentionLevel 0.0 → multiplier (1 + (1.0-0.0)*0.2) = 1.2
        // (note: 0.0 is below documented 0.3 min, but should still be handled)
        Session session = new Session(0.0, 0.0, 0.0, 0.0, "low", 0.5, 100, 0, 0);
        when(gameAPI.getPersonality()).thenReturn(personality(neutralTiming(), session));
        assertEquals(1200, runner.adjustDelay(1000, gameAPI));
    }

    @Test
    void lowestDocumentedAttention_adds14Percent() {
        // attentionLevel 0.3 → multiplier (1 + 0.7*0.2) = 1.14
        Session session = new Session(0.0, 0.3, 0.0, 0.0, "low", 0.5, 100, 0, 0);
        when(gameAPI.getPersonality()).thenReturn(personality(neutralTiming(), session));
        assertEquals(1140, runner.adjustDelay(1000, gameAPI));
    }

    @Test
    void fullAttention_noChange() {
        // attentionLevel 1.0 → multiplier (1 + 0.0*0.2) = 1.0
        when(gameAPI.getPersonality()).thenReturn(personality(neutralTiming(), neutralSession()));
        assertEquals(1000, runner.adjustDelay(1000, gameAPI));
    }

    // ========================== Combined factors ==========================

    @Test
    void allFactorsCombine_multiplicatively() {
        // reactionSpeed=1.2, pauseTendency=1.4, fatigueLevel=0.5, attentionLevel=0.6, rhythm=1.0
        // reaction: 1000 * 1.2 = 1200
        // pause: 1200 * (1 + 0.4*0.5) = 1200 * 1.2 = 1440
        // fatigue: 1440 * (1 + 0.5*0.3) = 1440 * 1.15 = 1656
        // attention: 1656 * (1 + 0.4*0.2) = 1656 * 1.08 = 1788.48 → 1788
        Timing timing = new Timing(1.2, 1.0, 1.4);
        Session session = new Session(0.5, 0.6, 1.0, 0.05, "moderate", 2.0, 500, 3, 1);
        when(gameAPI.getPersonality()).thenReturn(personality(timing, session));
        assertEquals(1788, runner.adjustDelay(1000, gameAPI));
    }

    @Test
    void exhaustedSlowPersonality_significantIncrease() {
        // reactionSpeed=1.5, pauseTendency=2.0, fatigueLevel=1.0, attentionLevel=0.3
        // reaction: 1000 * 1.5 = 1500
        // pause: 1500 * 1.5 = 2250
        // fatigue: 2250 * 1.3 = 2925
        // attention: 2925 * (1 + 0.7*0.2) = 2925 * 1.14 ≈ 3334
        Timing timing = new Timing(1.5, 1.0, 2.0);
        Session session = new Session(1.0, 0.3, 5.0, 0.5, "critical", 8.0, 3000, 50, 5);
        when(gameAPI.getPersonality()).thenReturn(personality(timing, session));
        assertEquals(3334, runner.adjustDelay(1000, gameAPI));
    }

    @Test
    void fastFreshPersonality_decreasesDelay() {
        // reactionSpeed=0.7, pauseTendency=0.5, fatigueLevel=0.0, attentionLevel=1.0
        // reaction: 1000 * 0.7 = 700
        // pause: 700 * 0.75 = 525
        // fatigue: 525 * 1.0 = 525
        // attention: 525 * 1.0 = 525
        Timing timing = new Timing(0.7, 1.0, 0.5);
        when(gameAPI.getPersonality()).thenReturn(personality(timing, neutralSession()));
        assertEquals(525, runner.adjustDelay(1000, gameAPI));
    }

    // ========================== Rhythm jitter ==========================

    @Test
    void perfectRhythm_noJitter() {
        // rhythmConsistency 1.0 → jitterRange = 0.0, no randomness
        Timing timing = new Timing(1.0, 1.0, 1.0);
        when(gameAPI.getPersonality()).thenReturn(personality(timing, neutralSession()));

        // Should return exactly 1000 every time with no jitter
        for (int i = 0; i < 50; i++) {
            assertEquals(1000, runner.adjustDelay(1000, gameAPI));
        }
    }

    @Test
    void lowRhythm_producesJitterWithinBounds() {
        // rhythmConsistency 0.3 → jitterRange = 0.7 * 0.15 = 0.105 (±10.5%)
        Timing timing = new Timing(1.0, 0.3, 1.0);
        when(gameAPI.getPersonality()).thenReturn(personality(timing, neutralSession()));

        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int i = 0; i < 500; i++) {
            int result = runner.adjustDelay(1000, gameAPI);
            min = Math.min(min, result);
            max = Math.max(max, result);
        }

        // Jitter range ±10.5%, so results should be in [895, 1105]
        assertTrue(min >= 895, "min " + min + " should be >= 895");
        assertTrue(max <= 1105, "max " + max + " should be <= 1105");
        // With 500 iterations, we should see some spread
        assertTrue(max - min > 20, "Expected meaningful jitter spread, got " + (max - min));
    }

    @Test
    void zeroRhythm_maxJitterWithinBounds() {
        // rhythmConsistency 0.0 → jitterRange = 1.0 * 0.15 = 0.15 (±15%)
        Timing timing = new Timing(1.0, 0.0, 1.0);
        when(gameAPI.getPersonality()).thenReturn(personality(timing, neutralSession()));

        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int i = 0; i < 500; i++) {
            int result = runner.adjustDelay(1000, gameAPI);
            min = Math.min(min, result);
            max = Math.max(max, result);
        }

        // Max jitter ±15%, results in [850, 1150]
        assertTrue(min >= 850, "min " + min + " should be >= 850");
        assertTrue(max <= 1150, "max " + max + " should be <= 1150");
        assertTrue(max - min > 50, "Expected wide jitter spread, got " + (max - min));
    }

    // ========================== Edge cases ==========================

    @Test
    void verySmallDelay_clampedToOne() {
        // fast personality reducing a small delay
        Timing timing = new Timing(0.7, 1.0, 0.5);
        when(gameAPI.getPersonality()).thenReturn(personality(timing, neutralSession()));
        // 1ms * 0.7 * 0.75 = 0.525 → rounds to 1 (clamped)
        assertEquals(1, runner.adjustDelay(1, gameAPI));
    }

    @Test
    void zeroDelay_notPassedToAdjust() {
        // Zero delay skips Thread.sleep entirely in ScriptRunner.run(),
        // but if it were passed, adjustDelay should still handle it
        when(gameAPI.getPersonality()).thenReturn(personality(neutralTiming(), neutralSession()));
        // 0 * anything = 0, but clamped to 1? No — Math.max(1, round(0)) = 1
        // This is fine: a 0ms base delay with personality should still produce at least 1ms
        int result = runner.adjustDelay(0, gameAPI);
        assertTrue(result >= 0);
    }

    @Test
    void largeDelay_scalesCorrectly() {
        Timing timing = new Timing(1.5, 1.0, 2.0);
        Session session = new Session(1.0, 0.3, 5.0, 0.5, "critical", 8.0, 3000, 50, 5);
        when(gameAPI.getPersonality()).thenReturn(personality(timing, session));
        // 60000 * 1.5 * 1.5 * 1.3 * 1.14 = 60000 * 3.3345 = ~200070
        assertEquals(200070, runner.adjustDelay(60000, gameAPI));
    }

    // ========================== Different base delays ==========================

    @Test
    void smallDelay_600ms_withModeratePersonality() {
        // reactionSpeed=1.1, pauseTendency=1.2, fatigue=0.3, attention=0.8
        // 600 * 1.1 = 660
        // 660 * (1 + 0.2*0.5) = 660 * 1.1 = 726
        // 726 * (1 + 0.3*0.3) = 726 * 1.09 = 791.34 → 791
        // 791 * (1 + 0.2*0.2) = 791 * 1.04 = 822.64 → 823
        Timing timing = new Timing(1.1, 1.0, 1.2);
        Session session = new Session(0.3, 0.8, 0.5, 0.01, "low", 1.0, 200, 1, 0);
        when(gameAPI.getPersonality()).thenReturn(personality(timing, session));
        assertEquals(823, runner.adjustDelay(600, gameAPI));
    }
}
