package com.botwithus.bot.core.launcher;

import com.botwithus.bot.core.pipe.PipeClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scope read through Panama, against the same identity read another way:
 * {@code whoami /user} for the SID and {@code tasklist} for the session. If a
 * real-scope service pipe is visible, its name must carry the same scope. Only
 * the pipe namespace is listed; no pipe is opened. Windows only, so it runs in
 * {@code :core:launcherLiveTest} rather than the CI-run {@code test}.
 */
class LauncherScopeLiveTest {

    private static final Pattern SID = Pattern.compile("(S-1-[0-9-]+)");
    private static final Pattern REAL_PIPE = Pattern.compile("^bwu_svc_auto_([0-9a-f]{16})$");
    private static final int TASKLIST_SESSION_COLUMN = 3;

    @Test
    void panamaScope_matchesWhoamiAndTasklist() throws Exception {
        ServiceScope.ScopeIdentity identity = ServiceScope.current();
        String sid = firstMatch(SID, run("whoami", "/user", "/fo", "csv", "/nh"));
        long session = sessionFromTasklist(ProcessHandle.current().pid());
        assertEquals(sid.toUpperCase(Locale.ROOT), identity.sid().toUpperCase(Locale.ROOT));
        assertEquals(session, identity.sessionId());
        assertEquals(ServiceScope.compute(sid, session), identity.scope());
    }

    @Test
    void visibleRealServicePipe_carriesThisScope() {
        String scope = ServiceScope.current().scope();
        List<String> real = PipeClient.scanPipes("bwu_svc_auto_").stream()
                .filter(name -> REAL_PIPE.matcher(name).matches()).toList();
        System.out.println("real-scope automation pipes visible: " + real + "; this process's scope " + scope);
        real.forEach(name -> assertTrue(name.endsWith(scope),
                name + " is visible in this session but does not carry scope " + scope));
    }

    private static long sessionFromTasklist(long pid) throws IOException, InterruptedException {
        String csv = run("tasklist", "/fi", "PID eq " + pid, "/fo", "csv", "/nh");
        String[] columns = csv.trim().split("\",\"");
        return Long.parseLong(columns[TASKLIST_SESSION_COLUMN].replace("\"", "").trim());
    }

    private static String firstMatch(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        assertTrue(m.find(), "no match for " + pattern + " in " + text);
        return m.group(1);
    }

    private static String run(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out;
        try (InputStream in = process.getInputStream()) {
            out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertEquals(0, process.waitFor(), String.join(" ", command) + ": " + out);
        return out;
    }
}
