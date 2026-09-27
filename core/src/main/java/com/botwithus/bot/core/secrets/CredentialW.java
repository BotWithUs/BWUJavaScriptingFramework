package com.botwithus.bot.core.secrets;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.StructLayout;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * The x64 layout of {@code CREDENTIALW}, from the Windows SDK header
 * {@code um/wincred.h}, and the constants from the same header that
 * {@link WindowsCredentialStore} passes.
 *
 * <pre>{@code
 * typedef struct _CREDENTIALW {          offset  size
 *     DWORD    Flags;                         0     4
 *     DWORD    Type;                          4     4
 *     LPWSTR   TargetName;                    8     8
 *     LPWSTR   Comment;                      16     8
 *     FILETIME LastWritten;                  24     8   two DWORDs, 4-byte aligned
 *     DWORD    CredentialBlobSize;           32     4
 *     (padding)                              36     4   aligns the next pointer to 8
 *     LPBYTE   CredentialBlob;               40     8
 *     DWORD    Persist;                      48     4
 *     DWORD    AttributeCount;               52     4
 *     PCREDENTIAL_ATTRIBUTEW Attributes;     56     8
 *     LPWSTR   TargetAlias;                  64     8
 *     LPWSTR   UserName;                     72     8
 * } CREDENTIALW;                          sizeof 80, alignment 8
 * }</pre>
 *
 * <p>The offsets were checked against {@code offsetof} and {@code sizeof} from the
 * header as MSVC compiles it for x64. {@code CredentialWTest} pins every one, so a
 * layout edit here fails a test rather than corrupting a native call.</p>
 */
final class CredentialW {

    /** {@code CRED_TYPE_GENERIC}: a credential only this application interprets. */
    static final int CRED_TYPE_GENERIC = 1;

    /** {@code CRED_PERSIST_LOCAL_MACHINE}: survives logoff, stays on this PC, this Windows user. */
    static final int CRED_PERSIST_LOCAL_MACHINE = 2;

    /** {@code ERROR_NOT_FOUND}: {@code CredReadW}/{@code CredDeleteW} found no such target. */
    static final int ERROR_NOT_FOUND = 1168;

    /** No {@code CRED_FLAGS_*} / {@code CRED_PRESERVE_*} options. */
    static final int NO_FLAGS = 0;

    private static final long LAST_WRITTEN_BYTES = 8;
    private static final long POINTER_PADDING_BYTES = 4;

    static final StructLayout LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("Flags"),
            JAVA_INT.withName("Type"),
            ADDRESS.withName("TargetName"),
            ADDRESS.withName("Comment"),
            MemoryLayout.paddingLayout(LAST_WRITTEN_BYTES).withName("LastWritten"),
            JAVA_INT.withName("CredentialBlobSize"),
            MemoryLayout.paddingLayout(POINTER_PADDING_BYTES),
            ADDRESS.withName("CredentialBlob"),
            JAVA_INT.withName("Persist"),
            JAVA_INT.withName("AttributeCount"),
            ADDRESS.withName("Attributes"),
            ADDRESS.withName("TargetAlias"),
            ADDRESS.withName("UserName"));

    static final long TYPE = offsetOf("Type");
    static final long TARGET_NAME = offsetOf("TargetName");
    static final long COMMENT = offsetOf("Comment");
    static final long CREDENTIAL_BLOB_SIZE = offsetOf("CredentialBlobSize");
    static final long CREDENTIAL_BLOB = offsetOf("CredentialBlob");
    static final long PERSIST = offsetOf("Persist");

    private CredentialW() {
    }

    static long offsetOf(String field) {
        return LAYOUT.byteOffset(groupElement(field));
    }
}
