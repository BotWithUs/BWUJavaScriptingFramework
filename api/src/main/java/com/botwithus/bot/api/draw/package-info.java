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
 * <p>World space ({@link com.botwithus.bot.api.draw.DrawSpace#WORLD}) now works:
 * the producer projects it to the screen on its own thread. The signatures shipped
 * before it did, deliberately, and none of them had to change when it landed —
 * which was the whole point of putting the field on the wire early. Two carve-outs
 * remain: a polyline is refused in world space, and a component highlight is
 * already on screen so it has no world position.</p>
 *
 * <p>World coordinates are bounded by
 * {@link com.botwithus.bot.api.draw.DrawLimits#MAX_WORLD_COORDINATE}, which is a
 * different and larger limit than the screen one — the two gate what you send and
 * what it projects to, respectively.</p>
 */
package com.botwithus.bot.api.draw;
