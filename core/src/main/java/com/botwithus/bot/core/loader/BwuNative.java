package com.botwithus.bot.core.loader;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Raw Panama downcall handles for every exported function in bwu.dll.
 * <p>
 * Package-private — callers use {@link BwuClient} instead.
 * <p>
 * Field names use Java {@code camelCase} ({@code bwuInit}); the corresponding
 * native symbol names ({@code bwu_init}) are passed as snake_case strings to
 * {@link SymbolLookup#find(String)} and must not change.
 */
final class BwuNative {

    // ── Lifecycle ──────────────────────────────────────────────────────────

    final MethodHandle bwuInit;                  // () -> int
    final MethodHandle bwuShutdown;              // () -> void
    final MethodHandle bwuGetStatus;             // (ptr) -> int
    final MethodHandle bwuGetLastError;          // () -> ptr
    final MethodHandle bwuGetVersion;            // () -> ptr

    // ── Authentication ─────────────────────────────────────────────────────

    final MethodHandle bwuLogin;                 // () -> int
    final MethodHandle bwuLoginWithToken;        // (ptr) -> int
    final MethodHandle bwuIsLoggedIn;            // () -> int
    final MethodHandle bwuGetToken;              // () -> ptr
    final MethodHandle bwuGetUser;               // (ptr) -> int
    final MethodHandle bwuLogout;                // () -> void
    final MethodHandle bwuSaveToken;             // (ptr) -> int
    final MethodHandle bwuLoadToken;             // (ptr) -> int

    // ── Module Management ──────────────────────────────────────────────────

    final MethodHandle bwuRefreshModule;         // () -> int
    final MethodHandle bwuLoadLocalModule;       // (ptr) -> int  [dev builds only, may be null]

    // ── Account Management (Classic) ───────────────────────────────────────

    final MethodHandle bwuAddAccount;            // (ptr) -> int
    final MethodHandle bwuRemoveAccount;         // (ptr) -> int
    final MethodHandle bwuGetAccountCount;       // () -> int
    final MethodHandle bwuGetAccount;            // (int, ptr) -> int
    final MethodHandle bwuFindAccount;           // (ptr, ptr) -> int
    final MethodHandle bwuUpdateAccount;         // (ptr) -> int
    final MethodHandle bwuClearAccounts;         // () -> void

    // ── Launch (Non-blocking Triggers) ─────────────────────────────────────

    final MethodHandle bwuLaunchDefault;         // (ptr) -> int
    final MethodHandle bwuLaunchPlatform;        // (ptr) -> int
    final MethodHandle bwuLaunchManaged;         // (ptr, ptr) -> int
    final MethodHandle bwuSetProviderPath;       // (ptr) -> int
    final MethodHandle bwuGetProviderPath;       // () -> ptr

    // ── Provider Account Discovery ─────────────────────────────────────────

    final MethodHandle bwuRefreshProviderAccounts;  // () -> int
    final MethodHandle bwuGetProviderAccounts;      // (ptr, u32, ptr) -> int

    // ── Jagex Account Authentication ───────────────────────────────────────

    final MethodHandle bwuJagexLogin;                // (ptr) -> int
    final MethodHandle bwuJagexGetAccount;           // (ptr, ptr) -> int
    final MethodHandle bwuJagexGetAccounts;          // (ptr, u32, ptr) -> int
    final MethodHandle bwuJagexAccountCount;         // () -> int
    final MethodHandle bwuJagexRemoveAccount;        // (ptr) -> int
    final MethodHandle bwuJagexRestoreAccounts;      // () -> int
    final MethodHandle bwuJagexRefreshCharacters;    // (ptr) -> int
    final MethodHandle bwuJagexSelectCharacter;      // (ptr, int) -> int
    final MethodHandle bwuJagexEnsureSession;        // (ptr) -> int
    final MethodHandle bwuJagexLaunch;               // (ptr, ptr, int) -> int

    // ── Utility ────────────────────────────────────────────────────────────

    final MethodHandle bwuGenerateUuid;          // (ptr, u32) -> int

    BwuNative(SymbolLookup lookup) {
        Linker linker = Linker.nativeLinker();

        // ── Lifecycle ──
        bwuInit = downcall(linker, lookup, "bwu_init",
                FunctionDescriptor.of(JAVA_INT));
        bwuShutdown = downcall(linker, lookup, "bwu_shutdown",
                FunctionDescriptor.ofVoid());
        bwuGetStatus = downcall(linker, lookup, "bwu_get_status",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        bwuGetLastError = downcall(linker, lookup, "bwu_get_last_error",
                FunctionDescriptor.of(ADDRESS));
        bwuGetVersion = downcall(linker, lookup, "bwu_get_version",
                FunctionDescriptor.of(ADDRESS));

        // ── Authentication ──
        bwuLogin = downcall(linker, lookup, "bwu_login",
                FunctionDescriptor.of(JAVA_INT));
        bwuLoginWithToken = downcall(linker, lookup, "bwu_login_with_token",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        bwuIsLoggedIn = downcall(linker, lookup, "bwu_is_logged_in",
                FunctionDescriptor.of(JAVA_INT));
        bwuGetToken = downcall(linker, lookup, "bwu_get_token",
                FunctionDescriptor.of(ADDRESS));
        bwuGetUser = downcall(linker, lookup, "bwu_get_user",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        bwuLogout = downcall(linker, lookup, "bwu_logout",
                FunctionDescriptor.ofVoid());
        bwuSaveToken = downcall(linker, lookup, "bwu_save_token",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        bwuLoadToken = downcall(linker, lookup, "bwu_load_token",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));

        // ── Module Management ──
        bwuRefreshModule = downcall(linker, lookup, "bwu_refresh_module",
                FunctionDescriptor.of(JAVA_INT));
        bwuLoadLocalModule = optionalDowncall(linker, lookup, "bwu_load_local_module",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));

        // ── Account Management (Classic) ──
        bwuAddAccount = downcall(linker, lookup, "bwu_add_account",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        bwuRemoveAccount = downcall(linker, lookup, "bwu_remove_account",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        bwuGetAccountCount = downcall(linker, lookup, "bwu_get_account_count",
                FunctionDescriptor.of(JAVA_INT));
        bwuGetAccount = downcall(linker, lookup, "bwu_get_account",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
        bwuFindAccount = downcall(linker, lookup, "bwu_find_account",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        bwuUpdateAccount = downcall(linker, lookup, "bwu_update_account",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        bwuClearAccounts = downcall(linker, lookup, "bwu_clear_accounts",
                FunctionDescriptor.ofVoid());

        // ── Launch (Non-blocking Triggers) ──
        bwuLaunchDefault = downcall(linker, lookup, "bwu_launch_default",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        bwuLaunchPlatform = downcall(linker, lookup, "bwu_launch_platform",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        bwuLaunchManaged = downcall(linker, lookup, "bwu_launch_managed",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        bwuSetProviderPath = downcall(linker, lookup, "bwu_set_provider_path",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        bwuGetProviderPath = downcall(linker, lookup, "bwu_get_provider_path",
                FunctionDescriptor.of(ADDRESS));

        // ── Provider Account Discovery ──
        bwuRefreshProviderAccounts = downcall(linker, lookup, "bwu_refresh_provider_accounts",
                FunctionDescriptor.of(JAVA_INT));
        bwuGetProviderAccounts = downcall(linker, lookup, "bwu_get_provider_accounts",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));

        // ── Jagex Account Authentication ──
        bwuJagexLogin = downcall(linker, lookup, "bwu_jagex_login",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        bwuJagexGetAccount = downcall(linker, lookup, "bwu_jagex_get_account",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        bwuJagexGetAccounts = downcall(linker, lookup, "bwu_jagex_get_accounts",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        bwuJagexAccountCount = downcall(linker, lookup, "bwu_jagex_account_count",
                FunctionDescriptor.of(JAVA_INT));
        bwuJagexRemoveAccount = downcall(linker, lookup, "bwu_jagex_remove_account",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        bwuJagexRestoreAccounts = downcall(linker, lookup, "bwu_jagex_restore_accounts",
                FunctionDescriptor.of(JAVA_INT));
        bwuJagexRefreshCharacters = downcall(linker, lookup, "bwu_jagex_refresh_characters",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        bwuJagexSelectCharacter = downcall(linker, lookup, "bwu_jagex_select_character",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        bwuJagexEnsureSession = downcall(linker, lookup, "bwu_jagex_ensure_session",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        bwuJagexLaunch = downcall(linker, lookup, "bwu_jagex_launch",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));

        // ── Utility ──
        bwuGenerateUuid = downcall(linker, lookup, "bwu_generate_uuid",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    }

    private static MethodHandle downcall(Linker linker, SymbolLookup lookup,
                                         String name, FunctionDescriptor fd) {
        MemorySegment symbol = lookup.find(name)
                .orElseThrow(() -> new UnsatisfiedLinkError("bwu.dll missing symbol: " + name));
        return linker.downcallHandle(symbol, fd);
    }

    private static MethodHandle optionalDowncall(Linker linker, SymbolLookup lookup,
                                                  String name, FunctionDescriptor fd) {
        return lookup.find(name)
                .map(symbol -> linker.downcallHandle(symbol, fd))
                .orElse(null);
    }
}
