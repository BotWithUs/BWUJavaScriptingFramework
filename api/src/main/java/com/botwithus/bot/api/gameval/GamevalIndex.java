package com.botwithus.bot.api.gameval;

import com.botwithus.bot.api.model.ComponentRef;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Resolves gameval symbolic names to entity ids and back.
 *
 * <p>A gameval is the stable name the game itself ships for an entity —
 * {@code YEW_LOGS}, {@code BANK__BANK_INV_BUTTON}, {@code ZAROS_SPELLBOOK}.
 * Unlike a display name it is unique within its {@link GamevalType}, is not
 * localised, and exists for things that have no display name at all (interface
 * components, varbits, params). Unlike a raw id it survives a game update, so a
 * renumber is fixed by refreshing the index rather than editing scripts.</p>
 *
 * <pre>{@code
 * int yew = api.gamevals().require(GamevalType.ITEM, "YEW_LOGS");
 * api.components().get("BANK__BANK_INV_BUTTON").interact(1);
 * }</pre>
 *
 * <p>Obtain one from {@link com.botwithus.bot.api.GameAPI#gamevals()}; it is
 * never {@code null}. When no index is deployed every lookup returns empty and
 * {@link #isAvailable()} is {@code false}, so a script can degrade rather than
 * crash — see {@link #empty()}.</p>
 *
 * <p>Names are matched case-insensitively; the index stores them upper-case.
 * Implementations are safe for concurrent use.</p>
 */
public interface GamevalIndex extends AutoCloseable {

    /** Bit position the interface id occupies in a packed component id. */
    int INTERFACE_ID_SHIFT = 16;

    /** Mask selecting the component-id half of a packed component id. */
    int COMPONENT_ID_MASK = 0xFFFF;

    /** The id for a name, or empty when the name is unknown. */
    OptionalInt id(GamevalType type, String gameval);

    /**
     * The id for a name, or throw.
     *
     * @throws GamevalNotFoundException when the name is unknown or no index is
     *                                  deployed
     */
    default int require(GamevalType type, String gameval) {
        OptionalInt found = id(type, gameval);
        if (found.isEmpty()) {
            throw new GamevalNotFoundException(type, gameval, isAvailable());
        }
        return found.getAsInt();
    }

    /** The name for an id, or empty when the id has none. */
    Optional<String> gameval(GamevalType type, int id);

    /**
     * Names in {@code type} starting with {@code prefix}, ordered by name.
     * Intended for tooling and diagnostics — a script that needs one id should
     * name it exactly.
     *
     * @param limit maximum results; must be positive
     */
    List<GamevalEntry> startingWith(GamevalType type, String prefix, int limit);

    /**
     * Splits a {@link GamevalType#COMPONENT} name into the interface and
     * component ids it packs, e.g. {@code "BANK__BANK_INV_BUTTON"} → interface
     * 517, component 39. Empty when the name is unknown.
     */
    default Optional<ComponentRef> component(String gameval) {
        OptionalInt packed = id(GamevalType.COMPONENT, gameval);
        if (packed.isEmpty()) {
            return Optional.empty();
        }
        int value = packed.getAsInt();
        return Optional.of(new ComponentRef(value >>> INTERFACE_ID_SHIFT,
                value & COMPONENT_ID_MASK));
    }

    /** Shorthand for {@code id(GamevalType.INTERFACE, gameval)}. */
    default OptionalInt interfaceId(String gameval) {
        return id(GamevalType.INTERFACE, gameval);
    }

    /** False when no index is deployed and every lookup will return empty. */
    boolean isAvailable();

    /**
     * A value from the index's build stamp — {@code built}, {@code source_mtime},
     * {@code rows}, {@code groups}, {@code schema_version}. Empty when absent.
     */
    Optional<String> meta(String key);

    /** Releases any resources held. Idempotent. */
    @Override
    default void close() {
    }

    /**
     * An index that resolves nothing — the fallback when none is deployed.
     * Test for it with {@link #isAvailable()}, never by identity.
     */
    static GamevalIndex empty() {
        return new EmptyGamevalIndex();
    }
}
