package com.botwithus.bot.core.loader;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Library status snapshot.
 *
 * @param loginStage       current login progress stage
 * @param maxLoginStage    total login stages
 * @param loggedIn         whether the user is logged in
 * @param downloading      whether a module download is in progress
 * @param downloadProgress module download progress (0-100)
 * @param moduleReady      whether the module is downloaded and verified
 * @param activeLaunches   number of launch+inject operations in progress
 * @param lastError        last error message
 */
public record BwuStatus(
        int loginStage,
        int maxLoginStage,
        boolean loggedIn,
        boolean downloading,
        int downloadProgress,
        boolean moduleReady,
        int activeLaunches,
        String lastError
) {
    // login_stage:       u32       offset 0
    // max_login_stage:   u32       offset 4
    // is_logged_in:      i32       offset 8
    // is_downloading:    i32       offset 12
    // download_progress: u32       offset 16
    // module_ready:      i32       offset 20
    // active_launches:   i32       offset 24
    // last_error:        char[512] offset 28

    static BwuStatus read(MemorySegment seg) {
        return new BwuStatus(
                seg.get(ValueLayout.JAVA_INT, 0),
                seg.get(ValueLayout.JAVA_INT, 4),
                seg.get(ValueLayout.JAVA_INT, 8) != 0,
                seg.get(ValueLayout.JAVA_INT, 12) != 0,
                seg.get(ValueLayout.JAVA_INT, 16),
                seg.get(ValueLayout.JAVA_INT, 20) != 0,
                seg.get(ValueLayout.JAVA_INT, 24),
                BwuLayouts.readString(seg, 28)
        );
    }
}
