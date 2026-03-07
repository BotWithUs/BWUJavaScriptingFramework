package com.botwithus.bot.core.test;

import com.botwithus.bot.core.msgpack.MessagePackCodec;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Mock game server for testing RPC calls end-to-end.
 */
public class MockGameServer {

    private final InputStream input;
    private final OutputStream output;
    private final Map<String, Function<Map<String, Object>, Object>> handlers = new ConcurrentHashMap<>();
    private volatile boolean running;
    private Thread thread;

    public MockGameServer(InMemoryTransport transport) {
        this.input = transport.getServerInput();
        this.output = transport.getServerOutput();
    }

    public void register(String method, Function<Map<String, Object>, Object> handler) {
        handlers.put(method, handler);
    }

    public void start() {
        running = true;
        thread = Thread.ofVirtual().name("mock-server").start(this::serverLoop);
    }

    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    @SuppressWarnings("unchecked")
    private void serverLoop() {
        while (running) {
            try {
                byte[] header = input.readNBytes(4);
                if (header.length < 4) break;
                int length = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt();
                byte[] payload = input.readNBytes(length);
                if (payload.length < length) break;

                Map<String, Object> request = MessagePackCodec.decode(payload);
                String method = (String) request.get("method");
                Object id = request.get("id");
                Map<String, Object> params = (Map<String, Object>) request.getOrDefault("params", Map.of());

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("id", id);

                Function<Map<String, Object>, Object> handler = handlers.get(method);
                if (handler != null) {
                    try {
                        response.put("result", handler.apply(params));
                    } catch (Exception e) {
                        response.put("error", e.getMessage());
                    }
                } else {
                    response.put("error", "Unknown method: " + method);
                }

                byte[] responseBytes = MessagePackCodec.encode(response);
                byte[] respHeader = ByteBuffer.allocate(4)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(responseBytes.length)
                        .array();
                output.write(respHeader);
                output.write(responseBytes);
                output.flush();
            } catch (IOException e) {
                if (running) break;
            }
        }
    }
}
