package com.botwithus.bot.cli.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stands in for the launcher's side of the report channel in tests: claims the
 * first request that appears, then answers it with a fixed reply, atomically.
 */
final class FakeLauncher {

    private static final String REQUEST = ".rptreq";
    private static final long POLL_MS = 5;

    private final Thread thread;
    private final AtomicReference<String> request = new AtomicReference<>("");

    private FakeLauncher(Path dir, String reply) {
        this.thread = Thread.ofVirtual().start(() -> serveOne(dir, reply));
    }

    /** Starts a launcher that answers one request in {@code dir} with {@code reply}. */
    static FakeLauncher answerOnce(Path dir, String reply) {
        return new FakeLauncher(dir, reply);
    }

    /** The request it answered, once it has. */
    String request() {
        return request.get();
    }

    void join() throws InterruptedException {
        thread.join();
    }

    private void serveOne(Path dir, String reply) {
        try {
            while (true) {
                try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*" + REQUEST)) {
                    for (Path req : files) {
                        answer(dir, req, reply);
                        return;
                    }
                }
                Thread.sleep(POLL_MS);
            }
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private void answer(Path dir, Path req, String reply) throws IOException {
        String id = req.getFileName().toString().replace(REQUEST, "");
        request.set(Files.readString(req, StandardCharsets.UTF_8));
        Files.delete(req);
        Path work = Files.writeString(dir.resolve(id + ".rptwork"), "");
        Path tmp = Files.writeString(dir.resolve(id + ".rpt.tmp"), reply, StandardCharsets.UTF_8);
        Files.move(tmp, dir.resolve(id + ".rpt"), StandardCopyOption.ATOMIC_MOVE);
        Files.delete(work);
    }
}
