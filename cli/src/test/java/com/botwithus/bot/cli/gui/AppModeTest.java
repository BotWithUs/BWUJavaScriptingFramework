package com.botwithus.bot.cli.gui;

import com.botwithus.bot.cli.settings.StartMode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppModeTest {

    @Test
    void openingIn_normal_opensNormal() {
        assertEquals(AppMode.NORMAL, AppMode.openingIn(StartMode.NORMAL));
    }

    @Test
    void openingIn_advanced_opensAdvanced() {
        assertEquals(AppMode.ADVANCED, AppMode.openingIn(StartMode.ADVANCED));
    }
}
