package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.Client;
import com.botwithus.bot.api.ClientProvider;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ClientProviderImpl implements ClientProvider {

    public ClientProviderImpl() {}

    private final ConcurrentHashMap<String, Client> clients = new ConcurrentHashMap<>();

    public void putClient(String name, Client client) {
        clients.put(name, client);
    }

    public Client removeClient(String name) {
        return clients.remove(name);
    }

    @Override
    public Optional<Client> getClient(String name) {
        return Optional.ofNullable(clients.get(name));
    }

    @Override
    public Collection<Client> getClients() {
        return List.copyOf(clients.values());
    }

    @Override
    public Set<String> getClientNames() {
        return Set.copyOf(clients.keySet());
    }
}
