package com.botwithus.bot.cli;

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
 * One server instance of a real Windows named pipe, made through Panama, so a
 * test can watch whether the host's client end of it is still open.
 *
 * <p>{@link #clientState()} peeks the pipe: a connected client reads as
 * {@link ClientState#CONNECTED}; once the client closes its handle the pipe is
 * broken, which is the only way to see from outside that a {@code PipeClient}
 * was really closed rather than merely dropped.</p>
 */
final class TestPipeServer implements AutoCloseable {

    /** What the server end sees of its client. */
    enum ClientState { WAITING, CONNECTED, CLOSED }

    private static final int PIPE_ACCESS_DUPLEX = 0x3;
    private static final int PIPE_TYPE_BYTE_WAIT = 0x0;
    private static final int ONE_INSTANCE = 1;
    private static final int BUFFER_BYTES = 4096;
    private static final int ERROR_BROKEN_PIPE = 109;
    private static final int ERROR_PIPE_LISTENING = 536;
    /** What PeekNamedPipe reports on a server end no client has connected to yet. */
    private static final int ERROR_BAD_PIPE = 230;
    private static final int ERROR_NO_DATA = 232;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final MemoryLayout CALL_STATE = Linker.Option.captureStateLayout();
    private static final VarHandle LAST_ERROR = CALL_STATE.varHandle(
            MemoryLayout.PathElement.groupElement("GetLastError"));

    private final Arena arena = Arena.ofShared();
    private final MethodHandle peekNamedPipe;
    private final MethodHandle closeHandle;
    private final MemorySegment handle;

    /** @param name the pipe's name after {@code \\.\pipe\} */
    TestPipeServer(String name) throws Throwable {
        SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", arena);
        Linker.Option capture = Linker.Option.captureCallState("GetLastError");
        MethodHandle createNamedPipe = LINKER.downcallHandle(kernel32.findOrThrow("CreateNamedPipeW"),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        peekNamedPipe = LINKER.downcallHandle(kernel32.findOrThrow("PeekNamedPipe"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                capture);
        closeHandle = LINKER.downcallHandle(kernel32.findOrThrow("CloseHandle"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        MemorySegment wideName = wide("\\\\.\\pipe\\" + name);
        // rule-exception: {rule:no-casts} - invokeExact is signature-polymorphic; the cast declares its return type.
        handle = (MemorySegment) createNamedPipe.invokeExact(wideName, PIPE_ACCESS_DUPLEX, PIPE_TYPE_BYTE_WAIT,
                ONE_INSTANCE, BUFFER_BYTES, BUFFER_BYTES, 0, MemorySegment.NULL);
        if (handle.address() == -1L) {
            throw new IllegalStateException("CreateNamedPipeW failed for " + name);
        }
    }

    /** @return what the server end sees of its client now */
    ClientState clientState() throws Throwable {
        MemorySegment state = arena.allocate(CALL_STATE);
        int ok = (int) peekNamedPipe.invokeExact(state, handle, MemorySegment.NULL, 0, MemorySegment.NULL,
                MemorySegment.NULL, MemorySegment.NULL);
        if (ok != 0) {
            return ClientState.CONNECTED;
        }
        int error = (int) LAST_ERROR.get(state, 0L);
        return switch (error) {
            case ERROR_PIPE_LISTENING, ERROR_BAD_PIPE -> ClientState.WAITING;
            case ERROR_BROKEN_PIPE, ERROR_NO_DATA -> ClientState.CLOSED;
            default -> throw new IllegalStateException("PeekNamedPipe failed: " + error);
        };
    }

    @Override
    public void close() {
        try {
            int ignored = (int) closeHandle.invokeExact(handle);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        } finally {
            arena.close();
        }
    }

    private MemorySegment wide(String text) {
        byte[] utf16 = (text + "\0").getBytes(StandardCharsets.UTF_16LE);
        MemorySegment segment = arena.allocate(utf16.length);
        segment.copyFrom(MemorySegment.ofArray(utf16));
        return segment;
    }
}
