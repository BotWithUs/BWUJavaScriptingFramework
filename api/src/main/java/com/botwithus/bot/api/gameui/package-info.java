/**
 * Plugin UI: native game components (canvases, rectangles, lines, text, layers,
 * and the interactive buttons, checks, lists, input fields, sliders, combo fields,
 * panels and grids) that a script creates and the client itself draws, with its
 * own z-order, scaling and styling. Reached through {@code api.gameUi()}. The
 * user's actions on them arrive as typed {@link com.botwithus.bot.api.gameui.UiHookEvent}s.
 *
 * <p>This is an additional, opt-in route. The script's ImGui tab
 * ({@code com.botwithus.bot.api.ui}) and the debug-draw overlay
 * ({@code com.botwithus.bot.api.draw}) are unchanged and remain the right tools
 * for configuration panels and for diagnostics.</p>
 *
 * <p>What this package deliberately makes impossible rather than checks:</p>
 * <ul>
 *   <li>editing a Jagex component: {@link com.botwithus.bot.api.gameui.JagexComponent}
 *       has no setter;</li>
 *   <li>the interaction configs the agent refuses on every agent component: no
 *       props builder can spell them;</li>
 *   <li>category {@code 0}: {@link com.botwithus.bot.api.gameui.UiPlacement}
 *       rejects it at construction;</li>
 *   <li>a slider range sent in parts: {@link com.botwithus.bot.api.gameui.SliderProps#range}
 *       takes all four values;</li>
 *   <li>a prop on the wrong type: each props class carries only its type's.</li>
 * </ul>
 */
package com.botwithus.bot.api.gameui;
