package com.botwithus.bot.core.secrets;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecretTest {

    @Test
    void toString_neverShowsTheValue() {
        Secret secret = new Secret("https://discord.com/api/webhooks/1/token");

        assertFalse(secret.toString().contains("token"), secret.toString());
        assertFalse(String.valueOf(List.of(secret)).contains("discord"));
    }

    @Test
    void reveal_returnsTheValue() {
        assertEquals("tk_abc", new Secret("tk_abc").reveal());
    }

    @Test
    void blank_isRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Secret("  "));
    }
}
