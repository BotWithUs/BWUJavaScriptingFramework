package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.cli.events.ClientRef;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** A {@link ClientDirectory} a test fills in by hand. */
final class FakeClientDirectory implements ClientDirectory {

    private final Map<String, String> names = new HashMap<>();
    private final Set<String> exited = new HashSet<>();
    private final List<ClientSnapshot> clients = new ArrayList<>();

    FakeClientDirectory name(String pipe, String name) {
        names.put(pipe, name);
        return this;
    }

    FakeClientDirectory forget(String pipe) {
        names.remove(pipe);
        return this;
    }

    FakeClientDirectory exited(String pipe) {
        exited.add(pipe);
        return this;
    }

    FakeClientDirectory client(ClientSnapshot snapshot) {
        clients.add(snapshot);
        return this;
    }

    @Override
    public Optional<String> displayName(ClientRef client) {
        return Optional.ofNullable(names.get(client.pipe()));
    }

    @Override
    public boolean hasExited(ClientRef client) {
        return exited.contains(client.pipe());
    }

    @Override
    public List<ClientSnapshot> clients() {
        return List.copyOf(clients);
    }
}
