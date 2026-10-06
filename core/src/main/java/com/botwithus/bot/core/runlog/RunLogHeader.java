package com.botwithus.bot.core.runlog;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The block every run log opens with (spec §1.1). One component per key, so a
 * key cannot be left out without the compiler noticing; a value the host could
 * not obtain is {@link #UNKNOWN}, never omitted and never guessed.
 */
public record RunLogHeader(
        String runId,
        String hostVersion,
        int protocolVersion,
        String agentBuild,
        String gameRevision,
        String scriptName,
        String scriptVersion,
        String scriptAuthor,
        String scriptSource,
        String scriptSha256,
        String os,
        String runtime,
        Instant startedAt,
        int slot) {

    /** The literal the spec uses for a value the host cannot obtain. */
    public static final String UNKNOWN = "unknown";
    /** What this host writes for {@code host}. */
    public static final String HOST = "java";
    public static final String SOURCE_SDN = "sdn";
    public static final String SOURCE_LOCAL = "local";
    /** First line of the file; names the format. */
    public static final String MAGIC = "# bwu-run-log v1";
    /** Line that closes the header. */
    public static final String END = "---";
    /** Header keys in the order the spec lists them. */
    public static final List<String> KEYS = List.of(
            "run_id", "host", "host_version", "protocol_version", "agent_build",
            "game_revision", "script_name", "script_version", "script_author",
            "script_source", "script_sha256", "os", "runtime", "started_at", "slot");
    /** Keys whose values R5 must leave alone (spec §3 exceptions). */
    public static final Set<String> HASH_KEYS = Set.of("run_id", "script_sha256", "agent_build");

    public RunLogHeader {
        runId = orUnknown(runId);
        hostVersion = orUnknown(hostVersion);
        agentBuild = orUnknown(agentBuild);
        gameRevision = orUnknown(gameRevision);
        scriptName = orUnknown(scriptName);
        scriptVersion = orUnknown(scriptVersion);
        scriptAuthor = orUnknown(scriptAuthor);
        scriptSource = orUnknown(scriptSource);
        scriptSha256 = orUnknown(scriptSha256);
        os = orUnknown(os);
        runtime = orUnknown(runtime);
        if (startedAt == null) {
            throw new IllegalArgumentException("startedAt");
        }
    }

    /** Header values keyed and ordered as {@link #KEYS}, before redaction. */
    public List<Field> fields() {
        List<String> values = List.of(runId, HOST, hostVersion, Integer.toString(protocolVersion),
                agentBuild, gameRevision, scriptName, scriptVersion, scriptAuthor, scriptSource,
                scriptSha256, os, runtime, RunLogClock.format(startedAt), Integer.toString(slot));
        List<Field> fields = new ArrayList<>(KEYS.size());
        for (int i = 0; i < KEYS.size(); i++) {
            fields.add(new Field(KEYS.get(i), values.get(i)));
        }
        return fields;
    }

    /** The header as file lines, each value through {@code redactor}; {@link #MAGIC} to {@link #END}. */
    public List<String> render(Redactor redactor) {
        List<String> lines = new ArrayList<>();
        lines.add(MAGIC);
        for (Field field : fields()) {
            String value = HASH_KEYS.contains(field.key())
                    ? redactor.redactKeepingHashes(field.value())
                    : redactor.redact(field.value());
            lines.add(field.key() + ": " + value);
        }
        lines.add(END);
        return lines;
    }

    private static String orUnknown(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        String stripped = value.strip().replace('\n', ' ').replace('\r', ' ');
        return stripped.isEmpty() ? UNKNOWN : stripped;
    }

    /** One header key and its value. */
    public record Field(String key, String value) {
    }
}
