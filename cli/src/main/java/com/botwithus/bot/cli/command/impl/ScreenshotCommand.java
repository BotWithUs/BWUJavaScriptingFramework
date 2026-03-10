package com.botwithus.bot.cli.command.impl;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.command.Command;
import com.botwithus.bot.cli.command.ParsedCommand;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class ScreenshotCommand implements Command {

    @Override public String name() { return "screenshot"; }
    @Override public List<String> aliases() { return List.of("ss"); }
    @Override public String description() { return "Capture a screenshot from the game client"; }
    @Override public String usage() { return "screenshot [file.png] [--open]"; }

    @Override
    public void execute(ParsedCommand parsed, CliContext ctx) {
        Connection conn = ctx.getActiveConnection();
        if (conn == null) {
            ctx.out().println("No active connection. Use 'connect' first.");
            return;
        }

        // Start progress bar in GUI mode, or just print text in CLI mode
        CliContext.ProgressDisplay progress = ctx.getProgressDisplay();
        Object progressHandle = null;
        if (progress != null) {
            progressHandle = progress.start("Capturing screenshot...");
        } else {
            ctx.out().println("Capturing screenshot...");
        }

        Map<String, Object> response;
        try {
            response = conn.getRpc().callSync("take_screenshot", Map.of());
        } catch (com.botwithus.bot.core.pipe.PipeException | com.botwithus.bot.core.rpc.RpcException e) {
            String msg = "Screenshot failed: " + e.getMessage();
            if (progress != null && progressHandle != null) {
                progress.completeWithError(progressHandle, msg);
            } else {
                ctx.out().println(msg);
            }
            ctx.handleConnectionError(conn.getName());
            return;
        } catch (Exception e) {
            String msg = "Screenshot failed: " + e.getMessage();
            if (progress != null && progressHandle != null) {
                progress.completeWithError(progressHandle, msg);
            } else {
                ctx.out().println(msg);
            }
            return;
        }

        Object data = response.get("data");
        if (data == null) {
            String error = response.getOrDefault("error", "unknown error").toString();
            String msg = "Screenshot failed: " + error;
            if (progress != null && progressHandle != null) {
                progress.completeWithError(progressHandle, msg);
            } else {
                ctx.out().println(msg);
            }
            return;
        }

        byte[] pngBytes;
        if (data instanceof byte[] b) {
            pngBytes = b;
        } else {
            String msg = "Unexpected response format.";
            if (progress != null && progressHandle != null) {
                progress.completeWithError(progressHandle, msg);
            } else {
                ctx.out().println(msg);
            }
            return;
        }

        // Determine output path
        String fileArg = parsed.arg(0);
        Path outPath;
        try {
            if (fileArg != null) {
                Path screenshotsDir = Path.of("screenshots").toAbsolutePath();
                Path requested = screenshotsDir.resolve(fileArg).normalize();
                if (!requested.startsWith(screenshotsDir)) {
                    String msg = "Invalid path: must be within screenshots/ directory.";
                    if (progress != null && progressHandle != null) {
                        progress.completeWithError(progressHandle, msg);
                    } else {
                        ctx.out().println(msg);
                    }
                    return;
                }
                Files.createDirectories(requested.getParent());
                outPath = requested;
            } else {
                Path screenshotsDir = Path.of("screenshots");
                Files.createDirectories(screenshotsDir);
                String timestamp = java.time.LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
                outPath = screenshotsDir.resolve("screenshot_" + timestamp + ".png");
            }
            Files.write(outPath, pngBytes);
        } catch (IOException e) {
            String msg = "Failed to save screenshot: " + e.getMessage();
            if (progress != null && progressHandle != null) {
                progress.completeWithError(progressHandle, msg);
            } else {
                ctx.out().println(msg);
            }
            return;
        }

        ctx.out().println("Saved: " + outPath.toAbsolutePath() + " (" + pngBytes.length + " bytes)");

        // Display inline in GUI — replace progress bar with the image
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

        // Auto-open or if --open flag set
        if (parsed.hasFlag("open")) {
            openFile(outPath, ctx);
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
