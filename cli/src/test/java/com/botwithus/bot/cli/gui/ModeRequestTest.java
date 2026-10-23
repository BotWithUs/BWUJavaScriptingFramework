package com.botwithus.bot.cli.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModeRequestTest {

    @Test
    void requestDuringTheFrame_winsOverTheModeTheFrameReturned() {
        ModeRequest request = new ModeRequest();

        // "View log" pressed in Normal mode: the frame itself still returns NORMAL.
        request.request(AppMode.ADVANCED);

        assertEquals(AppMode.ADVANCED, request.resolve(AppMode.NORMAL));
    }

    @Test
    void aRequestIsConsumedOnce() {
        ModeRequest request = new ModeRequest();
        request.request(AppMode.ADVANCED);
        request.resolve(AppMode.NORMAL);

        // Next frame the user pressed F12 back to Normal; nothing may drag them back.
        assertEquals(AppMode.NORMAL, request.resolve(AppMode.NORMAL));
    }

    @Test
    void noRequest_keepsTheFramesMode() {
        assertEquals(AppMode.ADVANCED, new ModeRequest().resolve(AppMode.ADVANCED));
    }
}
