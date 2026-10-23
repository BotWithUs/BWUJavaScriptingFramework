package com.botwithus.bot.core.launcher;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;

/**
 * Reads this process's user SID and logon session through Panama: Java has no
 * API for either (ADR 2.1). The calls are the ones the launcher's own
 * {@code CurrentScope} makes, so both sides hash the same input.
 */
final class ScopeNative {

    /** {@code TOKEN_QUERY} from winnt.h. */
    private static final int TOKEN_QUERY = 0x0008;
    /** {@code TokenUser} from the {@code TOKEN_INFORMATION_CLASS} enum. */
    private static final int TOKEN_USER_CLASS = 1;
    /** The token-information buffer; the launcher uses the same size. */
    private static final int TOKEN_BUFFER_BYTES = 256;
    /** Bound for reading the SID string: a SID string is under 200 characters. */
    private static final int MAX_SID_STRING_BYTES = 1024;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final MemoryLayout CALL_STATE = Linker.Option.captureStateLayout();
    private static final VarHandle LAST_ERROR = CALL_STATE.varHandle(
            MemoryLayout.PathElement.groupElement("GetLastError"));
    private static final Linker.Option CAPTURE = Linker.Option.captureCallState("GetLastError");

    private final MethodHandle getCurrentProcess;
    private final MethodHandle processIdToSessionId;
    private final MethodHandle closeHandle;
    private final MethodHandle localFree;
    private final MethodHandle openProcessToken;
    private final MethodHandle getTokenInformation;
    private final MethodHandle convertSidToStringSid;

    ScopeNative() {
        Arena arena = Arena.global();
        SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", arena);
        SymbolLookup advapi32 = SymbolLookup.libraryLookup("advapi32", arena);
        getCurrentProcess = LINKER.downcallHandle(kernel32.findOrThrow("GetCurrentProcess"),
                FunctionDescriptor.of(ValueLayout.ADDRESS));
        processIdToSessionId = LINKER.downcallHandle(kernel32.findOrThrow("ProcessIdToSessionId"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS), CAPTURE);
        closeHandle = LINKER.downcallHandle(kernel32.findOrThrow("CloseHandle"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        localFree = LINKER.downcallHandle(kernel32.findOrThrow("LocalFree"),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        openProcessToken = LINKER.downcallHandle(advapi32.findOrThrow("OpenProcessToken"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS), CAPTURE);
        getTokenInformation = LINKER.downcallHandle(advapi32.findOrThrow("GetTokenInformation"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS), CAPTURE);
        convertSidToStringSid = LINKER.downcallHandle(advapi32.findOrThrow("ConvertSidToStringSidW"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS), CAPTURE);
    }

    /** @return this process's SID string and logon session id */
    ServiceScope.ScopeIdentity read() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(CALL_STATE);
            return new ServiceScope.ScopeIdentity(readSid(arena, state), readSessionId(arena, state));
        } catch (ScopeException e) {
            throw e;
        } catch (Throwable t) {
            throw new ScopeException("the Win32 scope calls failed", t);
        }
    }

    private long readSessionId(Arena arena, MemorySegment state) throws Throwable {
        MemorySegment out = arena.allocate(ValueLayout.JAVA_INT);
        int pid = Math.toIntExact(ProcessHandle.current().pid());
        check((int) processIdToSessionId.invokeExact(state, pid, out), state, "ProcessIdToSessionId");
        return Integer.toUnsignedLong(out.get(ValueLayout.JAVA_INT, 0));
    }

    private String readSid(Arena arena, MemorySegment state) throws Throwable {
        MemorySegment tokenOut = arena.allocate(ValueLayout.ADDRESS);
        // rule-exception: {rule:no-casts} - invokeExact is signature-polymorphic; the cast declares
        // its return type and converts nothing (the same form core/shm/Kernel32 uses).
        MemorySegment process = (MemorySegment) getCurrentProcess.invokeExact();
        check((int) openProcessToken.invokeExact(state, process, TOKEN_QUERY, tokenOut), state, "OpenProcessToken");
        MemorySegment token = tokenOut.get(ValueLayout.ADDRESS, 0);
        try {
            MemorySegment buffer = arena.allocate(TOKEN_BUFFER_BYTES, ValueLayout.ADDRESS.byteAlignment());
            MemorySegment needed = arena.allocate(ValueLayout.JAVA_INT);
            check((int) getTokenInformation.invokeExact(state, token, TOKEN_USER_CLASS, buffer,
                    TOKEN_BUFFER_BYTES, needed), state, "GetTokenInformation(TokenUser)");
            return sidString(arena, state, buffer.get(ValueLayout.ADDRESS, 0));
        } finally {
            int ignored = (int) closeHandle.invokeExact(token);
        }
    }

    private String sidString(Arena arena, MemorySegment state, MemorySegment sid) throws Throwable {
        MemorySegment textOut = arena.allocate(ValueLayout.ADDRESS);
        check((int) convertSidToStringSid.invokeExact(state, sid, textOut), state, "ConvertSidToStringSidW");
        MemorySegment text = textOut.get(ValueLayout.ADDRESS, 0);
        try {
            return text.reinterpret(MAX_SID_STRING_BYTES).getString(0, StandardCharsets.UTF_16LE);
        } finally {
            // rule-exception: {rule:no-casts} - invokeExact is signature-polymorphic; the cast declares
            // its return type and converts nothing (the same form core/shm/Kernel32 uses).
            MemorySegment ignored = (MemorySegment) localFree.invokeExact(text);
        }
    }

    private static void check(int result, MemorySegment state, String call) {
        if (result == 0) {
            int error = (int) LAST_ERROR.get(state, 0L);
            throw new ScopeException(call + " failed: GetLastError=" + error);
        }
    }

    /** One of the scope calls failed; the host cannot name the service's pipe. */
    static final class ScopeException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        ScopeException(String message) {
            super(message);
        }

        ScopeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
