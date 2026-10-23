package com.botwithus.bot.cli.events;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientKeyTest {

    private static final String PIPE = "BotWithUs_4242";
    private static final String UUID = "0123456789abcdef0123456789abcdef";
    private static final int SECOND = 2;

    @Test
    void aRealUuid_keysTheClientByItsAccount() {
        ClientKey key = ClientKey.of(UUID, PIPE);

        assertAll(
                () -> assertEquals(new ClientKey.Account(UUID, 1), key),
                () -> assertEquals(UUID, key.value()),
                () -> assertEquals(Optional.of(UUID), key.accountUuid()),
                () -> assertTrue(key.isRemembered()));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "dev_uuid"})
    void aMissingOrPlaceholderUuid_keysTheClientByItsPipe(String uuid) {
        ClientKey key = ClientKey.of(uuid, PIPE);

        assertAll(
                () -> assertEquals(new ClientKey.Pipe(PIPE), key),
                () -> assertEquals("pipe:" + PIPE, key.value()),
                () -> assertTrue(key.accountUuid().isEmpty()),
                () -> assertFalse(key.isRemembered(), "a pipe key must never be remembered"));
    }

    @Test
    void aSecondClientOnAnAccount_isShownWithItsInstance_andNotRemembered() {
        ClientKey second = new ClientKey.Account(UUID, SECOND);

        assertEquals(UUID + "#2", second.value());
        assertFalse(second.isRemembered());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "dev_uuid"})
    void anAccountKey_refusesAPlaceholder(String uuid) {
        assertThrows(IllegalArgumentException.class, () -> ClientKey.account(uuid));
    }

    @Test
    void anAccountKey_refusesAnInstanceBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> new ClientKey.Account(UUID, 0));
    }
}
