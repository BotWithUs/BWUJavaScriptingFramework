/**
 * Scripter-facing API for querying interface components.
 *
 * <p>{@link com.botwithus.bot.api.component.Components} (obtained via
 * {@code api.components()}) is the entry point: a fluent, RPC-backed query that
 * materializes an interface subtree in one round-trip and filters it
 * client-side, returning rich
 * {@link com.botwithus.bot.api.component.ComponentNode} wrappers with in-memory
 * tree navigation and a thin interaction hook. Categories are exposed as the
 * stable {@link com.botwithus.bot.api.component.ComponentType} enum, decoupled
 * from the build-specific raw type byte.</p>
 *
 * <p>Poll-based: every query is a live read; results reflect one tick — don't
 * cache nodes or trees across ticks.</p>
 */
package com.botwithus.bot.api.component;
