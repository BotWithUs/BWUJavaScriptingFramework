package com.botwithus.bot.core.loader;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/**
 * Panama {@link StructLayout} definitions and string I/O helpers for bwu.dll structs.
 */
final class BwuLayouts {

    private BwuLayouts() {}

    // ── Buffer-size constants from the DLL header ──────────────────────────

    static final int UUID_LEN         = 64;
    static final int NAME_LEN         = 256;
    static final int PASSWORD_LEN     = 512;
    static final int PIN_LEN          = 64;
    static final int TOKEN_LEN        = 2048;
    static final int PATH_LEN         = 512;
    static final int ERROR_MSG_LEN    = 512;
    static final int SESSION_LEN      = 512;

    static final int MAX_ACCOUNTS       = 128;
    static final int MAX_JAGEX_ACCOUNTS = 32;
    static final int MAX_CHARACTERS     = 16;

    // ── Struct layouts ─────────────────────────────────────────────────────

    static final StructLayout BWU_USER = MemoryLayout.structLayout(
            MemoryLayout.sequenceLayout(UUID_LEN, ValueLayout.JAVA_BYTE).withName("id"),
            MemoryLayout.sequenceLayout(NAME_LEN, ValueLayout.JAVA_BYTE).withName("name"),
            ValueLayout.JAVA_LONG.withName("session_limit"),
            ValueLayout.JAVA_LONG.withName("expiration_ts")
    );

    static final StructLayout BWU_ACCOUNT = MemoryLayout.structLayout(
            MemoryLayout.sequenceLayout(UUID_LEN, ValueLayout.JAVA_BYTE).withName("uuid"),
            MemoryLayout.sequenceLayout(NAME_LEN, ValueLayout.JAVA_BYTE).withName("name"),
            MemoryLayout.sequenceLayout(PASSWORD_LEN, ValueLayout.JAVA_BYTE).withName("password"),
            MemoryLayout.sequenceLayout(PIN_LEN, ValueLayout.JAVA_BYTE).withName("pin"),
            ValueLayout.JAVA_INT.withName("world_a"),
            ValueLayout.JAVA_INT.withName("world_b"),
            ValueLayout.JAVA_INT.withName("target_type"),
            ValueLayout.JAVA_INT.withName("account_type"),
            ValueLayout.JAVA_INT.withName("auto_login"),
            ValueLayout.JAVA_INT.withName("auto_restart")
    );

    static final StructLayout BWU_PROVIDER_ACCOUNT = MemoryLayout.structLayout(
            MemoryLayout.sequenceLayout(NAME_LEN, ValueLayout.JAVA_BYTE).withName("name"),
            ValueLayout.JAVA_INT.withName("selected")
    );

    static final StructLayout BWU_STATUS = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("login_stage"),
            ValueLayout.JAVA_INT.withName("max_login_stage"),
            ValueLayout.JAVA_INT.withName("is_logged_in"),
            ValueLayout.JAVA_INT.withName("is_downloading"),
            ValueLayout.JAVA_INT.withName("download_progress"),
            ValueLayout.JAVA_INT.withName("module_ready"),
            ValueLayout.JAVA_INT.withName("active_launches"),
            MemoryLayout.sequenceLayout(ERROR_MSG_LEN, ValueLayout.JAVA_BYTE).withName("last_error")
    );

    static final StructLayout BWU_JAGEX_CHARACTER = MemoryLayout.structLayout(
            MemoryLayout.sequenceLayout(UUID_LEN, ValueLayout.JAVA_BYTE).withName("account_id"),
            MemoryLayout.sequenceLayout(NAME_LEN, ValueLayout.JAVA_BYTE).withName("display_name"),
            MemoryLayout.sequenceLayout(NAME_LEN, ValueLayout.JAVA_BYTE).withName("user_hash")
    );

    static final StructLayout BWU_JAGEX_ACCOUNT = MemoryLayout.structLayout(
            MemoryLayout.sequenceLayout(UUID_LEN, ValueLayout.JAVA_BYTE).withName("uuid"),
            MemoryLayout.sequenceLayout(NAME_LEN, ValueLayout.JAVA_BYTE).withName("subject"),
            MemoryLayout.sequenceLayout(NAME_LEN, ValueLayout.JAVA_BYTE).withName("display_label"),
            MemoryLayout.sequenceLayout(MAX_CHARACTERS, BWU_JAGEX_CHARACTER).withName("characters"),
            ValueLayout.JAVA_INT.withName("character_count"),
            ValueLayout.JAVA_INT.withName("selected_character"),
            MemoryLayout.sequenceLayout(SESSION_LEN, ValueLayout.JAVA_BYTE).withName("session_id"),
            ValueLayout.JAVA_LONG.withName("session_expires_at")
    );

    // ── String I/O helpers ─────────────────────────────────────────────────

    /**
     * Read a null-terminated C string starting at {@code offset} within {@code seg}.
     */
    static String readString(MemorySegment seg, long offset) {
        return seg.getString(offset, StandardCharsets.UTF_8);
    }

    /**
     * Write a Java {@code String} into a fixed-size {@code char[]} field.
     * The region is zeroed first to guarantee null-termination.
     */
    static void writeString(MemorySegment seg, long offset, int maxLen, String value) {
        MemorySegment slice = seg.asSlice(offset, maxLen);
        slice.fill((byte) 0);
        if (value != null && !value.isEmpty()) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            int len = Math.min(bytes.length, maxLen - 1);
            MemorySegment.copy(bytes, 0, slice, ValueLayout.JAVA_BYTE, 0, len);
        }
    }
}
