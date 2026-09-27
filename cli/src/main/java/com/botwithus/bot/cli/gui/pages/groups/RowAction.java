package com.botwithus.bot.cli.gui.pages.groups;

/** The one script action a member's row offers, besides removing it from the group. */
public enum RowAction {

    /** Stop the scripts running on the client. */
    STOP,

    /** Cancel the starts waiting for the client to be back. */
    CANCEL_QUEUED,

    /** Run the stopped script again. */
    RUN,

    /** Start the crashed script again. */
    RESTART,

    /** Nothing to do from here. */
    NONE
}
