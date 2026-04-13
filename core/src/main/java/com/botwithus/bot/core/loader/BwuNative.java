package com.botwithus.bot.core.loader;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.*;

/**
 * Raw Panama downcall handles for every exported function in bwu.dll.
 * <p>
 * Package-private — callers use {@link BwuClient} instead.
 */
final class BwuNative {

    // ── Lifecycle ──────────────────────────────────────────────────────────

    final MethodHandle bwu_init;                 // () -> int
    final MethodHandle bwu_shutdown;             // () -> void
    final MethodHandle bwu_get_status;           // (ptr) -> int
    final MethodHandle bwu_get_last_error;       // () -> ptr
    final MethodHandle bwu_get_version;          // () -> ptr

    // ── Authentication ─────────────────────────────────────────────────────

    final MethodHandle bwu_login;                // () -> int
    final MethodHandle bwu_login_with_token;     // (ptr) -> int
    final MethodHandle bwu_is_logged_in;         // () -> int
    final MethodHandle bwu_get_token;            // () -> ptr
    final MethodHandle bwu_get_user;             // (ptr) -> int
    final MethodHandle bwu_logout;               // () -> void
    final MethodHandle bwu_save_token;           // (ptr) -> int
    final MethodHandle bwu_load_token;           // (ptr) -> int

    // ── Module Management ──────────────────────────────────────────────────

    final MethodHandle bwu_download_module;      // () -> int
    final MethodHandle bwu_has_module;           // () -> int
    final MethodHandle bwu_get_module_bytes;     // (ptr, ptr) -> int

    // ── Account Management (Classic) ───────────────────────────────────────

    final MethodHandle bwu_add_account;          // (ptr) -> int
    final MethodHandle bwu_remove_account;       // (ptr) -> int
    final MethodHandle bwu_get_account_count;    // () -> int
    final MethodHandle bwu_get_account;          // (int, ptr) -> int
    final MethodHandle bwu_find_account;         // (ptr, ptr) -> int
    final MethodHandle bwu_update_account;       // (ptr) -> int
    final MethodHandle bwu_clear_accounts;       // () -> void

    // ── Process Management ─────────────────────────────────────────────────

    final MethodHandle bwu_launch_default;       // () -> int
    final MethodHandle bwu_launch_platform;      // () -> int
    final MethodHandle bwu_launch_managed;       // (ptr) -> int
    final MethodHandle bwu_set_provider_path;    // (ptr) -> int
    final MethodHandle bwu_get_provider_path;    // () -> ptr
    final MethodHandle bwu_find_processes;       // (ptr, u32, ptr) -> int
    final MethodHandle bwu_poll_process_event;   // (ptr) -> int

    // ── Module Loading ─────────────────────────────────────────────────────

    final MethodHandle bwu_load_module;          // (u32, ptr) -> int
    final MethodHandle bwu_load_module_raw;      // (u32, ptr, u32, ptr) -> int

    // ── Provider Account Discovery ─────────────────────────────────────────

    final MethodHandle bwu_refresh_provider_accounts;  // () -> int
    final MethodHandle bwu_get_provider_accounts;      // (ptr, u32, ptr) -> int

    // ── Jagex Account Authentication ───────────────────────────────────────

    final MethodHandle bwu_jagex_login;                // (ptr) -> int
    final MethodHandle bwu_jagex_get_account;          // (ptr, ptr) -> int
    final MethodHandle bwu_jagex_get_accounts;         // (ptr, u32, ptr) -> int
    final MethodHandle bwu_jagex_account_count;        // () -> int
    final MethodHandle bwu_jagex_remove_account;       // (ptr) -> int
    final MethodHandle bwu_jagex_select_character;     // (ptr, int) -> int
    final MethodHandle bwu_jagex_ensure_session;       // (ptr) -> int
    final MethodHandle bwu_jagex_launch;               // (ptr) -> int

    // ── Utility ────────────────────────────────────────────────────────────

    final MethodHandle bwu_generate_uuid;        // (ptr, u32) -> int

