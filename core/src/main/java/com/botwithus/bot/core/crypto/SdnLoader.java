package com.botwithus.bot.core.crypto;

import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.rpc.RpcException;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Base64;
import java.util.Map;

/**
 * Fetches an encrypted script bundle from the Agent and hands it to the custom
 * JVM's native {@code SdnClassLoader}, which decrypts and defines the classes.
 *
 * <p>This class is a courier, not a verifier: it moves opaque byte arrays
 * between the {@code get_script_bundle} RPC and the native loader. All key
 * agreement, authentication and decryption happen on the native side, and
 * plaintext bytecode never becomes a managed {@code byte[]}. A rejected bundle
 * surfaces here as a thrown exception and never as a usable loader.
 *
 * <p>Session keys are ephemeral; no static key material is held by this class.
 */
public final class SdnLoader {

    private static final String SDN_CLASS = "jdk.internal.sdn.SdnClassLoader";

    private final RpcClient rpc;

    // rule-exception: §Banned 5 (mutable static). Lazily resolved handles into
    // jdk.internal.sdn.SdnClassLoader — the target class is JVM-injected and
    // process-global, so the cached lookups are process-global by nature.
    // See JBotWithUsV2/CLAUDE.md → "Java rules exceptions".
    private static volatile MethodHandle pubkey0Handle;
    private static volatile MethodHandle lockdown0Handle;
    private static Class<?> sdnClass;

    public SdnLoader(RpcClient rpc) {
        this.rpc = rpc;
    }

    /**
     * Executes the full ECDH key exchange and returns a ClassLoader that
     * has the decrypted script classes defined in it.
     *
     * @param parent the parent ClassLoader for the SdnClassLoader
     * @return a ClassLoader containing the decrypted script classes
     */
    public ClassLoader loadScriptBundle(ClassLoader parent) {
        byte[] clientPubKey = getClientPublicKey();
        String pubKeyB64 = Base64.getEncoder().encodeToString(clientPubKey);

        Map<String, Object> response = rpc.callSync("get_script_bundle",
                Map.of("pubkey", pubKeyB64));

        Object serverPubObj = response.get("server_pubkey");
        Object encryptedJarObj = response.get("encrypted_jar");

        if (serverPubObj == null || encryptedJarObj == null) {
            throw new RpcException("Incomplete get_script_bundle response: "
                    + "server_pubkey=" + (serverPubObj != null) + ", encrypted_jar=" + (encryptedJarObj != null));
        }

        byte[] serverPubKey = decodeBytes(serverPubObj, "server_pubkey");
        byte[] encryptedJar = decodeBytes(encryptedJarObj, "encrypted_jar");

        return createSdnClassLoader(encryptedJar, serverPubKey, parent);
    }

    /**
     * Calls {@code SdnClassLoader.pubkey0()} to retrieve the JVM's ephemeral
     * public key. The matching private key never leaves native memory.
     */
    private static byte[] getClientPublicKey() {
        try {
            MethodHandle mh = getPubkey0Handle();
            return (byte[]) mh.invoke();
        } catch (Throwable t) {
            throw new RpcException("Failed to retrieve client ECDH public key", t);
        }
    }

    /**
     * Constructs the native loader over the supplied bundle and key material.
     * The constructor authenticates the input and defines the decrypted classes,
     * or throws — it never returns a loader for a bundle it could not verify.
     */
    private static ClassLoader createSdnClassLoader(byte[] encryptedJar, byte[] serverPubKey, ClassLoader parent) {
        // rule-exception: §Banned 1 (reflection). The target ctor lives in a
        // JVM-injected class that has no build-time symbol to import. See
        // JBotWithUsV2/CLAUDE.md → "Java rules exceptions".
        try {
            Class<?> clazz = getSdnClass();
            var ctor = clazz.getDeclaredConstructor(byte[].class, byte[].class, ClassLoader.class);
            ctor.setAccessible(true);
            return (ClassLoader) ctor.newInstance(encryptedJar, serverPubKey, parent);
        } catch (ReflectiveOperationException e) {
            throw new RpcException("Failed to construct SdnClassLoader", e);
        }
    }

