package com.botwithus.bot.core.crypto;

import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.rpc.RpcException;

import java.util.Map;

/**
 * Fetches the RSA-encrypted AES key from the DLL via pipe RPC.
 *
 * <p>The returned 256-byte ciphertext is handed off to the custom JVM's
 * native layer for RSA decryption and jar loading — no cryptographic
 * operations happen in Java.
 */
public final class SdnLoader {

    private final RpcClient rpc;

    public SdnLoader(RpcClient rpc) {
        this.rpc = rpc;
    }

    /**
     * Calls {@code get_encryption_key} on the DLL pipe server.
     *
     * @return 256-byte RSA-OAEP ciphertext containing the AES-256 key
     */
    public byte[] fetchEncryptedKey() {
        Map<String, Object> result = rpc.callSync("get_encryption_key", Map.of());
        Object key = result.get("encrypted_key");
        if (key instanceof byte[] bytes) {
            return bytes;
        }
        throw new RpcException("Expected binary encrypted_key, got: "
                + (key == null ? "null" : key.getClass().getSimpleName()));
    }
}