    BwuNative(SymbolLookup lookup) {
        Linker linker = Linker.nativeLinker();

        // ── Lifecycle ──
        bwu_init = downcall(linker, lookup, "bwu_init",
                FunctionDescriptor.of(JAVA_INT));
        bwu_shutdown = downcall(linker, lookup, "bwu_shutdown",
                FunctionDescriptor.ofVoid());
        bwu_get_status = downcall(linker, lookup, "bwu_get_status",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        bwu_get_last_error = downcall(linker, lookup, "bwu_get_last_error",
                FunctionDescriptor.of(ADDRESS));
        bwu_get_version = downcall(linker, lookup, "bwu_get_version",
                FunctionDescriptor.of(ADDRESS));

        // ── Authentication ──
        bwu_login = downcall(linker, lookup, "bwu_login",
                FunctionDescriptor.of(JAVA_INT));
        bwu_login_with_token = downcall(linker, lookup, "bwu_login_with_token",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        bwu_is_logged_in = downcall(linker, lookup, "bwu_is_logged_in",
                FunctionDescriptor.of(JAVA_INT));
        bwu_get_token = downcall(linker, lookup, "bwu_get_token",
                FunctionDescriptor.of(ADDRESS));
        bwu_get_user = downcall(linker, lookup, "bwu_get_user",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        bwu_logout = downcall(linker, lookup, "bwu_logout",
                FunctionDescriptor.ofVoid());
        bwu_save_token = downcall(linker, lookup, "bwu_save_token",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        bwu_load_token = downcall(linker, lookup, "bwu_load_token",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));

        // ── Module Management ──
        bwu_download_module = downcall(linker, lookup, "bwu_download_module",
                FunctionDescriptor.of(JAVA_INT));
        bwu_has_module = downcall(linker, lookup, "bwu_has_module",
                FunctionDescriptor.of(JAVA_INT));
        bwu_get_module_bytes = downcall(linker, lookup, "bwu_get_module_bytes",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

        // ── Account Management (Classic) ──
        bwu_add_account = downcall(linker, lookup, "bwu_add_account",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        bwu_remove_account = downcall(linker, lookup, "bwu_remove_account",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        bwu_get_account_count = downcall(linker, lookup, "bwu_get_account_count",
                FunctionDescriptor.of(JAVA_INT));
        bwu_get_account = downcall(linker, lookup, "bwu_get_account",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
        bwu_find_account = downcall(linker, lookup, "bwu_find_account",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        bwu_update_account = downcall(linker, lookup, "bwu_update_account",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        bwu_clear_accounts = downcall(linker, lookup, "bwu_clear_accounts",
                FunctionDescriptor.ofVoid());

        // ── Process Management ──
        bwu_launch_default = downcall(linker, lookup, "bwu_launch_default",
                FunctionDescriptor.of(JAVA_INT));
        bwu_launch_platform = downcall(linker, lookup, "bwu_launch_platform",
                FunctionDescriptor.of(JAVA_INT));
        bwu_launch_managed = downcall(linker, lookup, "bwu_launch_managed",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        bwu_set_provider_path = downcall(linker, lookup, "bwu_set_provider_path",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        bwu_get_provider_path = downcall(linker, lookup, "bwu_get_provider_path",
                FunctionDescriptor.of(ADDRESS));
        bwu_find_processes = downcall(linker, lookup, "bwu_find_processes",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        bwu_poll_process_event = downcall(linker, lookup, "bwu_poll_process_event",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));

        // ── Module Loading ──
        bwu_load_module = downcall(linker, lookup, "bwu_load_module",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
        bwu_load_module_raw = downcall(linker, lookup, "bwu_load_module_raw",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));

        // ── Provider Account Discovery ──
        bwu_refresh_provider_accounts = downcall(linker, lookup, "bwu_refresh_provider_accounts",
                FunctionDescriptor.of(JAVA_INT));
        bwu_get_provider_accounts = downcall(linker, lookup, "bwu_get_provider_accounts",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));

        // ── Jagex Account Authentication ──
        bwu_jagex_login = downcall(linker, lookup, "bwu_jagex_login",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        bwu_jagex_get_account = downcall(linker, lookup, "bwu_jagex_get_account",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        bwu_jagex_get_accounts = downcall(linker, lookup, "bwu_jagex_get_accounts",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        bwu_jagex_account_count = downcall(linker, lookup, "bwu_jagex_account_count",
                FunctionDescriptor.of(JAVA_INT));
        bwu_jagex_remove_account = downcall(linker, lookup, "bwu_jagex_remove_account",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        bwu_jagex_select_character = downcall(linker, lookup, "bwu_jagex_select_character",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        bwu_jagex_ensure_session = downcall(linker, lookup, "bwu_jagex_ensure_session",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        bwu_jagex_launch = downcall(linker, lookup, "bwu_jagex_launch",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));

        // ── Utility ──
        bwu_generate_uuid = downcall(linker, lookup, "bwu_generate_uuid",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    }

    private static MethodHandle downcall(Linker linker, SymbolLookup lookup,
                                         String name, FunctionDescriptor fd) {
        MemorySegment symbol = lookup.find(name)
                .orElseThrow(() -> new UnsatisfiedLinkError("bwu.dll missing symbol: " + name));
        return linker.downcallHandle(symbol, fd);
    }
}
