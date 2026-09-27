package com.botwithus.bot.cli.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The packed-colour helpers on {@link Controls}; imgui packs colours as 0xAABBGGRR. */
class ControlsColorTest {

    private static final float TENTH = 0.1f;

    @Test
    void lighten_addsToEveryColourChannel_andKeepsAlpha() {
        assertEquals(0x80_3A_2F_24, Controls.lighten(0x80_20_15_0A, TENTH));
    }

    @Test
    void lighten_capsAChannelAtFull() {
        assertEquals(0xFF_FF_FF_1A, Controls.lighten(0xFF_F0_FF_00, TENTH));
    }

    @Test
    void lighten_byNothing_isTheSameColour() {
        assertEquals(ImGuiTheme.COL_DANGER, Controls.lighten(ImGuiTheme.COL_DANGER, 0f));
    }
}
