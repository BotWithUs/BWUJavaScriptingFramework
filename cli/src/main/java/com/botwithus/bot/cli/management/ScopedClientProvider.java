package com.botwithus.bot.cli.management;

import com.botwithus.bot.api.Client;
import com.botwithus.bot.api.ClientProvider;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The client provider one management script is given: the host's, limited to
 * the clients its targets cover. A client its targets do not cover is not
 * listed and cannot be looked up, so the script cannot reach that client's
 * game API around its {@link ScopedClientOrchestrator}.
 *
 * <p>A client is covered when any target covers any script on it: the
 * provider hands out whole clients, not single scripts.</p>
 */
public final class ScopedClientProvider implements ClientProvider {

    private final ClientProvider delegate;
    private final Supplier<Scope> scope;
    private final Function<String, Optional<String>> accountOf;

    /**
     * @param delegate  the host's provider, over every client
     * @param scope     the script's targets as they are now; asked on every call
     * @param accountOf the account UUID of the client on a pipe; empty for a
     *                  client with no account
     */
    public ScopedClientProvider(ClientProvider delegate, Supplier<Scope> scope,
                                Function<String, Optional<String>> accountOf) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.accountOf = Objects.requireNonNull(accountOf, "accountOf");
    }

    @Override
    public Optional<Client> getClient(String name) {
        return scope.get().coversClient(accountOf.apply(name)) ? delegate.getClient(name) : Optional.empty();
    }

    @Override
    public Collection<Client> getClients() {
        Scope now = scope.get();
        return delegate.getClients().stream()
                .filter(client -> now.coversClient(accountOf.apply(client.getName())))
                .toList();
    }

    @Override
    public Set<String> getClientNames() {
        Scope now = scope.get();
        return delegate.getClientNames().stream()
                .filter(name -> now.coversClient(accountOf.apply(name)))
                .collect(Collectors.toUnmodifiableSet());
    }
}
