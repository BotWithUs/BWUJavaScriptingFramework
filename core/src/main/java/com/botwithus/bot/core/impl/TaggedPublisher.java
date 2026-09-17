package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.debug.ScriptContextPublisher;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thin per-script tagged wrapper around a {@link ScriptContextChannel}. Builds
 * the {@code {script, connection, t_us, kind, ...}} envelope and hands it to
 * the channel's bounded queue. Never blocks the caller.
 *
 * <p>Package-private — instantiated only by
 * {@link ScriptContextChannel#publisherFor(String)}.</p>
 */
final class TaggedPublisher implements ScriptContextPublisher {

    private final ScriptContextChannel channel;
    private final String scriptName;

    TaggedPublisher(ScriptContextChannel channel, String scriptName) {
        this.channel = channel;
        this.scriptName = scriptName;
    }

    @Override
    public void state(String state) {
        if (state == null) {
            return;
        }
        Map<String, Object> data = baseEnvelope("state");
        data.put("state", state);
        channel.enqueue(data);
    }

    @Override
    public void state(String state, String detail) {
        if (state == null) {
            return;
        }
        Map<String, Object> data = baseEnvelope("state");
        data.put("state", state);
        if (detail != null) {
            data.put("detail", detail);
        }
        channel.enqueue(data);
    }

    @Override
    public void trace(String level, String message) {
        if (message == null) {
            return;
        }
        Map<String, Object> data = baseEnvelope("trace");
        data.put("level", level != null ? level : "INFO");
        data.put("message", message);
        channel.enqueue(data);
    }

    @Override
    public void annotation(String key, Object value) {
        if (key == null) {
            return;
        }
        Map<String, Object> data = baseEnvelope("annotation");
        data.put("key", key);
        data.put("value", value);
        channel.enqueue(data);
    }

    private Map<String, Object> baseEnvelope(String kind) {
        Map<String, Object> data = new LinkedHashMap<>(7);
        data.put("script", scriptName);
        String conn = channel.getConnectionName();
        if (conn != null) {
            data.put("connection", conn);
        }
        data.put("t_us", System.currentTimeMillis() * 1000L);
        data.put("kind", kind);
        return data;
    }
}
