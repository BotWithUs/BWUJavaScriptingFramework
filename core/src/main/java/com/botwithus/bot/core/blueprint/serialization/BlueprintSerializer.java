package com.botwithus.bot.core.blueprint.serialization;

import com.botwithus.bot.api.blueprint.BlueprintGraph;
import com.botwithus.bot.api.blueprint.BlueprintMetadata;
import com.botwithus.bot.api.blueprint.Link;
import com.botwithus.bot.api.blueprint.NodeInstance;
import com.botwithus.bot.api.blueprint.PinType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-rolled JSON serialization for BlueprintGraph objects.
 * No external JSON library is used — only StringBuilder for writing
 * and a recursive descent parser for reading.
 */
public final class BlueprintSerializer {

    private static final Logger log = LoggerFactory.getLogger(BlueprintSerializer.class);
    private static final String FILE_SUFFIX = ".blueprint.json";

    private BlueprintSerializer() {
        // utility class
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Serializes a BlueprintGraph to a formatted JSON string.
     */
    public static String serialize(BlueprintGraph graph) {
        StringBuilder sb = new StringBuilder(4096);
        writeGraph(sb, graph, 0);
        sb.append('\n');
        return sb.toString();
    }

    /**
     * Deserializes a JSON string into a BlueprintGraph.
     *
     * @throws IllegalArgumentException if the JSON is malformed or the format version is unsupported
     */
    public static BlueprintGraph deserialize(String json) {
        JsonParser parser = new JsonParser(json);
        Object root = parser.parseValue();
        if (!(root instanceof Map)) {
            throw new IllegalArgumentException("Expected JSON object at root");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) root;

        int version = toInt(map.get("version"));
        if (!BlueprintFormat.isSupported(version)) {
            throw new IllegalArgumentException("Unsupported blueprint format version: " + version);
        }
        map = BlueprintFormat.migrate(map, version);

        return mapToGraph(map);
    }

    /**
     * Serializes a BlueprintGraph and writes it to a file.
     */
    public static void saveToFile(BlueprintGraph graph, Path path) throws IOException {
        String json = serialize(graph);
        Files.createDirectories(path.getParent());
        Files.writeString(path, json, StandardCharsets.UTF_8);
    }

    /**
     * Reads a file and deserializes it into a BlueprintGraph.
     */
    public static BlueprintGraph loadFromFile(Path path) throws IOException {
        String json = Files.readString(path, StandardCharsets.UTF_8);
        return deserialize(json);
    }

    /**
     * Loads all {@code *.blueprint.json} files from a directory.
     *
     * @param dir the directory to scan
     * @return list of loaded graphs (files that fail to parse are skipped with a warning on stderr)
     */
    public static List<BlueprintGraph> loadAllFromDirectory(Path dir) throws IOException {
        List<BlueprintGraph> graphs = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return graphs;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*" + FILE_SUFFIX)) {
            for (Path file : stream) {
                try {
                    graphs.add(loadFromFile(file));
                } catch (Exception e) {
                    log.error("Failed to load blueprint {}: {}", file, e.getMessage());
                }
            }
        }
        return graphs;
    }

    // =========================================================================
    // JSON Writing
    // =========================================================================

    private static void writeGraph(StringBuilder sb, BlueprintGraph graph, int indent) {
        sb.append("{\n");
        int inner = indent + 1;

        // version
        writeKey(sb, "version", inner);
        sb.append(BlueprintFormat.CURRENT_VERSION);
        sb.append(",\n");

        // metadata
        writeKey(sb, "metadata", inner);
        writeMetadata(sb, graph.getMetadata(), inner);
        sb.append(",\n");

        // variables
        writeKey(sb, "variables", inner);
        writeVariables(sb, graph.getVariables(), inner);
        sb.append(",\n");

        // nodes
        writeKey(sb, "nodes", inner);
        writeNodes(sb, graph.getNodes(), inner);
        sb.append(",\n");

        // links
        writeKey(sb, "links", inner);
        writeLinks(sb, graph.getLinks(), inner);
        sb.append(",\n");

        // nextId
        writeKey(sb, "nextId", inner);
        sb.append(graph.getNextId());
        sb.append('\n');

        writeIndent(sb, indent);
        sb.append('}');
    }

