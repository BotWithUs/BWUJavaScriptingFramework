package com.botwithus.bot.api.util;

import java.util.Arrays;

/**
 * RuneScape 3's experience curves: how much xp each level needs. Two curves exist: the
 * standard one, and the steeper elite one that {@link SkillType#isElite() elite} skills use.
 *
 * <p>Levels are 1-based and include virtual levels as far as the curve is known and
 * {@link #MAX_XP} reaches: the standard curve runs to level {@value #STANDARD_TABLE_TOP} and
 * the elite curve to {@value #ELITE_TABLE_TOP}. A skill's real level cap (99 or 120 for most
 * skills) is a client value that is not on the wire, so nothing here caps at it; pass the cap
 * you mean to {@link #levelForXp(int, int)}.</p>
 */
public enum XpTable {

    /** The curve every non-elite skill uses. */
    STANDARD(new int[] {
            0, 83, 174, 276, 388, 512, 650, 801, 969, 1_154, 1_358, 1_584, 1_833, 2_107, 2_411,
            2_746, 3_115, 3_523, 3_973, 4_470, 5_018, 5_624, 6_291, 7_028, 7_842, 8_740, 9_730,
            10_824, 12_031, 13_363, 14_833, 16_456, 18_247, 20_224, 22_406, 24_815, 27_473,
            30_408, 33_648, 37_224, 41_171, 45_529, 50_339, 55_649, 61_512, 67_983, 75_127,
            83_014, 91_721, 101_333, 111_945, 123_660, 136_594, 150_872, 166_636, 184_040,
            203_254, 224_466, 247_886, 273_742, 302_288, 333_804, 368_599, 407_015, 449_428,
            496_254, 547_953, 605_032, 668_051, 737_627, 814_445, 899_257, 992_895, 1_096_278,
            1_210_421, 1_336_443, 1_475_581, 1_629_200, 1_798_808, 1_986_068, 2_192_818,
            2_421_087, 2_673_114, 2_951_373, 3_258_594, 3_597_792, 3_972_294, 4_385_776,
            4_842_295, 5_346_332, 5_902_831, 6_517_253, 7_195_629, 7_944_614, 8_771_558,
            9_684_577, 10_692_629, 11_805_606, 13_034_431, 14_391_160, 15_889_109, 17_542_976,
            19_368_992, 21_385_073, 23_611_006, 26_068_632, 28_782_068, 31_777_942, 35_085_654,
            38_737_661, 42_769_801, 47_221_641, 52_136_869, 57_563_718, 63_555_443, 70_170_840,
            77_474_828, 85_539_082, 94_442_736, 104_273_167, 115_126_838, 127_110_260,
            140_341_028, 154_948_977, 171_077_457, 188_884_740
    }),

    /** The steeper curve for elite skills (Invention). */
    ELITE(new int[] {
            0, 830, 1_861, 2_902, 3_980, 5_126, 6_390, 7_787, 9_400, 11_275, 13_605, 16_372,
            19_656, 23_546, 28_138, 33_520, 39_809, 47_109, 55_535, 64_802, 77_190, 90_811,
            106_221, 123_573, 143_025, 164_742, 188_893, 215_651, 245_196, 277_713, 316_311,
            358_547, 404_634, 454_796, 509_259, 568_254, 632_019, 700_797, 774_834, 854_383,
            946_227, 1_044_569, 1_149_696, 1_261_903, 1_381_488, 1_508_756, 1_644_015, 1_787_581,
            1_939_773, 2_100_917, 2_283_490, 2_476_369, 2_679_907, 2_894_505, 3_120_508,
            3_358_307, 3_608_290, 3_870_846, 4_146_374, 4_435_275, 4_758_122, 5_096_111,
            5_449_685, 5_819_299, 6_205_407, 6_608_473, 7_028_964, 7_467_354, 7_924_122,
            8_399_751, 8_925_664, 9_472_665, 10_041_285, 10_632_061, 11_245_538, 11_882_262,
            12_542_789, 13_227_679, 13_937_496, 14_672_812, 15_478_994, 16_313_404, 17_176_661,
            18_069_395, 18_992_239, 19_945_833, 20_930_821, 21_947_856, 22_997_593, 24_080_695,
            25_259_906, 26_475_754, 27_728_955, 29_020_233, 30_350_318, 31_719_944, 33_129_852,
            34_580_790, 36_073_511, 37_608_773, 39_270_442, 40_978_509, 42_733_789, 44_537_107,
            46_389_292, 48_291_180, 50_243_611, 52_247_435, 54_303_504, 56_412_678, 58_575_823,
            60_793_812, 63_067_521, 65_397_835, 67_785_643, 70_231_841, 72_737_330, 75_303_019,
            77_929_820, 80_618_654
    });

    /** The game's xp cap for any one skill. */
    public static final int MAX_XP = 200_000_000;

    /** Highest level {@link #STANDARD} defines: the last one {@link #MAX_XP} reaches. */
    public static final int STANDARD_TABLE_TOP = 126;

    /** Highest level {@link #ELITE} defines. */
    public static final int ELITE_TABLE_TOP = 120;

    /** Lowest level any skill can have. */
    public static final int MIN_LEVEL = 1;

    /** Total xp needed for each level; index 0 is level 1. */
    private final int[] xpAtLevel;

    XpTable(int[] xpAtLevel) {
        this.xpAtLevel = Arrays.copyOf(xpAtLevel, xpAtLevel.length);
    }

    /** The highest level this curve defines. */
    public int maxLevel() {
        return xpAtLevel.length;
    }

    /**
     * Total xp needed to reach {@code level}.
     *
     * @throws IllegalArgumentException when {@code level} is outside {@code 1..maxLevel()}
     */
    public int xpForLevel(int level) {
        if (level < MIN_LEVEL || level > maxLevel()) {
            throw new IllegalArgumentException(
                    "level " + level + " is outside 1.." + maxLevel() + " for " + this);
        }
        return xpAtLevel[level - 1];
    }

    /**
     * The (virtual) level {@code xp} reaches on this curve, up to {@link #maxLevel()}.
     * Negative xp reads as level 1.
     */
    public int levelForXp(int xp) {
        int index = Arrays.binarySearch(xpAtLevel, xp);
        int level = index >= 0 ? index + 1 : -index - 1;
        return Math.max(MIN_LEVEL, level);
    }

    /** {@link #levelForXp(int)}, capped at {@code levelCap} (99 for a skill capped at 99). */
    public int levelForXp(int xp, int levelCap) {
        return Math.min(levelForXp(xp), levelCap);
    }

    /**
     * Xp still needed to reach the level after the one {@code xp} gives, or {@code 0} when
     * {@code xp} is already at this curve's top level.
     */
    public int xpToNextLevel(int xp) {
        int level = levelForXp(xp);
        if (level >= maxLevel()) {
            return 0;
        }
        return xpForLevel(level + 1) - Math.max(0, xp);
    }
}
