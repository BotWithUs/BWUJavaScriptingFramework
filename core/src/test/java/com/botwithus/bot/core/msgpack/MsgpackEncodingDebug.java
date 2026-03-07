package com.botwithus.bot.core.msgpack;

import java.util.LinkedHashMap;
import java.util.Map;

public class MsgpackEncodingDebug {
    public static void main(String[] args) {
        // Exactly what RpcClient.doCall builds for login_to_lobby
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("method", "login_to_lobby");
        request.put("id", 1);
        // params is empty Map.of(), so not added

        byte[] bytes = MessagePackCodec.encode(request);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x ", b & 0xff));
        System.out.println("Java msgpack hex: " + sb.toString().trim());
        System.out.println("Length: " + bytes.length);
        System.out.println("Expected Python:  82 a6 6d 65 74 68 6f 64 ae 6c 6f 67 69 6e 5f 74 6f 5f 6c 6f 62 62 79 a2 69 64 01");
        System.out.println("Match: " + sb.toString().trim().equals("82 a6 6d 65 74 68 6f 64 ae 6c 6f 67 69 6e 5f 74 6f 5f 6c 6f 62 62 79 a2 69 64 01"));
    }
}
