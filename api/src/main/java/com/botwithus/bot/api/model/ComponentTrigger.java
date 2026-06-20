package com.botwithus.bot.api.model;

/**
 * One CS2 event trigger bound to an interface {@link Component} — the discovery
 * side of trigger firing. Tells a scripter which input event a component
 * actually responds to, and what to drive via
 * {@link com.botwithus.bot.api.GameAPI#fireComponentTrigger}.
 *
 * @param type     event type the engine indexes the trigger by — {@code 9} = click,
 *                 {@code 10} = key, {@code 39} = op, plus hover / scroll / drag-reaction
 *                 types (see {@code ActionTypes.TRIGGER_TYPE_*}). Pass this as the
 *                 {@code triggerType} argument to {@code fireComponentTrigger}.
 * @param scriptId the CS2 script id the engine runs for that event — look it up
 *                 with the CS2 tooling to see exactly which arguments it consumes.
 */
public record ComponentTrigger(int type, int scriptId) {}
