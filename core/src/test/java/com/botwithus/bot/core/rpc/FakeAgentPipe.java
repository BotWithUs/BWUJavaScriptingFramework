package com.botwithus.bot.core.rpc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A stand-in for the NXTLibrary agent's pipe server, used to observe what the
 * host does when the producer hangs up.
 *
 * <p>Java cannot create a Windows named pipe server, so this drives a short
 * PowerShell script instead. The pipe is created {@code Byte}-mode to match
 * {@code PIPE_TYPE_BYTE | PIPE_READMODE_BYTE} in
 * {@code NXTLibrary/src/rpc/PipeServer.cpp}.</p>
 *
 * <p>The server echoes each length-prefixed frame back verbatim. That is a
 * valid RPC responder for these tests: {@code RpcClient} matches a response by
 * its {@code id} field, and echoing the request returns the same id with no
 * {@code error} key, so {@code callSync} completes normally.</p>
 *
 * <p>{@link #hangUp()} kills the server process, so Windows closes the pipe
 * handle exactly the way {@code ServeClient}'s caller does with
 * {@code DisconnectNamedPipe} + {@code CloseHandle} — the client's next
 * {@code WriteFile} then fails with {@code ERROR_NO_DATA} (232), "The pipe is
 * being closed".</p>
 */
final class FakeAgentPipe implements AutoCloseable {

    /** How long to wait for PowerShell to report the pipe is listening. */
    private static final long READY_TIMEOUT_SECONDS = 30L;

    private static final String SERVER_SCRIPT = """
            param([string]$PipeName)
            $ErrorActionPreference = 'Stop'
            $s = New-Object System.IO.Pipes.NamedPipeServerStream(
                $PipeName,
                [System.IO.Pipes.PipeDirection]::InOut,
                1,
                [System.IO.Pipes.PipeTransmissionMode]::Byte,
                [System.IO.Pipes.PipeOptions]::None,
                65536, 65536)
            Write-Output 'READY'
            $s.WaitForConnection()
            Write-Output 'CONNECTED'
            while ($true) {
                $h = New-Object byte[] 4
                $got = 0
                while ($got -lt 4) {
                    $r = $s.Read($h, $got, 4 - $got)
                    if ($r -le 0) { exit }
                    $got += $r
                }
                $len = [BitConverter]::ToInt32($h, 0)
                $b = New-Object byte[] $len
                $off = 0
                while ($off -lt $len) {
                    $r = $s.Read($b, $off, $len - $off)
                    if ($r -le 0) { exit }
                    $off += $r
                }
                $o = New-Object byte[] (4 + $len)
                [Array]::Copy($h, 0, $o, 0, 4)
                [Array]::Copy($b, 0, $o, 4, $len)
                $s.Write($o, 0, $o.Length)
                $s.Flush()
            }
            """;

    private final String pipeName;
    private final Process process;
    private final Path scriptPath;

    private FakeAgentPipe(String pipeName, Process process, Path scriptPath) {
        this.pipeName = pipeName;
        this.process = process;
        this.scriptPath = scriptPath;
    }

    /** Starts a server and returns once it is listening. */
    static FakeAgentPipe start(String pipeName) throws IOException, InterruptedException {
        Path script = Files.createTempFile("fake-agent-pipe", ".ps1");
        Files.writeString(script, SERVER_SCRIPT, StandardCharsets.UTF_8);

        Process p = new ProcessBuilder(List.of(
                powershellPath(), "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", script.toString(), "-PipeName", pipeName))
                .redirectErrorStream(true)
                .start();

        awaitReady(p);
        return new FakeAgentPipe(pipeName, p, script);
    }

    /**
     * Absolute path to PowerShell. The Gradle test JVM does not inherit a PATH
     * containing System32, so the bare name fails with CreateProcess error=2.
     */
    private static String powershellPath() {
        String root = System.getenv("SystemRoot");
        if (root == null || root.isBlank()) {
            return "powershell.exe";
        }
        Path exe = Path.of(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        return Files.isExecutable(exe) ? exe.toString() : "powershell.exe";
    }

    private static void awaitReady(Process p) throws IOException, InterruptedException {
        BufferedReader out = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(READY_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            String line = out.readLine();
            if (line == null) {
                throw new IOException("pipe server exited before signalling READY");
            }
            if (line.contains("READY")) {
                return;
            }
        }
        throw new IOException("pipe server did not signal READY within "
                + READY_TIMEOUT_SECONDS + "s");
    }

    String pipeName() {
        return pipeName;
    }

    /**
     * Kills the server so the OS closes its pipe handle — the agent hanging up
     * with no notice to the client. Returns once the process is gone, so a
     * subsequent write is guaranteed to hit a broken pipe.
     */
    void hangUp() throws InterruptedException {
        process.destroyForcibly();
        process.waitFor();
    }

    /**
     * Declared without {@code throws} so try-with-resources does not trip the
     * {@code [try]} lint under {@code -Werror}. An interrupt during teardown is
     * restored on the thread rather than propagated.
     */
    @Override
    public void close() {
        process.destroyForcibly();
        try {
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            Files.deleteIfExists(scriptPath);
        } catch (IOException e) {
            // Temp-file cleanup only; nothing the test can do about it.
        }
    }
}
