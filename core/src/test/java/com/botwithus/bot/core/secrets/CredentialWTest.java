package com.botwithus.bot.core.secrets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code CREDENTIALW} as MSVC lays it out for x64 from {@code um/wincred.h}: these
 * are the {@code offsetof} / {@code sizeof} / alignment values that header yields.
 * No native call is made.
 */
class CredentialWTest {

    @Test
    void layout_matchesTheX64Header() {
        assertAll(
                () -> assertEquals(80, CredentialW.LAYOUT.byteSize(), "sizeof(CREDENTIALW)"),
                () -> assertEquals(8, CredentialW.LAYOUT.byteAlignment(), "alignment"),
                () -> assertEquals(0, CredentialW.offsetOf("Flags")),
                () -> assertEquals(4, CredentialW.offsetOf("Type")),
                () -> assertEquals(8, CredentialW.offsetOf("TargetName")),
                () -> assertEquals(16, CredentialW.offsetOf("Comment")),
                () -> assertEquals(24, CredentialW.offsetOf("LastWritten")),
                () -> assertEquals(32, CredentialW.offsetOf("CredentialBlobSize")),
                () -> assertEquals(40, CredentialW.offsetOf("CredentialBlob")),
                () -> assertEquals(48, CredentialW.offsetOf("Persist")),
                () -> assertEquals(52, CredentialW.offsetOf("AttributeCount")),
                () -> assertEquals(56, CredentialW.offsetOf("Attributes")),
                () -> assertEquals(64, CredentialW.offsetOf("TargetAlias")),
                () -> assertEquals(72, CredentialW.offsetOf("UserName")));
    }

    @Test
    void constants_matchTheHeader() {
        assertAll(
                () -> assertEquals(1, CredentialW.CRED_TYPE_GENERIC),
                () -> assertEquals(2, CredentialW.CRED_PERSIST_LOCAL_MACHINE),
                () -> assertEquals(1168, CredentialW.ERROR_NOT_FOUND));
    }
}
