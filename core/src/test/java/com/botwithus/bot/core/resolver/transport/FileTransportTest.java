package com.botwithus.bot.core.resolver.transport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FileTransportTest {

    @TempDir
    Path tempDir;

    private final FileTransport transport = new FileTransport();

    @Test
    void copiesExistingFile() throws IOException {
        Path src = tempDir.resolve("src.txt");
        Files.writeString(src, "hello");
        Path dest = tempDir.resolve("out").resolve("dest.txt");

        TransportResult result = transport.fetch(src.toUri(), dest, Optional.empty()).join();
        assertInstanceOf(TransportResult.Ok.class, result);
        TransportResult.Ok ok = (TransportResult.Ok) result;
        assertEquals(dest, ok.localPath());
        assertEquals(5, ok.bytesWritten());
        assertEquals("hello", Files.readString(dest));
    }

    @Test
    void returnsNotFoundForMissingFile() {
        Path src = tempDir.resolve("nope.txt");
        Path dest = tempDir.resolve("out.txt");
        TransportResult result = transport.fetch(src.toUri(), dest, Optional.empty()).join();
        assertInstanceOf(TransportResult.NotFound.class, result);
        assertFalse(Files.exists(dest));
    }

    @Test
    void leavesNoPartialFileOnFailure() throws IOException {
        Path src = tempDir.resolve("missing.txt");
        Path dest = tempDir.resolve("dest.txt");
        Files.writeString(dest, "preexisting");

        TransportResult result = transport.fetch(src.toUri(), dest, Optional.empty()).join();
        assertInstanceOf(TransportResult.NotFound.class, result);
        assertEquals("preexisting", Files.readString(dest));
    }

    @Test
    void rejectsNonFileScheme() {
        URI http = URI.create("https://example.invalid/something.jar");
        Path dest = tempDir.resolve("dest.jar");
        TransportResult result = transport.fetch(http, dest, Optional.empty()).join();
        assertInstanceOf(TransportResult.Network.class, result);
    }
}
