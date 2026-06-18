package com.botwithus.bot.core.crypto;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Arrays;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Minimal TLS client for the BotWithUs heartbeat's binary opcode protocol, used
 * by {@link SdnEndToEndIT} to fetch a per-session SDN key envelope from a
 * locally-running heartbeat (LOCAL_TEST mode).
 *
 * <p>Protocol (all multi-byte ints big-endian):</p>
 * <ul>
 *   <li>auth (client&rarr;server): {@code [u32 size][u32 opcode=0][token \0 hwid \0 ver]}
 *       where {@code size} is the body length (excludes the opcode word).</li>
 *   <li>key reply (server&rarr;client): {@code [u32 size][u32 opcode=2][key(32)][sessionId(16)]}
 *       where {@code size} INCLUDES the opcode word.</li>
 *   <li>get_class_key (client&rarr;server): {@code [u32 size=32][u32 opcode=8][x25519Pub(32)]}.</li>
 *   <li>key-envelope reply (server&rarr;client): {@code [u32 size][u32 opcode=8][envelope(168)][bundleId ascii]}
 *       ({@code size} INCLUDES the opcode word).</li>
 * </ul>
 *
 * <p>Trusts any server certificate — acceptable only because this talks to a
 * self-signed LOCAL_TEST dev server on loopback.</p>
 */
final class DummyHeartbeatClient implements AutoCloseable {

    /** Outcome of the get_class_key exchange. */
    record Result(byte[] envelope, String bundleId) {
    }

    private static final int ENVELOPE_LEN = 168;

    private final SSLSocket socket;
    private final DataInputStream in;
    private final DataOutputStream out;

    DummyHeartbeatClient(String host, int port) throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{TRUST_ALL}, new SecureRandom());
        socket = (SSLSocket) ctx.getSocketFactory().createSocket(host, port);
        socket.startHandshake();
        in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        authenticate();
    }

    private void authenticate() throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write("sdn-test-token".getBytes(StandardCharsets.US_ASCII));
        body.write(0);
        body.write("SDN-TEST-HWID".getBytes(StandardCharsets.US_ASCII));
        body.write(0);
        body.write(2); // version byte

        byte[] b = body.toByteArray();
        out.writeInt(b.length); // size excludes the opcode on the read path
        out.writeInt(0);        // opcode 0 = legacy auth
        out.write(b);
        out.flush();

        // Consume the opcode-2 key reply (unused by the SDN path).
        int size = in.readInt();
        int opcode = in.readInt();
        if (opcode != 2) {
            throw new IOException("expected opcode 2 (key) after auth, got " + opcode);
        }
        readFully(size - 4); // key(32) + sessionId(16)
    }

    Result getClassKey(byte[] x25519Pub) throws IOException {
        if (x25519Pub.length != 32) {
            throw new IllegalArgumentException("X25519 public key must be 32 bytes, got " + x25519Pub.length);
        }
        out.writeInt(x25519Pub.length); // size excludes the opcode
        out.writeInt(8);                // opcode 8 = get_class_key
        out.write(x25519Pub);
        out.flush();

        int size = in.readInt();
        int opcode = in.readInt();
        if (opcode != 8) {
            throw new IOException("expected opcode 8 reply, got " + opcode);
        }
        byte[] payload = readFully(size - 4); // size includes the opcode word
        if (payload.length < ENVELOPE_LEN) {
            throw new IOException("opcode-8 payload too short: " + payload.length);
        }
        byte[] envelope = Arrays.copyOfRange(payload, 0, ENVELOPE_LEN);
        String bundleId = new String(payload, ENVELOPE_LEN, payload.length - ENVELOPE_LEN,
                StandardCharsets.US_ASCII);
        return new Result(envelope, bundleId);
    }

    private byte[] readFully(int n) throws IOException {
        byte[] buf = new byte[n];
        in.readFully(buf);
        return buf;
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    private static final X509TrustManager TRUST_ALL = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };
}
