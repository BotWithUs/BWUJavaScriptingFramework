package com.botwithus.bot.cli.sdnlive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * A stand-in for the launcher's SDN courier, run as a child process.
 *
 * <p>The executable is the private SDN tooling's {@code serve} command; this
 * class knows only how to start it, what it logs, and how to stop it. It answers
 * the host's rendezvous files exactly as the launcher's courier does, but signs
 * with a seed the run chose, which only a debug runtime told the matching public
 * key will accept.
 *
 * <p>It stops when its stdin closes, so a courier cannot outlive the check and
 * answer the next run's requests.
 */
final class TestCourier implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TestCourier.class);

    /** The environment variable {@code serve} reads its seed from, keeping it off the command line. */
    private static final String SEED_ENV = "SDNBUNDLE_SEED";
    private static final Duration STARTUP = Duration.ofSeconds(10);
    private static final Duration SHUTDOWN = Duration.ofSeconds(5);
    private static final String READY_LINE = "courier: serving ";
    private static final String DELIVERED_MARK = " jar(s), ";

    private final Process process;
    private final List<String> lines = new CopyOnWriteArrayList<>();

    private TestCourier(Process process) {
        this.process = process;
        Thread.ofVirtual().name("sdn-live-courier-log").start(this::pumpOutput);
    }

    /**
     * Starts a courier over {@code dir} that answers with {@code catalogue} and
     * delivers {@code jarsById}, signed by {@code seedHex}.
     *
     * @throws IllegalStateException if it does not report ready in time
     */
    static TestCourier start(Path executable, String seedHex, Path dir, Path catalogue,
                             Map<String, Path> jarsById) throws IOException {
        List<String> command = new ArrayList<>(List.of(executable.toString(), "serve", dir.toString(),
                catalogue.toString()));
        jarsById.forEach((id, jar) -> command.add(id + "=" + jar));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put(SEED_ENV, seedHex);
        TestCourier courier = new TestCourier(builder.start());
        if (!Waits.until(() -> courier.hasLogged(READY_LINE), STARTUP)) {
            courier.close();
            throw new IllegalStateException("the courier did not start; it said: " + courier.lines);
        }
        return courier;
    }

    /** How many deliveries it has written whose request named {@code scriptId}. */
    long deliveriesNaming(String scriptId) {
        return lines.stream()
                .filter(l -> l.contains(DELIVERED_MARK))
                .filter(l -> idsOf(l).contains(scriptId))
                .count();
    }

    @Override
    public void close() {
        try {
            process.getOutputStream().close();
            if (!process.waitFor(SHUTDOWN.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (IOException e) {
            process.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private boolean hasLogged(String fragment) {
        return lines.stream().anyMatch(l -> l.contains(fragment));
    }

    /** The {@code ids=[a b]} list of a delivery line. */
    private static List<String> idsOf(String line) {
        int open = line.indexOf("ids=[");
        int close = line.indexOf(']', open);
        if (open < 0 || close < 0) {
            return List.of();
        }
        return List.of(line.substring(open + "ids=[".length(), close).split(" "));
    }

    private void pumpOutput() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
                log.info("[courier] {}", line);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
