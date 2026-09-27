package com.botwithus.bot.cli;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * A named set of connection names. Safe to read from any thread while another
 * edits it: members keep their insertion order, and iterating
 * {@link #getConnectionNames()} walks a snapshot that later edits do not disturb.
 */
public class ConnectionGroup {

    private final String name;
    private volatile String description;
    private final Set<String> connectionNames = new CopyOnWriteArraySet<>();

    public ConnectionGroup(String name) {
        this.name = name;
    }

    public ConnectionGroup(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public String getName() { return name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public void add(String connectionName) {
        connectionNames.add(connectionName);
    }

    public void remove(String connectionName) {
        connectionNames.remove(connectionName);
    }

    public boolean contains(String connectionName) {
        return connectionNames.contains(connectionName);
    }

    public Set<String> getConnectionNames() {
        return Collections.unmodifiableSet(connectionNames);
    }
}
