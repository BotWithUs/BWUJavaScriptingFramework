package com.botwithus.bot.core.secrets;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * A {@link CredentialStore} over Windows Credential Manager, bound to
 * {@code advapi32}'s {@code CredWriteW} / {@code CredReadW} / {@code CredDeleteW} /
 * {@code CredFree} through the Foreign Function &amp; Memory API.
 *
 * <p>Every secret is a {@code CRED_TYPE_GENERIC} credential persisted
 * {@code CRED_PERSIST_LOCAL_MACHINE}: it survives a restart, stays on this PC and
 * belongs to the signed-in Windows user, who can see and remove it under Windows
 * Credentials in Control Panel. The blob is the secret in UTF-16LE, the form
 * {@code cmdkey /generic} writes, so it can be inspected or replaced with the
 * system's own tools. Every buffer {@code CredReadW} returns is released with
 * {@code CredFree}, and every copy of the secret this class makes in native memory
 * is zeroed before it is freed.</p>
 *
 * <p>Failures are reported with the Win32 error {@code GetLastError} held at the
 * moment of the call (captured by the linker, so JVM-internal calls in between
 * cannot overwrite it).</p>
 */
public final class WindowsCredentialStore implements CredentialStore {

    private static final String LIBRARY = "advapi32";
    private static final String LAST_ERROR = "GetLastError";
    /** Shown in Credential Manager beside each entry, so a user knows what it is for. */
    private static final String COMMENT = "BotWithUs alert integration";
    private static final int WIN32_FALSE = 0;

    private final MethodHandle credWrite;
    private final MethodHandle credRead;
    private final MethodHandle credDelete;
    private final MethodHandle credFree;
    private final StructLayout callState;
    private final long lastErrorOffset;

