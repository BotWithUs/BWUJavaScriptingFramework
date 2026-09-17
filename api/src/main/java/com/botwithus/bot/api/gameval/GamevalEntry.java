package com.botwithus.bot.api.gameval;

/**
 * One name↔id pairing from the gameval index.
 *
 * @param type    namespace the name lives in
 * @param id      the entity id; for {@link GamevalType#COMPONENT} this is the
 *                packed {@code (interfaceId << 16) | componentId}
 * @param gameval the symbolic name, always upper-case
 */
public record GamevalEntry(GamevalType type, int id, String gameval) {
}
