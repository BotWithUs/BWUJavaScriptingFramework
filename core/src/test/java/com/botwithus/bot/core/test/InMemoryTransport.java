package com.botwithus.bot.core.test;

import com.botwithus.bot.core.msgpack.MessagePackCodec;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

/**
 * In-memory transport using piped streams for testing.
 * Implements the same 4-byte LE length-prefix framing as PipeClient.
 */
public class InMemoryTransport {

    private final PipedOutputStream clientToServer;
    private final PipedInputStream serverFromClient;
    private final PipedOutputStream serverToClient;
    private final PipedInputStream clientFromServer;

    public InMemoryTransport() throws IOException {
        clientToServer = new PipedOutputStream();
        serverFromClient = new PipedInputStream(clientToServer, 65536);
        serverToClient = new PipedOutputStream();
        clientFromServer = new PipedInputStream(serverToClient, 65536);
    }

    public OutputStream getClientOutput() { return clientToServer; }
    public InputStream getClientInput() { return clientFromServer; }
    public OutputStream getServerOutput() { return serverToClient; }
    public InputStream getServerInput() { return serverFromClient; }

    public void sendToServer(Map<String, Object> message) throws IOException {
        writeFramed(clientToServer, MessagePackCodec.encode(message));
    }

    public void sendToClient(Map<String, Object> message) throws IOException {
        writeFramed(serverToClient, MessagePackCodec.encode(message));
    }

    public Map<String, Object> readFromClient() throws IOException {
        return MessagePackCodec.decode(readFramed(serverFromClient));
    }

    public Map<String, Object> readFromServer() throws IOException {
        return MessagePackCodec.decode(readFramed(clientFromServer));
    }

    private static void writeFramed(OutputStream out, byte[] data) throws IOException {
        byte[] header = ByteBuffer.allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(data.length)
                .array();
        out.write(header);
        out.write(data);
        out.flush();
    }

    private static byte[] readFramed(InputStream in) throws IOException {
        byte[] header = in.readNBytes(4);
        if (header.length < 4) throw new IOException("Stream closed");
        int length = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt();
        byte[] payload = in.readNBytes(length);
        if (payload.length < length) throw new IOException("Incomplete message");
        return payload;
    }

    public void close() {
        try { clientToServer.close(); } catch (IOException ignored) {}
        try { serverToClient.close(); } catch (IOException ignored) {}
    }
}
