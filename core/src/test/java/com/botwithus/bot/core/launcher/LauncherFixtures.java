package com.botwithus.bot.core.launcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.msgpack.value.Value;
import org.msgpack.value.ValueFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The launcher protocol fixtures vendored under
 * {@code src/test/resources/launcher-protocol/} (see its {@code VENDOR.md}).
 */
final class LauncherFixtures {

    /** The launcher merge the fixtures were vendored from. */
    static final String SOURCE_SHA = "7fa9a446bbcf95eb00d5e27b60bbb236c86febfa";

    private final Path dir;
    private final List<Fixture> fixtures;

    private LauncherFixtures(Path dir, List<Fixture> fixtures) {
        this.dir = dir;
        this.fixtures = fixtures;
    }

    /** One manifest entry and its bytes. */
    record Fixture(String name, String file, String direction, List<String> surface, JsonObject value,
                   Set<String> binFields, byte[] bytes) {

        /** @return the manifest value as msgpack-core would build it */
        Value expectedValue() {
            return toValue(value, "", binFields);
        }

        /** @return the envelope's {@code body}, as JSON */
        JsonObject body() {
            return value.getAsJsonObject("body");
        }
    }

    static LauncherFixtures load() {
        Path dir = resourceDir();
        JsonObject manifest = JsonParser.parseString(read(dir.resolve("fixtures/manifest.json"))).getAsJsonObject();
        List<Fixture> fixtures = new ArrayList<>();
        for (JsonElement element : manifest.getAsJsonArray("fixtures")) {
            JsonObject entry = element.getAsJsonObject();
            Set<String> binFields = entry.getAsJsonArray("binFields").asList().stream()
                    .map(JsonElement::getAsString).collect(Collectors.toSet());
            String file = entry.get("file").getAsString();
            List<String> surface = entry.getAsJsonArray("surface").asList().stream()
                    .map(JsonElement::getAsString).toList();
            fixtures.add(new Fixture(entry.get("name").getAsString(), file, entry.get("direction").getAsString(),
                    surface, entry.getAsJsonObject("value"), binFields,
                    readBytes(dir.resolve("fixtures").resolve(file))));
        }
        return new LauncherFixtures(dir, List.copyOf(fixtures));
    }

    List<Fixture> all() {
        return fixtures;
    }

    Fixture named(String name) {
        return fixtures.stream().filter(f -> f.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no fixture named " + name));
    }

    /** The decoded envelope of the fixture called {@code name}. */
    Envelope envelope(String name) {
        return Envelope.decode(named(name).bytes());
    }

    Path dir() {
        return dir;
    }

    /** @return the first offset at which the two arrays differ, or -1 when equal */
    static int firstDifference(byte[] expected, byte[] actual) {
        int shared = Math.min(expected.length, actual.length);
        for (int i = 0; i < shared; i++) {
            if (expected[i] != actual[i]) {
                return i;
            }
        }
        return expected.length == actual.length ? -1 : shared;
    }

    static Value toValue(JsonElement json, String pointer, Set<String> binFields) {
        if (json.isJsonNull()) {
            return ValueFactory.newNil();
        }
        if (json.isJsonObject()) {
            var builder = ValueFactory.newMapBuilder();
            for (Map.Entry<String, JsonElement> e : json.getAsJsonObject().entrySet()) {
                builder.put(ValueFactory.newString(e.getKey()),
                        toValue(e.getValue(), pointer + "/" + e.getKey(), binFields));
            }
            return builder.build();
        }
        if (json.isJsonArray()) {
            JsonArray array = json.getAsJsonArray();
            List<Value> items = new ArrayList<>();
            for (int i = 0; i < array.size(); i++) {
                items.add(toValue(array.get(i), pointer + "/" + i, binFields));
            }
            return ValueFactory.newArray(items);
        }
        return primitive(json, pointer, binFields);
    }

    private static Value primitive(JsonElement json, String pointer, Set<String> binFields) {
        var primitive = json.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return ValueFactory.newBoolean(primitive.getAsBoolean());
        }
        if (primitive.isNumber()) {
            return ValueFactory.newInteger(primitive.getAsBigInteger());
        }
        if (binFields.contains(pointer)) {
            return ValueFactory.newBinary(HexFormat.of().parseHex(primitive.getAsString()));
        }
        return ValueFactory.newString(primitive.getAsString());
    }

    private static Path resourceDir() {
        URL marker = Objects.requireNonNull(LauncherFixtures.class.getClassLoader()
                .getResource("launcher-protocol/VENDOR.md"), "launcher-protocol/VENDOR.md is not on the test classpath");
        try {
            return Path.of(marker.toURI()).getParent();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    static String read(Path path) {
        return new String(readBytes(path), StandardCharsets.UTF_8);
    }

    static byte[] readBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
