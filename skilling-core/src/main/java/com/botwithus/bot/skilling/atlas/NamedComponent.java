package com.botwithus.bot.skilling.atlas;

/**
 * A UI component resolved from its gameval symbolic name (index-67 {@code component}
 * group), e.g. {@code "BANK__BANK_INV_BUTTON"} → interface 517, component 39. The
 * gameval id packs both: {@code interfaceId = id >> 16}, {@code componentId = id & 0xFFFF}.
 * Resolving by name means a game-update renumber is fixed by rebuilding the Atlas,
 * not by editing a hardcoded id.
 */
public record NamedComponent(String gameval, int interfaceId, int componentId) {}
