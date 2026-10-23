package com.botwithus.bot.cli.sdnlive;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * Watches the file courier's directory for the whole run and remembers every
 * install-exchange file that ever appeared in it. A pipe delivery must leave
 * none: the request, the key and the bundle all travel over the launcher's pipe.
 * The catalogue's own request file is not an install file and is ignored.
 */
final class RendezvousWatch implements AutoCloseable {

    /** The suffixes of the file courier's install exchange. */
    private static final List<String> INSTALL_SUFFIXES = List.of(".instreq", ".pub", ".sdn");
    private static final Duration POLL = Duration.ofMillis(20);

    private final Path directory;
    private final Set<String> seen = new ConcurrentSkipListSet<>();
    private final Thread poller;
    private volatile boolean isRunning = true;

    private RendezvousWatch(Path directory) {
        this.directory = directory;
        this.poller = Thread.ofVirtual().name("sdn-live-rendezvous-watch").start(this::pollUntilClosed);
    }

    static RendezvousWatch start(Path directory) {
        return new RendezvousWatch(directory);
    }

    /** Every install-exchange file name seen so far, in name order. */
    List<String> installFilesSeen() {
        scanOnce();
        return List.copyOf(seen);
    }

    @Override
    public void close() {
        isRunning = false;
        poller.interrupt();
    }

    private void pollUntilClosed() {
        while (isRunning) {
            scanOnce();
            try {
                Thread.sleep(POLL);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void scanOnce() {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (INSTALL_SUFFIXES.stream().anyMatch(suffix -> name.endsWith(suffix)
                        || name.endsWith(suffix + ".tmp"))) {
                    seen.add(name);
                }
            }
        } catch (IOException e) {
            seen.add("<could not list " + directory + ": " + e.getMessage() + ">");
        }
    }
}
