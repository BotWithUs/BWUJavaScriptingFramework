package com.botwithus.bot.cli.stream;

import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.gui.AnsiOutputBuffer;
import com.botwithus.bot.cli.gui.OutputLine;
import com.botwithus.bot.cli.gui.TextureManager;
import com.botwithus.bot.core.pipe.StreamPipeReader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Manages active video streams: starting/stopping stream pipes, and
 * coordinating inline display via AnsiOutputBuffer + TextureManager.
 */
public class StreamManager {

    private static final Logger log = LoggerFactory.getLogger(StreamManager.class);

    private record ActiveStream(StreamPipeReader reader, OutputLine streamLine, int[] textureId) {}

    private final Map<String, ActiveStream> streams = new LinkedHashMap<>();
    private final AnsiOutputBuffer outputBuffer;
    private final TextureManager textureManager;
    private final PrintStream out;

    public StreamManager(AnsiOutputBuffer outputBuffer, TextureManager textureManager, PrintStream out) {
        this.outputBuffer = outputBuffer;
        this.textureManager = textureManager;
        this.out = out;
    }

    /**
     * Start streaming for a single connection.
     */
    public void startStream(Connection conn, int quality, int frameSkip, int width, int height) {
        String name = conn.getName();
        if (streams.containsKey(name)) {
            out.println("Already streaming '" + name + "'.");
            return;
        }

        // Call start_stream RPC
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("quality", quality);
        params.put("frame_skip", frameSkip);
        params.put("width", width);
        params.put("height", height);

        Map<String, Object> result;
        try {
            result = conn.getRpc().callSync("start_stream", params);
        } catch (Exception e) {
            out.println("Failed to start stream for '" + name + "': " + e.getMessage());
            return;
        }

        out.println("start_stream response: " + result);

        Object pipeNameObj = result.get("pipe_name");
        if (pipeNameObj == null) {
            out.println("Server did not return 'pipe_name' for '" + name + "'. Keys: " + result.keySet());
            return;
        }
        String streamPipeName = pipeNameObj.toString();
        out.println("Stream pipe name: " + streamPipeName);

        // Clamp embedded size
        int embW = Math.min(width, 800);
        int embH = Math.min(height, 450);

        // Insert stream line in buffer (texture starts at 0 = no image yet)
        OutputLine streamLine = outputBuffer.insertStream(name, "Stream: " + name, 0, embW, embH);

        // Track mutable texture ID (array so we can update from lambda)
        int[] texIdHolder = new int[]{0};

        StreamPipeReader reader = new StreamPipeReader(streamPipeName, jpegData -> {
            try {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpegData));
                if (img != null) {
                    textureManager.queueOperation(() -> {
                        int newTexId = textureManager.updateTexture(texIdHolder[0], img);
                        texIdHolder[0] = newTexId;
                        outputBuffer.updateStreamTexture(streamLine, newTexId);
                    });
                }
            } catch (IOException e) {
                log.error("Failed to decode JPEG frame", e);
            }
        });
        reader.setErrorCallback(out::println);

        streams.put(name, new ActiveStream(reader, streamLine, texIdHolder));
        reader.start();

        out.println("Streaming '" + name + "' inline from pipe: " + streamPipeName);
    }

    /**
     * Stop streaming for a single connection.
     */
    public void stopStream(String connectionName, Function<String, Connection> connectionLookup) {
        ActiveStream active = streams.remove(connectionName);
        if (active == null) {
            out.println("No active stream for '" + connectionName + "'.");
            return;
        }

        active.reader.close();

        // Send stop_stream RPC (best-effort)
        Connection conn = connectionLookup.apply(connectionName);
        if (conn != null && conn.isAlive()) {
            try {
                conn.getRpc().callSync("stop_stream", Map.of());
            } catch (Exception e) {
                log.error("Failed to send stop_stream for '{}'", connectionName, e);
            }
        }

        // Clean up texture and remove line
        int texId = active.textureId[0];
        if (texId > 0) {
            textureManager.queueOperation(() -> textureManager.deleteTexture(texId));
        }
        outputBuffer.removeStreamLine(active.streamLine);

        out.println("Stopped stream for '" + connectionName + "'.");
    }

    /**
     * Stop all active streams.
     */
    public void stopAll(Function<String, Connection> connectionLookup) {
        for (var entry : Map.copyOf(streams).entrySet()) {
            String name = entry.getKey();
            ActiveStream active = entry.getValue();
            active.reader.close();

            // Best-effort stop_stream RPC
            if (connectionLookup != null) {
                Connection conn = connectionLookup.apply(name);
                if (conn != null && conn.isAlive()) {
                    try {
                        conn.getRpc().callSync("stop_stream", Map.of());
                    } catch (Exception e) {
                        log.error("Failed to send stop_stream for '{}'", name, e);
                    }
                }
            }

            int texId = active.textureId[0];
            if (texId > 0) {
                textureManager.queueOperation(() -> textureManager.deleteTexture(texId));
            }
            outputBuffer.removeStreamLine(active.streamLine);
        }
        streams.clear();
    }

    /**
     * Handle a connection being lost — clean up its stream if active.
     */
    public void handleConnectionLost(String connectionName) {
        ActiveStream active = streams.remove(connectionName);
        if (active == null) return;

        active.reader.close();
        int texId = active.textureId[0];
        if (texId > 0) {
            textureManager.queueOperation(() -> textureManager.deleteTexture(texId));
        }
        outputBuffer.removeStreamLine(active.streamLine);
    }

    public boolean hasActiveStreams() {
        return !streams.isEmpty();
    }
}
