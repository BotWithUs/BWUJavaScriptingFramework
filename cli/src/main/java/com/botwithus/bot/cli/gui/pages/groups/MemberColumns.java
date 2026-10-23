package com.botwithus.bot.cli.gui.pages.groups;

/**
 * Where each column of the members table starts, for a table of a given width.
 * Account and Script share what the fixed columns leave, 1.1 to 1.5; on a narrow
 * table World and Loop give way first, and their starts then equal the next
 * column's.
 *
 * @param gap    the space between columns
 * @param isWide whether World and Loop are shown
 */
record MemberColumns(float check, float account, float world, float link, float script, float loop,
                     float actions, float end, float gap, boolean isWide) {

    private static final float CHECK_EM = 1.067f;
    private static final float WORLD_EM = 3.733f;
    private static final float LINK_EM = 8.533f;
    private static final float LOOP_EM = 4.8f;
    private static final float ACTIONS_EM = 5.733f;
    private static final float ACCOUNT_SHARE = 1.1f;
    private static final float SCRIPT_SHARE = 1.5f;
    /** Below this many body-font widths, World and Loop are dropped. */
    private static final float WIDE_EM = 45f;
    private static final int GAPS_WIDE = 6;
    private static final int GAPS_NARROW = 4;

    /**
     * @param padLeft  space before the checkbox
     * @param padRight space after the actions
     */
    static MemberColumns of(float x, float width, float fontSize, float padLeft, float padRight, float gap) {
        boolean isWide = width >= fontSize * WIDE_EM;
        float optional = isWide ? fontSize * (WORLD_EM + LOOP_EM) : 0f;
        float fixed = fontSize * (CHECK_EM + LINK_EM + ACTIONS_EM) + optional;
        int gaps = isWide ? GAPS_WIDE : GAPS_NARROW;
        float flexible = Math.max(0f, width - padLeft - padRight - fixed - gap * gaps);
        float accountW = flexible * ACCOUNT_SHARE / (ACCOUNT_SHARE + SCRIPT_SHARE);
        float scriptW = flexible - accountW;
        float check = x + padLeft;
        float account = check + fontSize * CHECK_EM + gap;
        float world = account + accountW + gap;
        float link = isWide ? world + fontSize * WORLD_EM + gap : world;
        float script = link + fontSize * LINK_EM + gap;
        float loop = script + scriptW + gap;
        float actions = isWide ? loop + fontSize * LOOP_EM + gap : loop;
        return new MemberColumns(check, account, world, link, script, loop, actions,
                actions + fontSize * ACTIONS_EM, gap, isWide);
    }

    float accountWidth() {
        return world - account - gap;
    }

    float scriptWidth() {
        return loop - script - gap;
    }

    float loopWidth() {
        return actions - loop - gap;
    }

    float actionsWidth() {
        return end - actions;
    }
}
