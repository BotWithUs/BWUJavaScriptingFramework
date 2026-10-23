package com.botwithus.bot.cli.gui.pages.groups;

/**
 * What starting a script on a group does on a member already running a
 * different one. A client runs any number of scripts at once, so starting one
 * never has to stop another; switching is a choice, not a consequence.
 */
public enum BusyChoice {

    /** Start it alongside what is running; nothing is stopped. */
    ALSO_START,

    /** Stop what is running, then start it. */
    SWITCH
}
