package com.botwithus.bot.api.model;

/**
 * Humanizer personality profile and live session statistics.
 * <p>
 * Personality values are stable per-user traits that define movement characteristics
 * (speed, precision, timing, etc.). Session stats reflect the current fatigue/risk state.
 * All values are read-only; scripts can use these to adapt timing delays, click precision,
 * and break scheduling.
 *
 * @see com.botwithus.bot.api.GameAPI#getPersonality
 */
public record Personality(
        long personalityId,
        Speed speed,
        Path path,
        Precision precision,
        Tremor tremor,
        Timing timing,
        Fatigue fatigue,
        Camera camera,
        double dailyVariance,
        Session session
) {

    /**
     * Speed characteristics for mouse movement.
     *
     * @param bias        base speed tendency (0.7–1.3, {@literal >}1 = faster)
     * @param consistency how consistent speed is (0.5–1.0, 1 = very consistent)
     */
    public record Speed(double bias, double consistency) {}

    /**
     * Mouse path curvature characteristics.
     *
     * @param curvatureBias tendency to curve paths (-0.3–0.3, + = rightward)
     * @param handedness    dominant hand: "right", "left", or "ambidextrous"
     * @param variability   how much paths vary between movements (0.5–1.5)
     */
    public record Path(double curvatureBias, String handedness, double variability) {}

    /**
     * Click precision characteristics.
     *
     * @param overshootTendency personal overshoot rate multiplier (0.5–2.0)
     * @param correctionSpeed   how fast corrections happen (0.7–1.3)
     * @param targetPrecision   how precisely targets are hit (0.7–1.3)
     */
    public record Precision(double overshootTendency, double correctionSpeed, double targetPrecision) {}

    /**
     * Mouse tremor characteristics.
     *
     * @param frequencyBias personal tremor frequency offset in Hz (-2.0–2.0)
     * @param amplitudeBias personal tremor amplitude multiplier (0.5–2.0)
     */
    public record Tremor(double frequencyBias, double amplitudeBias) {}

    /**
     * Action timing characteristics.
     *
     * @param reactionSpeed      reaction time multiplier (0.7–1.5, {@literal >}1 = slower)
     * @param rhythmConsistency  how rhythmic actions are (0.3–1.0)
     * @param pauseTendency      tendency to pause between actions (0.5–2.0)
     */
    public record Timing(double reactionSpeed, double rhythmConsistency, double pauseTendency) {}

    /**
     * Fatigue resistance and recovery traits.
     *
     * @param resistance how resistant to fatigue (0.5–1.5)
     * @param recovery   how fast fatigue recovers (0.7–1.3)
     */
    public record Fatigue(double resistance, double recovery) {}

    /**
     * Camera movement characteristics.
     *
     * @param sensitivity       camera rotation speed multiplier (0.7–1.3)
     * @param smoothness        camera smoothness (0.5–1.5, low = jerky)
     * @param overshootTendency camera overshoot rate (0.5–2.0)
     * @param idleDriftAmount   idle camera drift intensity (0.5–2.0)
     * @param settlingSpeed     camera settling oscillation speed (0.7–1.3)
     */
    public record Camera(double sensitivity, double smoothness, double overshootTendency,
                          double idleDriftAmount, double settlingSpeed) {}

    /**
     * Live session statistics reflecting current fatigue/risk state.
     *
     * @param fatigueLevel         current fatigue (0.0–1.0, 0 = fresh, 1 = exhausted)
     * @param attentionLevel       current attention level (0.3–1.0)
     * @param cumulativeRisk       accumulated detection risk (0.0+)
     * @param banProbability       estimated ban probability (0.0–1.0)
     * @param riskLevel            current risk classification: "low", "moderate", "high", "critical"
     * @param sessionDurationHours hours since session start
     * @param totalActions         total actions this session
     * @param totalErrors          errors this session
     * @param breaksTaken          breaks taken this session
     */
    public record Session(double fatigueLevel, double attentionLevel, double cumulativeRisk,
                           double banProbability, String riskLevel, double sessionDurationHours,
                           int totalActions, int totalErrors, int breaksTaken) {}
}
