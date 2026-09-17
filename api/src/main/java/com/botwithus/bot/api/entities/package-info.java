/**
 * Rich entity wrappers and fluent query builders.
 *
 * <p>This package sits on top of two substrates:</p>
 * <ul>
 *   <li>{@code api.snapshot.*} — read-only tick-scoped records pulled directly
 *       from shared memory. Entity queries iterate these for the cheap path
 *       (no RPC).</li>
 *   <li>{@code api.GameAPI} — RPC and definition lookups for fields that
 *       aren't on the snapshot wire (NPC names, options, varbits/varps, etc.).
 *       Definition lookups are cached by id.</li>
 * </ul>
 *
 * <p>Obtain query facades through {@code GameAPI}:</p>
 * <pre>{@code
 *   Npc target = api.npcs().query()
 *       .named("Goblin")
 *       .withinDistance(15)
 *       .filter(n -> n.hasOption("Attack"))
 *       .nearest();
 *   if (target != null) target.interact("Attack");
 * }</pre>
 *
 * <p>The facades ({@link com.botwithus.bot.api.entities.Npcs},
 * {@link com.botwithus.bot.api.entities.Players}) are singletons per
 * {@code GameAPI}; only the per-query builder and the per-result wrappers
 * allocate.</p>
 */
package com.botwithus.bot.api.entities;