    private static void writeMetadata(StringBuilder sb, BlueprintMetadata meta, int indent) {
        sb.append("{\n");
        int inner = indent + 1;

        writeKey(sb, "name", inner);
        writeJsonString(sb, meta.name());
        sb.append(",\n");

        writeKey(sb, "version", inner);
        writeJsonString(sb, meta.version());
        sb.append(",\n");

        writeKey(sb, "author", inner);
        writeJsonString(sb, meta.author());
        sb.append(",\n");

        writeKey(sb, "description", inner);
        writeJsonString(sb, meta.description());
        sb.append('\n');

        writeIndent(sb, indent);
        sb.append('}');
    }

    private static void writeVariables(StringBuilder sb, Map<String, PinType> variables, int indent) {
        if (variables.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append("[\n");
        int inner = indent + 1;
        int i = 0;
        for (Map.Entry<String, PinType> entry : variables.entrySet()) {
            writeIndent(sb, inner);
            sb.append("{\n");
            int objInner = inner + 1;

            writeKey(sb, "name", objInner);
            writeJsonString(sb, entry.getKey());
            sb.append(",\n");

            writeKey(sb, "type", objInner);
            writeJsonString(sb, entry.getValue().name());
            sb.append(",\n");

            writeKey(sb, "defaultValue", objInner);
            writeDefaultForType(sb, entry.getValue());
            sb.append('\n');

            writeIndent(sb, inner);
            sb.append('}');
            if (++i < variables.size()) {
                sb.append(',');
            }
            sb.append('\n');
        }
        writeIndent(sb, indent);
        sb.append(']');
    }

    private static void writeDefaultForType(StringBuilder sb, PinType type) {
        switch (type) {
            case BOOLEAN -> sb.append("false");
            case INT -> sb.append('0');
            case FLOAT -> sb.append("0.0");
            case STRING -> sb.append("\"\"");
            default -> sb.append("null");
        }
    }

    private static void writeNodes(StringBuilder sb, List<NodeInstance> nodes, int indent) {
        if (nodes.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append("[\n");
        int inner = indent + 1;
        for (int i = 0; i < nodes.size(); i++) {
            NodeInstance node = nodes.get(i);
            writeIndent(sb, inner);
            sb.append("{\n");
            int objInner = inner + 1;

            writeKey(sb, "id", objInner);
            sb.append(node.getId());
            sb.append(",\n");

            writeKey(sb, "typeId", objInner);
            writeJsonString(sb, node.getTypeId());
            sb.append(",\n");

            writeKey(sb, "x", objInner);
            writeFloat(sb, node.getX());
            sb.append(",\n");

            writeKey(sb, "y", objInner);
            writeFloat(sb, node.getY());
            sb.append(",\n");

            writeKey(sb, "properties", objInner);
            writePropertyMap(sb, node.getPropertyValues(), objInner);
            sb.append('\n');

            writeIndent(sb, inner);
            sb.append('}');
            if (i < nodes.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        writeIndent(sb, indent);
        sb.append(']');
    }

    private static void writeLinks(StringBuilder sb, List<Link> links, int indent) {
        if (links.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append("[\n");
        int inner = indent + 1;
        for (int i = 0; i < links.size(); i++) {
            Link link = links.get(i);
            writeIndent(sb, inner);
            sb.append("{\n");
            int objInner = inner + 1;

            writeKey(sb, "id", objInner);
            sb.append(link.id());
            sb.append(",\n");

            writeKey(sb, "sourcePinId", objInner);
            sb.append(link.sourcePinId());
            sb.append(",\n");

            writeKey(sb, "targetPinId", objInner);
            sb.append(link.targetPinId());
            sb.append('\n');

            writeIndent(sb, inner);
            sb.append('}');
            if (i < links.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        writeIndent(sb, indent);
        sb.append(']');
    }

    private static void writePropertyMap(StringBuilder sb, Map<String, Object> props, int indent) {
        if (props.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append("{\n");
        int inner = indent + 1;
        int i = 0;
        for (Map.Entry<String, Object> entry : props.entrySet()) {
            writeKey(sb, entry.getKey(), inner);
            writeValue(sb, entry.getValue(), inner);
            if (++i < props.size()) {
                sb.append(',');
            }
            sb.append('\n');
        }
        writeIndent(sb, indent);
        sb.append('}');
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object value, int indent) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Boolean b) {
            sb.append(b);
        } else if (value instanceof Integer || value instanceof Long) {
            sb.append(value);
        } else if (value instanceof Float f) {
            writeFloat(sb, f);
        } else if (value instanceof Double d) {
            writeDouble(sb, d);
        } else if (value instanceof Number n) {
            sb.append(n);
        } else if (value instanceof String s) {
            writeJsonString(sb, s);
        } else if (value instanceof Map) {
            writePropertyMap(sb, (Map<String, Object>) value, indent);
        } else if (value instanceof List<?> list) {
            writeArray(sb, list, indent);
        } else {
            // Fallback: treat as string
            writeJsonString(sb, value.toString());
        }
    }

    private static void writeArray(StringBuilder sb, List<?> list, int indent) {
        if (list.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append("[\n");
        int inner = indent + 1;
        for (int i = 0; i < list.size(); i++) {
            writeIndent(sb, inner);
            writeValue(sb, list.get(i), inner);
            if (i < list.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        writeIndent(sb, indent);
        sb.append(']');
    }

    private static void writeFloat(StringBuilder sb, float f) {
        if (f == (long) f) {
            sb.append(String.format("%.1f", f));
        } else {
            sb.append(f);
        }
    }

    private static void writeDouble(StringBuilder sb, double d) {
        if (d == (long) d) {
            sb.append(String.format("%.1f", d));
        } else {
            sb.append(d);
        }
    }

    private static void writeJsonString(StringBuilder sb, String s) {
        if (s == null) {
            sb.append("null");
            return;
        }
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static void writeKey(StringBuilder sb, String key, int indent) {
        writeIndent(sb, indent);
        writeJsonString(sb, key);
        sb.append(": ");
    }

    private static void writeIndent(StringBuilder sb, int level) {
        for (int i = 0; i < level; i++) {
            sb.append("  ");
        }
    }

    // =========================================================================
    // Deserialization: Map -> BlueprintGraph
    // =========================================================================

    @SuppressWarnings("unchecked")
    private static BlueprintGraph mapToGraph(Map<String, Object> map) {
        // metadata
        BlueprintMetadata metadata;
        Object metaObj = map.get("metadata");
        if (metaObj instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) metaObj;
            metadata = new BlueprintMetadata(
                    toStringOrDefault(m.get("name"), "Untitled"),
                    toStringOrDefault(m.get("version"), "1.0"),
                    toStringOrDefault(m.get("author"), ""),
                    toStringOrDefault(m.get("description"), "")
            );
        } else {
            metadata = new BlueprintMetadata("Untitled", "1.0", "", "");
        }

        BlueprintGraph graph = new BlueprintGraph(metadata);

        // variables
        Object varsObj = map.get("variables");
        if (varsObj instanceof List<?> varsList) {
            for (Object item : varsList) {
                if (item instanceof Map) {
                    Map<String, Object> varMap = (Map<String, Object>) item;
                    String name = toStringOrDefault(varMap.get("name"), null);
                    String typeName = toStringOrDefault(varMap.get("type"), null);
                    if (name != null && typeName != null) {
                        try {
                            PinType pinType = PinType.valueOf(typeName);
                            graph.getVariables().put(name, pinType);
                        } catch (IllegalArgumentException e) {
                            log.warn("Unknown PinType '{}' for variable '{}', skipping", typeName, name);
                        }
                    }
                }
            }
        }

        // nodes
        Object nodesObj = map.get("nodes");
        if (nodesObj instanceof List<?> nodesList) {
            for (Object item : nodesList) {
                if (item instanceof Map) {
                    Map<String, Object> nodeMap = (Map<String, Object>) item;
                    long id = toLong(nodeMap.get("id"));
                    String typeId = toStringOrDefault(nodeMap.get("typeId"), "");
                    float x = toFloat(nodeMap.get("x"));
                    float y = toFloat(nodeMap.get("y"));

                    NodeInstance node = new NodeInstance(id, typeId, x, y);

                    Object propsObj = nodeMap.get("properties");
                    if (propsObj instanceof Map) {
                        Map<String, Object> props = (Map<String, Object>) propsObj;
                        for (Map.Entry<String, Object> entry : props.entrySet()) {
                            node.setProperty(entry.getKey(), entry.getValue());
                        }
                    }
                    graph.getNodes().add(node);
                }
            }
        }

        // links
        Object linksObj = map.get("links");
        if (linksObj instanceof List<?> linksList) {
            for (Object item : linksList) {
                if (item instanceof Map) {
                    Map<String, Object> linkMap = (Map<String, Object>) item;
                    long id = toLong(linkMap.get("id"));
                    long sourcePinId = toLong(linkMap.get("sourcePinId"));
                    long targetPinId = toLong(linkMap.get("targetPinId"));
                    graph.getLinks().add(new Link(id, sourcePinId, targetPinId));
                }
            }
        }

        // nextId
        Object nextIdObj = map.get("nextId");
        if (nextIdObj != null) {
            graph.setNextId(toLong(nextIdObj));
        }

        return graph;
    }

    // =========================================================================
    // Type conversion helpers
    // =========================================================================

    private static int toInt(Object obj) {
        if (obj instanceof Number n) return n.intValue();
        if (obj instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }

    private static long toLong(Object obj) {
        if (obj instanceof Number n) return n.longValue();
        if (obj instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }

    private static float toFloat(Object obj) {
        if (obj instanceof Number n) return n.floatValue();
        if (obj instanceof String s) {
            try { return Float.parseFloat(s); } catch (NumberFormatException e) { return 0f; }
        }
        return 0f;
    }

    private static String toStringOrDefault(Object obj, String defaultValue) {
        if (obj instanceof String s) return s;
        if (obj != null) return obj.toString();
        return defaultValue;
    }

    // =========================================================================
    // JSON Parser (recursive descent)
    // =========================================================================

    /**
     * A simple recursive descent JSON parser.
     * Parses a JSON string into Java objects:
     * - JSON objects become {@code LinkedHashMap<String, Object>} (preserving insertion order)
     * - JSON arrays become {@code ArrayList<Object>}
     * - JSON strings become {@code String}
     * - JSON numbers become {@code Long} (no decimal) or {@code Double} (with decimal/exponent)
     * - JSON booleans become {@code Boolean}
     * - JSON null becomes {@code null}
     */
    static final class JsonParser {

        private final String input;
        private int pos;

        JsonParser(String input) {
            this.input = input;
            this.pos = 0;
        }

        Object parseValue() {
            skipWhitespace();
            if (pos >= input.length()) {
                throw error("Unexpected end of input");
            }
            char c = peek();
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't', 'f' -> parseBool();
                case 'n' -> parseNull();
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield parseNumber();
                    }
                    throw error("Unexpected character: '" + c + "'");
                }
            };
        }

        Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (pos < input.length() && peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (pos >= input.length()) {
                    throw error("Unterminated object");
                }
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                if (pos >= input.length()) {
                    throw error("Unterminated object");
                }
                char c = input.charAt(pos);
                if (c == '}') {
                    pos++;
                    return map;
                }
                if (c == ',') {
                    pos++;
                } else {
                    throw error("Expected ',' or '}' in object, got '" + c + "'");
                }
            }
        }

        List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (pos < input.length() && peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                Object value = parseValue();
                list.add(value);
                skipWhitespace();
                if (pos >= input.length()) {
                    throw error("Unterminated array");
                }
                char c = input.charAt(pos);
                if (c == ']') {
                    pos++;
                    return list;
                }
                if (c == ',') {
                    pos++;
                } else {
                    throw error("Expected ',' or ']' in array, got '" + c + "'");
                }
            }
        }

        String parseString() {
            skipWhitespace();
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < input.length()) {
                char c = input.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= input.length()) {
                        throw error("Unterminated escape sequence");
                    }
                    char esc = input.charAt(pos++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (pos + 4 > input.length()) {
                                throw error("Incomplete unicode escape");
                            }
                            String hex = input.substring(pos, pos + 4);
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException e) {
                                throw error("Invalid unicode escape: \\u" + hex);
                            }
                            pos += 4;
                        }
                        default -> throw error("Unknown escape character: \\" + esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw error("Unterminated string");
        }

        Number parseNumber() {
            int start = pos;
            boolean isFloat = false;

            // optional minus
            if (pos < input.length() && input.charAt(pos) == '-') {
                pos++;
            }

            // integer part
            if (pos < input.length() && input.charAt(pos) == '0') {
                pos++;
            } else {
                if (pos >= input.length() || input.charAt(pos) < '1' || input.charAt(pos) > '9') {
                    throw error("Invalid number");
                }
                while (pos < input.length() && input.charAt(pos) >= '0' && input.charAt(pos) <= '9') {
                    pos++;
                }
            }

            // fractional part
            if (pos < input.length() && input.charAt(pos) == '.') {
                isFloat = true;
                pos++;
                if (pos >= input.length() || input.charAt(pos) < '0' || input.charAt(pos) > '9') {
                    throw error("Invalid number: expected digit after decimal point");
                }
                while (pos < input.length() && input.charAt(pos) >= '0' && input.charAt(pos) <= '9') {
                    pos++;
                }
            }

            // exponent part
            if (pos < input.length() && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
                isFloat = true;
                pos++;
                if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) {
                    pos++;
                }
                if (pos >= input.length() || input.charAt(pos) < '0' || input.charAt(pos) > '9') {
                    throw error("Invalid number: expected digit in exponent");
                }
                while (pos < input.length() && input.charAt(pos) >= '0' && input.charAt(pos) <= '9') {
                    pos++;
                }
            }

            String numStr = input.substring(start, pos);
            if (isFloat) {
                return Double.parseDouble(numStr);
            } else {
                try {
                    return Long.parseLong(numStr);
                } catch (NumberFormatException e) {
                    // Overflow: fall back to double
                    return Double.parseDouble(numStr);
                }
            }
        }

        Boolean parseBool() {
            if (input.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (input.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw error("Expected 'true' or 'false'");
        }

        Object parseNull() {
            if (input.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw error("Expected 'null'");
        }

        void skipWhitespace() {
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        char peek() {
            return input.charAt(pos);
        }

        void expect(char expected) {
            skipWhitespace();
            if (pos >= input.length()) {
                throw error("Expected '" + expected + "' but reached end of input");
            }
            char actual = input.charAt(pos);
            if (actual != expected) {
                throw error("Expected '" + expected + "' but got '" + actual + "'");
            }
            pos++;
        }

        private IllegalArgumentException error(String message) {
            // Provide context around the error position
            int contextStart = Math.max(0, pos - 20);
            int contextEnd = Math.min(input.length(), pos + 20);
            String context = input.substring(contextStart, contextEnd);
            int pointer = pos - contextStart;
            return new IllegalArgumentException(
                    message + " at position " + pos + "\n  ..." + context + "...\n  "
                            + " ".repeat(pointer + 3) + "^"
            );
        }
    }
}
