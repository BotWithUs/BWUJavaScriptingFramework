package com.botwithus.bot.core.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ServiceLoader;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.botwithus.bot.api.BotScript;

/**
 * End-to-end test of the SDN encrypted-script path against the locally-running
 * dummy rig: the JVM's X25519 public key is sent to the heartbeat (LOCAL_TEST),
 * which returns a signed key envelope wrapping a fresh per-session content key;
 * the encrypted bundle is fetched over HTTP; the custom JVM's
 * {@code jdk.internal.sdn.SdnClassLoader} verifies + unwraps the key, decrypts
 * each class in native memory, and the dummy {@code BotScript} loads and runs.
 *
 * <p>Skipped automatically (JUnit assumptions) when not on the custom JDK or
 * when the dummy heartbeat is not reachable, so it is harmless in normal CI.
 * To run it:</p>
 * <ol>
 *   <li>build the custom JDK and point the Gradle toolchain at it;</li>
 *   <li>{@code ./gradlew :sdn-test-script:jar} and set
 *       {@code SDN_PLAINTEXT_BUNDLE} to the built jar;</li>
 *   <li>run the heartbeat with {@code LOCAL_TEST=true} and export its logged
 *       Ed25519 pubkey via {@code BWU_SDN_SERVER_PUBKEY} for the test JVM;</li>
 *   <li>{@code ./gradlew :core:test --tests *SdnEndToEndIT}.</li>
 * </ol>
 */
@Tag("sdn-it")
class SdnEndToEndIT {

    private static final String HB_HOST = System.getProperty("sdn.hb.host", "127.0.0.1");
    private static final int HB_PORT = Integer.getInteger("sdn.hb.port", 9124);
    private static final String BUNDLE_URL =
            System.getProperty("sdn.bundle.url", "http://127.0.0.1:9125/script-bundle");

    @Test
    void loadsDummyScriptThroughSdnClassLoader() throws Exception {
        assumeTrue(sdnClassLoaderPresent(),
                "requires the custom JDK with jdk.internal.sdn.SdnClassLoader");
        assumeTrue(reachable(HB_HOST, HB_PORT),
                "dummy heartbeat not reachable on " + HB_HOST + ":" + HB_PORT);

        byte[] pubkey = SdnLoader.clientPublicKey();
        assertEquals(32, pubkey.length, "X25519 public key should be 32 bytes");

        DummyHeartbeatClient.Result result;
        try (DummyHeartbeatClient client = new DummyHeartbeatClient(HB_HOST, HB_PORT)) {
            result = client.getClassKey(pubkey);
        }
        assertEquals(168, result.envelope().length, "signed key envelope should be 168 bytes");

        byte[] encryptedJar = fetchBundle(result.bundleId());
        assertTrue(encryptedJar.length > 0, "encrypted bundle should be non-empty");

        ClassLoader loader = SdnLoader.defineLoader(encryptedJar, result.envelope(),
                getClass().getClassLoader());

        BotScript script = null;
        for (BotScript candidate : ServiceLoader.load(BotScript.class, loader)) {
            if (candidate.getClass().getName().endsWith("SdnDummyScript")) {
                script = candidate;
                break;
            }
        }
        assertNotNull(script, "dummy BotScript should be discovered via ServiceLoader");
        assertSame(loader, script.getClass().getClassLoader(),
                "the decrypted class must be defined by the SdnClassLoader");

        // The decrypted class actually runs.
        script.onStart(null);
        assertEquals(-1, script.onLoop(), "dummy onLoop returns -1");
        script.onStop();
    }

    @Test
    void rejectsTamperedKeyEnvelope() throws Exception {
        assumeTrue(sdnClassLoaderPresent(),
                "requires the custom JDK with jdk.internal.sdn.SdnClassLoader");
        assumeTrue(reachable(HB_HOST, HB_PORT),
                "dummy heartbeat not reachable on " + HB_HOST + ":" + HB_PORT);

        byte[] pubkey = SdnLoader.clientPublicKey();
        DummyHeartbeatClient.Result result;
        try (DummyHeartbeatClient client = new DummyHeartbeatClient(HB_HOST, HB_PORT)) {
            result = client.getClassKey(pubkey);
        }
        byte[] encryptedJar = fetchBundle(result.bundleId());

        // Flip a bit in the Ed25519 signature (the last byte of the envelope).
        byte[] tampered = result.envelope().clone();
        tampered[tampered.length - 1] ^= 0x01;

        // init0 must reject the envelope; createSdnClassLoader surfaces it.
        assertThrows(RuntimeException.class, () ->
                SdnLoader.defineLoader(encryptedJar, tampered, getClass().getClassLoader()));
    }

    private static byte[] fetchBundle(String bundleId) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<byte[]> resp = http.send(
                HttpRequest.newBuilder(URI.create(BUNDLE_URL + "?id=" + bundleId)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, resp.statusCode(), "bundle fetch HTTP status");
        return resp.body();
    }

    private static boolean sdnClassLoaderPresent() {
        try {
            Class.forName("jdk.internal.sdn.SdnClassLoader");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean reachable(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
