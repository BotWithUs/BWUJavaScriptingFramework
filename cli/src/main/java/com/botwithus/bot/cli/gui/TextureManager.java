package com.botwithus.bot.cli.gui;

import org.lwjgl.opengl.GL11;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Manages OpenGL texture lifecycle for inline images and streams.
 * All actual GL calls must happen on the GL thread; other threads
 * enqueue operations via {@link #queueOperation(Runnable)}.
 */
public final class TextureManager {

    public TextureManager() {}

    private final Queue<Runnable> pendingOps = new ConcurrentLinkedQueue<>();

    /**
     * Create an OpenGL texture from a BufferedImage. MUST be called on the GL thread.
     */
    public int createTexture(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();

        int[] pixels = new int[w * h];
        image.getRGB(0, 0, w, h, pixels, 0, w);

        ByteBuffer buffer = ByteBuffer.allocateDirect(w * h * 4);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = pixels[y * w + x];
                buffer.put((byte) ((argb >> 16) & 0xFF)); // R
                buffer.put((byte) ((argb >> 8) & 0xFF));  // G
                buffer.put((byte) (argb & 0xFF));          // B
                buffer.put((byte) ((argb >> 24) & 0xFF)); // A
            }
        }
        buffer.flip();

        int texId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, w, h, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        return texId;
    }

    /**
     * Delete an old texture and create a new one from the given image.
     * MUST be called on the GL thread.
     */
    public int updateTexture(int existingTexId, BufferedImage image) {
        if (existingTexId > 0) {
            GL11.glDeleteTextures(existingTexId);
        }
        return createTexture(image);
    }

    /**
     * Delete a texture. MUST be called on the GL thread.
     */
    public void deleteTexture(int texId) {
        if (texId > 0) {
            GL11.glDeleteTextures(texId);
        }
    }

    /**
     * Enqueue an operation to run on the GL thread during the next frame.
     * Safe to call from any thread.
     */
    public void queueOperation(Runnable op) {
        pendingOps.add(op);
    }

    /**
     * Drain and execute all queued operations. Call once per frame from the GL thread.
     */
    public void processPending() {
        Runnable op;
        while ((op = pendingOps.poll()) != null) {
            op.run();
        }
    }
}
