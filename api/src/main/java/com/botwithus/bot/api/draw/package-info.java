/**
 * Debug drawing — retained overlay commands the producer renders over the client.
 *
 * <p>{@link com.botwithus.bot.api.draw.Draw}, reached through {@code api.draw()},
 * is the entry point. {@link com.botwithus.bot.api.draw.DrawFrame} is how a script
 * that draws more than a couple of things per tick should draw them.</p>
 *
 * <p>Four things about the wire that this package deliberately does not hide:</p>
 * <ul>
 *   <li>A batch reports refusals inside a <i>successful</i> reply. See
 *       {@link com.botwithus.bot.api.draw.DrawBatchResult}.</li>
 *   <li>A batch is capped at {@link com.botwithus.bot.api.draw.DrawLimits#MAX_BATCH_ITEMS}
 *       items and an over-size one is refused whole.</li>
 *   <li>A listed TTL is what is left, not what you sent. See
 *       {@link com.botwithus.bot.api.draw.DrawEntry#remainingTtlMs()}.</li>
 *   <li>A component highlight's key has a conventional auto format, and it is
 *       exposed rather than left implicit. See
 *       {@link com.botwithus.bot.api.draw.DrawCommand.ComponentTarget#autoKey(int, int)}.</li>
 * </ul>
 *
 * <p>World space ({@link com.botwithus.bot.api.draw.DrawSpace#WORLD}) is on the
 * wire and rejected by the producer until the world-to-screen projection lands.
 * The signatures that will use it ship now so a script written today is the script
 * that works then.</p>
 */
package com.botwithus.bot.api.draw;
