package com.botwithus.bot.cli.settings;

/**
 * A setting's effective value changed. Values are in their text form, as
 * {@link SettingKey#format} writes them; a reset reports the default's text.
 *
 * @param key      the setting that changed
 * @param oldValue the previous effective value
 * @param newValue the new effective value
 */
public record SettingChange(SettingKey<?> key, String oldValue, String newValue) {}
