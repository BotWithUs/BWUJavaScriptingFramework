package com.botwithus.bot.api.model;

/**
 * Information about an active JPEG frame stream.
 *
 * @param pipeName  the named pipe to connect to for reading frames
 * @param frameSkip capture every Nth frame (1 = every frame)
 * @param quality   JPEG quality 1-100
 * @param width     output width in pixels
 * @param height    output height in pixels
 * @see com.botwithus.bot.api.GameAPI#startStream
 */
public record StreamInfo(String pipeName, int frameSkip, int quality, int width, int height) {}
