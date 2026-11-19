package com.botwithus.bot.api.gameui;

/** Something a new component can be created under: a Jagex component or one of the script's own. */
public sealed interface UiParent permits JagexComponent, UiComponent {

    /** The agent's handle. */
    long handle();

    /** What the agent reported about the handle when it was made. */
    UiHandleInfo info();

    /** Reads the component back through the client's own getters. */
    UiReadback readback();
}
