package com.botwithus.bot.core.test;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryTransportTest {

    @Test
    void roundtripMessage() throws Exception {
        InMemoryTransport transport = new InMemoryTransport();
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("method", "test");
        msg.put("id", 1);

        transport.sendToServer(msg);
        Map<String, Object> received = transport.readFromClient();
        assertEquals("test", received.get("method"));
        assertEquals(1, ((Number) received.get("id")).intValue());

        transport.close();
    }

    @Test
    void mockServerHandlesRequest() throws Exception {
        InMemoryTransport transport = new InMemoryTransport();
        MockGameServer server = new MockGameServer(transport);
        server.register("rpc.ping", params -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("pong", true);
            return result;
        });
        server.start();

        // Send a request from "client" side
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("method", "rpc.ping");
        request.put("id", 1);
        transport.sendToServer(request);

        // Read response from "client" side
        Map<String, Object> response = transport.readFromServer();
        assertEquals(1, ((Number) response.get("id")).intValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        assertEquals(true, result.get("pong"));

        server.stop();
        transport.close();
    }
}
