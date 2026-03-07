package com.botwithus.bot.core.crypto;

import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.rpc.RpcException;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Base64;
import java.util.Map;

/**
 * Performs ephemeral ECDH key exchange with the Agent, then hands the
 * encrypted script bundle to the custom JVM's native SdnClassLoader.
 *
 * <p>Protocol:
 * <ol>
 *   <li>JVM generates an ephemeral ECDH P-256 keypair at startup (native side)</li>
 *   <li>Framework retrieves the client public key via {@code SdnClassLoader.pubkey0()}</li>
 *   <li>Framework sends the public key to the Agent via {@code get_script_bundle} RPC</li>
 *   <li>Agent generates its own ephemeral keypair, derives AES key via ECDH, encrypts the JAR</li>
 *   <li>Agent returns its public key + encrypted JAR</li>
 *   <li>Framework passes both to {@code SdnClassLoader(encryptedJar, serverPubKey, parentLoader)}</li>
 *   <li>JVM native code derives the same AES key via ECDH, decrypts, and defines classes</li>
 * </ol>
 *
 * <p>No static keys exist in any binary. Each session uses a fresh keypair.
 */
public final class SdnLoader {

    private static final String SDN_CLASS = "jdk.internal.sdn.SdnClassLoader";

    private final RpcClient rpc;

    // Lazily resolved handles into jdk.internal.sdn.SdnClassLoader
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
     * Calls {@code SdnClassLoader.pubkey0()} to retrieve the JVM's
     * ephemeral ECDH public key (BCRYPT_ECCPUBLIC_BLOB).
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
     * Constructs a new {@code SdnClassLoader(byte[] encryptedJar, byte[] serverPubKey, ClassLoader parent)}.
     * The native constructor derives the AES key via ECDH and decrypts/defines classes.
     */
    private static ClassLoader createSdnClassLoader(byte[] encryptedJar, byte[] serverPubKey, ClassLoader parent) {
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
     * Calls {@code SdnClassLoader.lockdown0()} to enforce code integrity
     * (MicrosoftSignedOnly DLL policy). Must be called after all scripts
     * and native libraries are loaded — no more unsigned DLLs can load after this.
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
