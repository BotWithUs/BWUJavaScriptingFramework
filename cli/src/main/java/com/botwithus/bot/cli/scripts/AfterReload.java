package com.botwithus.bot.cli.scripts;

import com.botwithus.bot.cli.settings.SettingKeys;

/** What a reload starts once the fresh scripts are registered. */
public enum AfterReload {

    /** Start nothing: the reloaded scripts wait to be started. */
    REGISTER_ONLY,

    /**
     * Start again exactly the scripts each client was running before the
     * reload, and nothing else. The {@link SettingKeys#RESTART_AFTER_RELOAD}
     * behaviour.
     */
    RESTART_RUNNING,

    /** Start every reloaded script on every reloaded client: {@code reload --start}. */
    START_ALL;

    /** The mode a plain reload uses, given {@link SettingKeys#RESTART_AFTER_RELOAD}. */
    public static AfterReload fromSetting(boolean isRestartAfterReload) {
        return isRestartAfterReload ? RESTART_RUNNING : REGISTER_ONLY;
    }
}
