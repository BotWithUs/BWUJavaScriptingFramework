package com.botwithus.bot.cli.management;

import com.botwithus.bot.api.Client;
import com.botwithus.bot.core.impl.ClientProviderImpl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The client provider a management script gets, over the host's real provider. */
class ScopedClientProviderTest {

    private static final String UUID_B = "fedcba9876543210fedcba9876543210";
    private static final String PIPE_A = "BotWithUs_1001";
    private static final String PIPE_B = "BotWithUs_2002";
    private static final String PIPE_DEV = "BotWithUs_4004";
    private static final Map<String, String> ACCOUNTS = Map.of(
            PIPE_A, "0123456789abcdef0123456789abcdef",
            PIPE_B, UUID_B);

    private final ClientProviderImpl host = new ClientProviderImpl();
    private Client clientB;

    @BeforeEach
    void connectClients() {
        for (String pipe : List.of(PIPE_A, PIPE_B, PIPE_DEV)) {
            Client client = mock(Client.class);
            when(client.getName()).thenReturn(pipe);
            host.putClient(pipe, client);
            if (pipe.equals(PIPE_B)) {
                clientB = client;
            }
        }
    }

    private ScopedClientProvider scopedTo(Scope scope) {
        return new ScopedClientProvider(host, () -> scope, pipe -> Optional.ofNullable(ACCOUNTS.get(pipe)));
    }

    @Test
    void aClientScriptTarget_revealsOnlyThatClient() {
        ScopedClientProvider scoped = scopedTo(
                new Scope(false, List.of(), List.of(new Target.ClientScript(UUID_B, "Fishing"))));

        assertAll(
                () -> assertEquals(Set.of(PIPE_B), scoped.getClientNames()),
                () -> assertEquals(List.of(clientB), List.copyOf(scoped.getClients())),
                () -> assertEquals(Optional.of(clientB), scoped.getClient(PIPE_B)),
                () -> assertEquals(Optional.empty(), scoped.getClient(PIPE_A)));
    }

    @Test
    void theWholeHost_revealsEveryClient_evenOneWithNoAccount() {
        ScopedClientProvider scoped = scopedTo(new Scope(true, List.of(), List.of()));

        assertEquals(Set.of(PIPE_A, PIPE_B, PIPE_DEV), scoped.getClientNames());
    }

    @Test
    void noTargets_revealNothing() {
        ScopedClientProvider scoped = scopedTo(Scope.NOT_APPLIED);

        assertAll(
                () -> assertEquals(Set.of(), scoped.getClientNames()),
                () -> assertEquals(List.of(), List.copyOf(scoped.getClients())),
                () -> assertEquals(Optional.empty(), scoped.getClient(PIPE_B)));
    }
}
