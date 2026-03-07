package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.isc.MessageBus;
import com.botwithus.bot.api.isc.ScriptMessage;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class MessageBusImpl implements MessageBus {

    private final Map<String, List<Consumer<ScriptMessage>>> subscribers = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<ScriptMessage>> pendingRequests = new ConcurrentHashMap<>();

    @Override
    public void subscribe(String channel, Consumer<ScriptMessage> handler) {
        subscribers.computeIfAbsent(channel, k -> new CopyOnWriteArrayList<>()).add(handler);
    }

    @Override
    public void unsubscribe(String channel, Consumer<ScriptMessage> handler) {
        List<Consumer<ScriptMessage>> list = subscribers.get(channel);
        if (list != null) {
            list.remove(handler);
        }
    }

    @Override
    public void publish(String channel, String sender, Object payload) {
        List<Consumer<ScriptMessage>> list = subscribers.get(channel);
        if (list == null || list.isEmpty()) {
            return;
        }
        ScriptMessage message = new ScriptMessage(channel, sender, payload, System.currentTimeMillis());
        for (Consumer<ScriptMessage> handler : list) {
            Thread.startVirtualThread(() -> handler.accept(message));
        }
    }

    @Override
    public CompletableFuture<ScriptMessage> request(String channel, String sender, Object payload, long timeoutMs) {
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<ScriptMessage> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);

        ScriptMessage message = new ScriptMessage(channel, sender, payload, System.currentTimeMillis(), requestId);
        List<Consumer<ScriptMessage>> list = subscribers.get(channel);
        if (list != null && !list.isEmpty()) {
            for (Consumer<ScriptMessage> handler : list) {
                Thread.startVirtualThread(() -> handler.accept(message));
            }
        }

        return future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .whenComplete((result, error) -> pendingRequests.remove(requestId));
    }

    @Override
    public void respond(String requestId, String channel, String sender, Object payload) {
        CompletableFuture<ScriptMessage> future = pendingRequests.remove(requestId);
        if (future != null) {
            ScriptMessage response = new ScriptMessage(channel, sender, payload, System.currentTimeMillis(), requestId);
            future.complete(response);
        }
    }
}
