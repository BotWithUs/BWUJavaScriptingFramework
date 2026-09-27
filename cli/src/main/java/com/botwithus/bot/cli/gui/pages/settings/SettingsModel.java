package com.botwithus.bot.cli.gui.pages.settings;

/**
 * What the Settings page reads and asks for. The live model works on the host's
 * {@link com.botwithus.bot.cli.settings.HostSettings}, profiles and connections;
 * the dev preview supplies a fixture. Every method runs on the render thread and
 * returns promptly: anything slow (opening Explorer, writing the export) happens
 * elsewhere and reports back through {@link SettingsView#note()} in a later frame.
 */
public interface SettingsModel {

    /** This frame's page. */
    SettingsView view();

    /** Just the header's save line: cheap enough for the sidebar to read every frame. */
    SaveLine save();

    /** Applies an edit from a setting's row, in the row's display form; saves on its own. */
    EditResult edit(String name, String shownText);

    /** Applies an edit from the All config keys table, in the form the file stores. */
    EditResult editRaw(String name, String text);

    /** Turns resuming saved scripts for the account on or off. */
    void setAutoStart(String accountUuid, boolean isOn);

    /** Clears the scripts saved for the account; the account itself stays listed. */
    void forgetScripts(String accountUuid);

    /** Opens a folder in Explorer, or the settings file in its editor. */
    void open(SettingsPlace place);

    /** Runs a one-shot button. */
    void run(SettingsAction action);
}