    /**
     * Returns the JVM's ephemeral SDN client public key, for callers that drive
     * the key exchange themselves rather than via the Agent's
     * {@code get_script_bundle} RPC. The matching private key never leaves the
     * JVM's native memory.
     */
    public static byte[] clientPublicKey() {
        return getClientPublicKey();
    }

    /**
     * Constructs the native loader directly from an already-fetched bundle and
     * key envelope, bypassing the Agent RPC. Used by the direct
     * key-distribution path; the native side verifies the envelope and decrypts
     * the bundle, or throws.
     *
     * @param encryptedJar the encrypted script bundle
     * @param envelope     the server-signed key envelope
     * @param parent       the parent loader resolving the script API/runtime types
     */
    public static ClassLoader defineLoader(byte[] encryptedJar, byte[] envelope, ClassLoader parent) {
        return createSdnClassLoader(encryptedJar, envelope, parent);
    }

    /**
     * True when this JVM provides the SDN class loader — i.e. the custom
     * runtime is in use, so {@link #lockdown()} is meaningful and its failure
     * is a security failure. False on a stock JDK, where there is no lockdown
     * to arm and no SDN bundle in play.
     */
    public static boolean isAvailable() {
        try {
            getSdnClass();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Calls {@code SdnClassLoader.lockdown0()} to enforce process code
     * integrity. Must be called after all scripts and native libraries are
     * loaded; the policy it arms cannot be relaxed for the process lifetime.
     */
    public static void lockdown() {
        try {
            MethodHandle mh = getLockdown0Handle();
            mh.invoke();
        } catch (Throwable t) {
            throw new RpcException("Failed to call lockdown0()", t);
        }
    }

    private static byte[] decodeBytes(Object value, String fieldName) {
        if (value instanceof byte[] bytes) {
            return bytes;
        }
        if (value instanceof String s) {
            return Base64.getDecoder().decode(s);
        }
        throw new RpcException("Expected binary or base64 for " + fieldName
                + ", got: " + value.getClass().getSimpleName());
    }

    private static Class<?> getSdnClass() {
        // rule-exception: §Banned 1 (Class.forName for dispatch). The class
        // is JVM-injected — name lookup is the only available handle. See
        // JBotWithUsV2/CLAUDE.md → "Java rules exceptions".
        if (sdnClass == null) {
            try {
                sdnClass = Class.forName(SDN_CLASS);
            } catch (ClassNotFoundException e) {
                throw new RpcException("SdnClassLoader not found — requires custom JDK", e);
            }
        }
        return sdnClass;
    }

    private static MethodHandle getPubkey0Handle() {
        // rule-exception: §Banned 1 (MethodHandles.privateLookupIn). Same reason
        // as getSdnClass. See JBotWithUsV2/CLAUDE.md → "Java rules exceptions".
        if (pubkey0Handle == null) {
            try {
                var lookup = MethodHandles.privateLookupIn(getSdnClass(), MethodHandles.lookup());
                pubkey0Handle = lookup.findStatic(getSdnClass(), "pubkey0",
                        MethodType.methodType(byte[].class));
            } catch (ReflectiveOperationException e) {
                throw new RpcException("Failed to resolve pubkey0() method", e);
            }
        }
        return pubkey0Handle;
    }

    private static MethodHandle getLockdown0Handle() {
        // rule-exception: §Banned 1 (MethodHandles.privateLookupIn). Same reason
        // as getSdnClass. See JBotWithUsV2/CLAUDE.md → "Java rules exceptions".
        if (lockdown0Handle == null) {
            try {
                var lookup = MethodHandles.privateLookupIn(getSdnClass(), MethodHandles.lookup());
                lockdown0Handle = lookup.findStatic(getSdnClass(), "lockdown0",
                        MethodType.methodType(void.class));
            } catch (ReflectiveOperationException e) {
                throw new RpcException("Failed to resolve lockdown0() method", e);
            }
        }
        return lockdown0Handle;
    }
}
