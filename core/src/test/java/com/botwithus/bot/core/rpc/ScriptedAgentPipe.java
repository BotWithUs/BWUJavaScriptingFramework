package com.botwithus.bot.core.rpc;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * A stand-in for the agent's pipe server that answers each request with bytes the
 * test wrote in advance, split into the writes the test chooses.
 *
 * <p>{@link FakeAgentPipe} echoes, which cannot produce a push. This one exists to
 * reproduce the shapes a {@code PIPE_TYPE_BYTE} pipe delivers: several frames in
 * one {@code WriteFile} (two pushes in one agent tick, then the reply), and one
 * frame split over two writes with a pause between them, so the client's first
 * read genuinely returns part of a frame. Like {@link FakeAgentPipe} it is a
 * PowerShell {@code NamedPipeServerStream} in byte mode, because Java cannot
 * create a pipe server.</p>
 *
 * <p>For request {@code i} (in arrival order) the server performs the writes in
 * {@code script.get(i)}, pausing between them. It does not read request bodies
 * beyond framing them, so the test must know which ids its calls will carry; a
 * fresh {@link RpcClient} numbers its calls from 1.</p>
 */
final class ScriptedAgentPipe implements AutoCloseable {

    private static final long READY_TIMEOUT_SECONDS = 30L;
    /** Bytes in the frame length prefix. */
    private static final int HEADER_BYTES = 4;

    private static final String SERVER_SCRIPT = """
            param([string]$PipeName, [string]$Script, [int]$PauseMs)
            $ErrorActionPreference = 'Stop'
            $s = New-Object System.IO.Pipes.NamedPipeServerStream(
                $PipeName, [System.IO.Pipes.PipeDirection]::InOut, 1,
                [System.IO.Pipes.PipeTransmissionMode]::Byte,
                [System.IO.Pipes.PipeOptions]::None, 65536, 65536)
            Write-Output 'READY'
            $s.WaitForConnection()
            foreach ($request in $Script.Split(';')) {
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
                foreach ($chunk in $request.Split(',')) {
                    $bytes = [Convert]::FromBase64String($chunk)
                    $s.Write($bytes, 0, $bytes.Length)
                    $s.Flush()
                    Start-Sleep -Milliseconds $PauseMs
                }
            }
            Start-Sleep -Seconds 30
            """;

    private final String pipeName;
    private final Process process;
    private final Path scriptPath;

    private ScriptedAgentPipe(String pipeName, Process process, Path scriptPath) {
        this.pipeName = pipeName;
        this.process = process;
        this.scriptPath = scriptPath;
    }

    String pipeName() {
        return pipeName;
    }

    /**
     * Starts a server that answers request {@code i} with the writes in
     * {@code script.get(i)}, pausing {@code pauseMs} after each write.
     */
    static ScriptedAgentPipe start(String pipeName, List<List<byte[]>> script, int pauseMs)
            throws IOException, InterruptedException {
        Path file = Files.createTempFile("scripted-agent-pipe", ".ps1");
        Files.writeString(file, SERVER_SCRIPT, StandardCharsets.UTF_8);
        String encoded = script.stream()
                .map(writes -> writes.stream()
                        .map(w -> Base64.getEncoder().encodeToString(w))
                        .collect(Collectors.joining(",")))
                .collect(Collectors.joining(";"));
        Process p = new ProcessBuilder(List.of(powershellPath(), "-NoProfile", "-ExecutionPolicy",
                "Bypass", "-File", file.toString(), "-PipeName", pipeName, "-Script", encoded,
                "-PauseMs", Integer.toString(pauseMs)))
                .redirectErrorStream(true)
                .start();
        awaitReady(p);
        return new ScriptedAgentPipe(pipeName, p, file);
    }

    /** One frame as the agent writes it: the little-endian length, then the body. */
    static byte[] frame(byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(HEADER_BYTES + body.length);
        int n = body.length;
        for (int i = 0; i < HEADER_BYTES; i++) {
            out.write(n >>> (i * Byte.SIZE));
        }
        out.writeBytes(body);
        return out.toByteArray();
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    static byte[] slice(byte[] bytes, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(bytes, from, out, 0, out.length);
        return out;
    }

    private static String powershellPath() {
        String root = System.getenv("SystemRoot");
        if (root == null || root.isBlank()) {
            return "powershell.exe";
        }
        Path exe = Path.of(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        return Files.isExecutable(exe) ? exe.toString() : "powershell.exe";
    }

    private static void awaitReady(Process p) throws IOException {
        BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(),
                StandardCharsets.UTF_8));
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
        throw new IOException("pipe server did not signal READY within " + READY_TIMEOUT_SECONDS + "s");
    }

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
            // Temp-file cleanup only.
        }
    }
}
