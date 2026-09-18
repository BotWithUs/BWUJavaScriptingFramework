/**
 * Domain-specific sub-interfaces of {@link com.botwithus.bot.api.GameAPI}.
 *
 * <p>These interfaces group related methods into logical domains, making the
 * API easier to navigate and understand. {@code GameAPI} extends all of them,
 * so existing code continues to work unchanged.</p>
 *
 * <ul>
 *   <li>{@link com.botwithus.bot.api.domain.SystemAPI} — connectivity, events, client info</li>
 *   <li>{@link com.botwithus.bot.api.domain.ActionAPI} — action queuing and execution</li>
 *   <li>{@link com.botwithus.bot.api.domain.VariableAPI} — varps, varbits, and client variables</li>
 *   <li>{@link com.botwithus.bot.api.domain.NavigationAPI} — pathfinding, walks, and navigation links</li>
 *   <li>{@link com.botwithus.bot.api.domain.DrawAPI} — debug drawing over the client</li>
 * </ul>
 */
package com.botwithus.bot.api.domain;
