package com.botwithus.bot.cli.command.impl;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.command.Command;
import com.botwithus.bot.cli.command.ParsedCommand;
import com.botwithus.bot.core.pipe.PipeException;
import com.botwithus.bot.core.rpc.RpcException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

public class ScreenshotCommand implements Command {

    @Override public String name() { return "screenshot"; }
    @Override public List<String> aliases() { return List.of("ss"); }
    @Override public String description() { return "Capture a screenshot from the game client"; }
    @Override public String usage() { return "screenshot [file.png] [--open]"; }

    private static final DateTimeFormatter FILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    @Override
    public void execute(ParsedCommand parsed, CliContext ctx) {
        Connection conn = ctx.getActiveConnection();
        if (conn == null) {
            ctx.out().println("No active connection. Use 'connect' first.");
            return;
        }

        CliContext.ProgressDisplay progress = ctx.getProgressDisplay();
        Object progressHandle = startProgress(progress, ctx);

        byte[] pngBytes = fetchScreenshot(conn, ctx, progress, progressHandle);
        if (pngBytes == null) {
            return;
        }

        Path outPath = writeScreenshotFile(parsed.arg(0), pngBytes, ctx, progress, progressHandle);
        if (outPath == null) {
            return;
        }

        ctx.out().println("Saved: " + outPath.toAbsolutePath() + " (" + pngBytes.length + " bytes)");
        displayScreenshot(pngBytes, ctx, progress, progressHandle);

        if (parsed.hasFlag("open")) {
            openFile(outPath, ctx);
        }
    }

    private static Object startProgress(CliContext.ProgressDisplay progress, CliContext ctx) {
        if (progress != null) {
            return progress.start("Capturing screenshot...");
        }
        ctx.out().println("Capturing screenshot...");
        return null;
    }

    /** Report an error to the GUI progress bar if one is active, otherwise to stdout. */
    private static void reportError(String msg, CliContext ctx,
                                    CliContext.ProgressDisplay progress, Object progressHandle) {
        if (progress != null && progressHandle != null) {
            progress.completeWithError(progressHandle, msg);
        } else {
            ctx.out().println(msg);
        }
    }

    /** Returns the screenshot PNG bytes, or null when the call failed (error already reported). */
    private static byte[] fetchScreenshot(Connection conn, CliContext ctx,
                                          CliContext.ProgressDisplay progress, Object progressHandle) {
        Map<String, Object> response;
        try {
            response = conn.getRpc().callSync("take_screenshot", Map.of());
        } catch (PipeException | RpcException e) {
            reportError("Screenshot failed: " + e.getMessage(), ctx, progress, progressHandle);
            ctx.handleConnectionError(conn.getName());
            return null;
        } catch (Exception e) {
            reportError("Screenshot failed: " + e.getMessage(), ctx, progress, progressHandle);
            return null;
        }

        Object data = response.get("data");
        if (data == null) {
            String error = response.getOrDefault("error", "unknown error").toString();
            reportError("Screenshot failed: " + error, ctx, progress, progressHandle);
            return null;
        }
        if (data instanceof byte[] b) {
            return b;
        }
        reportError("Unexpected response format.", ctx, progress, progressHandle);
        return null;
    }

    /** Returns the path written, or null on failure (error already reported). */
    private static Path writeScreenshotFile(String fileArg, byte[] pngBytes, CliContext ctx,
                                            CliContext.ProgressDisplay progress, Object progressHandle) {
        try {
            Path outPath = resolveOutputPath(fileArg);
            if (outPath == null) {
                reportError("Invalid path: must be within screenshots/ directory.",
                        ctx, progress, progressHandle);
                return null;
            }
            Files.write(outPath, pngBytes);
            return outPath;
        } catch (IOException e) {
            reportError("Failed to save screenshot: " + e.getMessage(), ctx, progress, progressHandle);
            return null;
        }
    }

    /** Returns null when a user-supplied path escapes the screenshots/ sandbox. */
    private static Path resolveOutputPath(String fileArg) throws IOException {
        if (fileArg != null) {
            Path screenshotsDir = Path.of("screenshots").toAbsolutePath();
            Path requested = screenshotsDir.resolve(fileArg).normalize();
            if (!requested.startsWith(screenshotsDir)) {
                return null;
            }
            Files.createDirectories(requested.getParent());
            return requested;
        }
        Path screenshotsDir = Path.of("screenshots");
        Files.createDirectories(screenshotsDir);
        return screenshotsDir.resolve("screenshot_" + LocalDateTime.now().format(FILE_TIMESTAMP) + ".png");
    }

    private static void displayScreenshot(byte[] pngBytes, CliContext ctx,
                                          CliContext.ProgressDisplay progress, Object progressHandle) {
        if (progress != null && progressHandle != null) {
            try {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(pngBytes));
                if (img != null) {
                    progress.completeWithImage(progressHandle, img);
                } else {
                    progress.completeWithError(progressHandle, "Could not decode image.");
                }
            } catch (IOException e) {
                progress.completeWithError(progressHandle, "Could not display inline: " + e.getMessage());
            }
        } else if (ctx.getImageDisplay() != null) {
            try {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(pngBytes));
                if (img != null) {
                    ctx.getImageDisplay().display(img);
                }
            } catch (IOException e) {
                ctx.out().println("Could not display inline: " + e.getMessage());
            }
        }
    }

    private void openFile(Path path, CliContext ctx) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            ProcessBuilder pb;
            if (os.contains("win")) {
                pb = new ProcessBuilder("cmd", "/c", "start", "", path.toAbsolutePath().toString());
            } else if (os.contains("mac")) {
                pb = new ProcessBuilder("open", path.toAbsolutePath().toString());
            } else {
                pb = new ProcessBuilder("xdg-open", path.toAbsolutePath().toString());
            }
            pb.start();
        } catch (IOException e) {
            ctx.out().println("Could not open file: " + e.getMessage());
        }
    }
}
