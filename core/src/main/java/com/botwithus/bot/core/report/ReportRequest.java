package com.botwithus.bot.core.report;

import com.botwithus.bot.core.runlog.KnownNames;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * What the host asks the launcher to report: everything it knows about one
 * script on one client. The launcher fills what is missing from the run log's
 * header, bundles the logs and uploads them.
 *
 * <p>Absent optionals are left out of the JSON, so the launcher can tell "not
 * known here" from a value. {@code crash} is the exception: it is always
 * written, as {@code null} when the script has not crashed, because an absent
 * {@code crash} asks the launcher to parse one out of the log instead.</p>
 *
 * @param scriptName    the script's manifest name
 * @param scriptSlug    the slug its run logs live under
 * @param scriptId      the website's id for a Store script; empty for a local build
 * @param scriptVersion the manifest version, if known
 * @param hostVersion   this host's build version
 * @param runId         the run the report is about: the crashed one, else the latest
 * @param runLogDir     the directory holding the script's run logs
 * @param gamePid       the game client's process id, if known
 * @param knownNames    the names the launcher must redact
 * @param crash         the trimmed crash, or empty when there was none
 * @param userNote      what the user typed; blank when nothing
 */
public record ReportRequest(String scriptName, String scriptSlug, OptionalLong scriptId,
                            Optional<String> scriptVersion, String hostVersion, Optional<String> runId,
                            Optional<Path> runLogDir, OptionalLong gamePid, KnownNames knownNames,
                            Optional<CrashPayload> crash, String userNote) {

    /** The request format this host writes. */
    public static final int FORMAT = 1;
    /** What this host calls itself in a report. */
    public static final String HOST = "java";
    /** Longest note the website keeps, in characters. */
    public static final int MAX_NOTE_CHARS = 2000;

    public ReportRequest {
        Objects.requireNonNull(scriptName, "scriptName");
        Objects.requireNonNull(scriptSlug, "scriptSlug");
        Objects.requireNonNull(scriptId, "scriptId");
        Objects.requireNonNull(scriptVersion, "scriptVersion");
        Objects.requireNonNull(hostVersion, "hostVersion");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(runLogDir, "runLogDir");
        Objects.requireNonNull(gamePid, "gamePid");
        Objects.requireNonNull(knownNames, "knownNames");
        Objects.requireNonNull(crash, "crash");
        userNote = CrashPayload.cutChars(userNote == null ? "" : userNote.strip(), MAX_NOTE_CHARS);
    }

    /** The request as the launcher reads it: UTF-8 JSON, one object. */
    public String toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("format", FORMAT);
        o.addProperty("script_name", scriptName);
        o.addProperty("script_slug", scriptSlug);
        scriptId.ifPresent(id -> o.addProperty("script_id", id));
        scriptVersion.ifPresent(v -> o.addProperty("script_version", v));
        o.addProperty("host", HOST);
        o.addProperty("host_version", hostVersion);
        runId.ifPresent(id -> o.addProperty("run_id", id));
        runLogDir.ifPresent(dir -> o.addProperty("run_log_dir", dir.toString()));
        gamePid.ifPresent(pid -> o.addProperty("game_pid", pid));
        o.add("known_names", names(knownNames));
        o.add("crash", crash.<JsonElement>map(ReportRequest::crash).orElse(JsonNull.INSTANCE));
        if (!userNote.isEmpty()) {
            o.addProperty("user_note", userNote);
        }
        return new GsonBuilder().serializeNulls().create().toJson(o);
    }

    private static JsonObject names(KnownNames names) {
        JsonObject o = new JsonObject();
        o.add("accounts", array(names.accounts()));
        o.add("players", array(names.players()));
        return o;
    }

    private static JsonObject crash(CrashPayload crash) {
        JsonObject o = new JsonObject();
        o.addProperty("phase", crash.phase());
        o.addProperty("exception", crash.exception());
        o.addProperty("top_frame", crash.topFrame());
        o.addProperty("stack", crash.stack());
        o.add("breadcrumbs", array(crash.breadcrumbs()));
        return o;
    }

    private static JsonArray array(List<String> values) {
        JsonArray a = new JsonArray();
        values.forEach(a::add);
        return a;
    }
}
