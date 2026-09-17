package com.botwithus.bot.api.gameval;

/**
 * A gameval name paired with the id it resolved to when the calling code was
 * written. Lets api facades address interfaces, components and variables by
 * name — so a game renumber is fixed by refreshing the gameval index — while
 * still working on a host with no index deployed.
 *
 * <pre>{@code
 * static final GamevalRef ITEMS = new GamevalRef(GamevalType.COMPONENT, "BANK__BANK_INV",
 *         Interfaces.componentHash(517, 201));
 * int hash = ITEMS.resolve(api.gamevals());
 * }</pre>
 *
 * @param type       namespace the name lives in
 * @param gameval    the symbolic name
 * @param fallbackId the id to use when the name does not resolve; for
 *                   {@link GamevalType#COMPONENT} the packed
 *                   {@code (interfaceId << 16) | componentId}
 */
public record GamevalRef(GamevalType type, String gameval, int fallbackId) {

    public GamevalRef {
        if (type == null) {
            throw new IllegalArgumentException("type");
        }
        if (gameval == null || gameval.isBlank()) {
            throw new IllegalArgumentException("gameval");
        }
    }

    /**
     * The id {@code index} resolves {@link #gameval()} to, or {@link #fallbackId()}
     * when the name is unknown or no index is deployed.
     */
    public int resolve(GamevalIndex index) {
        return index.id(type, gameval).orElse(fallbackId);
    }
}