    /**
     * Binds to {@code advapi32}.
     *
     * @throws CredentialStoreException if the library or an entry point cannot be
     *                                  found, as on any system but Windows
     */
    public WindowsCredentialStore() {
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup advapi32 = SymbolLookup.libraryLookup(LIBRARY, Arena.global());
            Linker.Option captureLastError = Linker.Option.captureCallState(LAST_ERROR);
            this.callState = Linker.Option.captureStateLayout();
            this.lastErrorOffset = callState.byteOffset(groupElement(LAST_ERROR));
            this.credWrite = linker.downcallHandle(advapi32.findOrThrow("CredWriteW"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), captureLastError);
            this.credRead = linker.downcallHandle(advapi32.findOrThrow("CredReadW"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS), captureLastError);
            this.credDelete = linker.downcallHandle(advapi32.findOrThrow("CredDeleteW"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT), captureLastError);
            this.credFree = linker.downcallHandle(advapi32.findOrThrow("CredFree"),
                    FunctionDescriptor.ofVoid(ADDRESS));
        } catch (RuntimeException e) {
            throw new CredentialStoreException("Windows Credential Manager is not available", e);
        }
    }

    @Override
    public Optional<Secret> read(String target) {
        CredentialStore.requireTarget(target);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(callState);
            MemorySegment out = arena.allocate(ADDRESS);
            int ok = (int) credRead.invokeExact(state, wide(arena, target),
                    CredentialW.CRED_TYPE_GENERIC, CredentialW.NO_FLAGS, out);
            if (ok == WIN32_FALSE) {
                return notFoundOrThrow(state, "read", target);
            }
            MemorySegment credential = out.get(ADDRESS, 0).reinterpret(CredentialW.LAYOUT.byteSize());
            try {
                return Optional.of(blobOf(credential, target));
            } finally {
                credFree.invokeExact(credential);
            }
        } catch (CredentialStoreException e) {
            throw e;
        } catch (Throwable t) {
            throw new CredentialStoreException("Could not read credential " + target, t);
        }
    }

    @Override
    public void write(String target, Secret secret) {
        CredentialStore.requireTarget(target);
        CredentialStore.requireStorable(secret);
        byte[] bytes = secret.reveal().getBytes(StandardCharsets.UTF_16LE);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment blob = arena.allocate(bytes.length);
            MemorySegment.copy(bytes, 0, blob, JAVA_BYTE, 0, bytes.length);
            try {
                MemorySegment credential = credentialFor(arena, target, blob);
                MemorySegment state = arena.allocate(callState);
                int ok = (int) credWrite.invokeExact(state, credential, CredentialW.NO_FLAGS);
                if (ok == WIN32_FALSE) {
                    throw failure("write", target, state);
                }
            } finally {
                blob.fill((byte) 0);
            }
        } catch (CredentialStoreException e) {
            throw e;
        } catch (Throwable t) {
            throw new CredentialStoreException("Could not write credential " + target, t);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    @Override
    public boolean delete(String target) {
        CredentialStore.requireTarget(target);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(callState);
            int ok = (int) credDelete.invokeExact(state, wide(arena, target),
                    CredentialW.CRED_TYPE_GENERIC, CredentialW.NO_FLAGS);
            if (ok != WIN32_FALSE) {
                return true;
            }
            if (lastError(state) == CredentialW.ERROR_NOT_FOUND) {
                return false;
            }
            throw failure("delete", target, state);
        } catch (CredentialStoreException e) {
            throw e;
        } catch (Throwable t) {
            throw new CredentialStoreException("Could not delete credential " + target, t);
        }
    }

    /** A zeroed {@code CREDENTIALW} naming {@code target} and pointing at {@code blob}. */
    private static MemorySegment credentialFor(Arena arena, String target, MemorySegment blob) {
        MemorySegment credential = arena.allocate(CredentialW.LAYOUT);
        credential.set(JAVA_INT, CredentialW.TYPE, CredentialW.CRED_TYPE_GENERIC);
        credential.set(ADDRESS, CredentialW.TARGET_NAME, wide(arena, target));
        credential.set(ADDRESS, CredentialW.COMMENT, wide(arena, COMMENT));
        credential.set(JAVA_INT, CredentialW.CREDENTIAL_BLOB_SIZE, (int) blob.byteSize());
        credential.set(ADDRESS, CredentialW.CREDENTIAL_BLOB, blob);
        credential.set(JAVA_INT, CredentialW.PERSIST, CredentialW.CRED_PERSIST_LOCAL_MACHINE);
        return credential;
    }

    /** The secret in a {@code CREDENTIALW} that {@code CredReadW} returned. */
    private static Secret blobOf(MemorySegment credential, String target) {
        int size = credential.get(JAVA_INT, CredentialW.CREDENTIAL_BLOB_SIZE);
        if (size <= 0 || size % Character.BYTES != 0) {
            throw new CredentialStoreException("Credential " + target
                    + " does not hold text this host wrote", new IllegalStateException("blob size " + size));
        }
        MemorySegment blob = credential.get(ADDRESS, CredentialW.CREDENTIAL_BLOB).reinterpret(size);
        byte[] bytes = blob.toArray(JAVA_BYTE);
        try {
            return new Secret(new String(bytes, StandardCharsets.UTF_16LE));
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private Optional<Secret> notFoundOrThrow(MemorySegment state, String operation, String target) {
        if (lastError(state) == CredentialW.ERROR_NOT_FOUND) {
            return Optional.empty();
        }
        throw failure(operation, target, state);
    }

    private CredentialStoreException failure(String operation, String target, MemorySegment state) {
        return new CredentialStoreException("Could not " + operation + " credential " + target,
                lastError(state));
    }

    private int lastError(MemorySegment state) {
        return state.get(JAVA_INT, lastErrorOffset);
    }

    /** {@code text} as a NUL-terminated UTF-16LE string ({@code LPCWSTR}). */
    private static MemorySegment wide(Arena arena, String text) {
        return arena.allocateFrom(text, StandardCharsets.UTF_16LE);
    }
}
