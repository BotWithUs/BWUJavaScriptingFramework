package com.botwithus.bot.api.combat;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.gameval.GamevalRef;
import com.botwithus.bot.api.gameval.GamevalType;
import com.botwithus.bot.api.model.EnumType;
import com.botwithus.bot.api.model.StructType;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every ability the cache defines, resolved the way the action bar does: a slot's type code
 * picks an ability enum through {@code COMBATV2_TYPE_TO_ENUM}, and the slot's id is the key
 * into that enum, whose value is the ability's struct.
 *
 * <p>Everything here is cache data, read through {@link GameAPI#getEnumType(int)} and
 * {@link GameAPI#getStructType(int)} and memoised. A host with no cache open resolves
 * nothing: lookups return empty rather than throw.</p>
 */
public final class AbilityCatalog {

    private static final GamevalRef TYPE_TO_ENUM =
            new GamevalRef(GamevalType.ENUM, "COMBATV2_TYPE_TO_ENUM", 13199);

    private final GameAPI api;
    private final Map<Long, Optional<Ability>> bySlotKey = new ConcurrentHashMap<>();
    private volatile Map<Integer, Integer> typeToEnum;

    public AbilityCatalog(GameAPI api) {
        this.api = api;
    }

    /** The ability a slot with {@code typeCode} and {@code id} holds, if the cache names one. */
    public Optional<Ability> resolve(int typeCode, int id) {
        if (typeToEnum().isEmpty()) {
            // No cache (yet): answer empty without memoising, so a later call can resolve.
            return Optional.empty();
        }
        long key = ((long) typeCode << Integer.SIZE) | Integer.toUnsignedLong(id);
        return bySlotKey.computeIfAbsent(key, k -> load(typeCode, id));
    }

    /**
     * The first ability named {@code name} (ignoring case) under any slot type. Walks every
     * ability enum the first time, so prefer {@link #resolve} when the slot is known.
     */
    public Optional<Ability> byName(String name) {
        for (Map.Entry<Integer, Integer> type : typeToEnum().entrySet()) {
            Optional<EnumType> abilities = enumType(type.getValue());
            if (abilities.isEmpty()) {
                continue;
            }
            for (int abilityId : abilities.get().intEntries().keySet()) {
                Optional<Ability> ability = resolve(type.getKey(), abilityId);
                if (ability.isPresent() && ability.get().isNamed(name)) {
                    return ability;
                }
            }
        }
        return Optional.empty();
    }

    private Optional<Ability> load(int typeCode, int id) {
        Integer enumId = typeToEnum().get(typeCode);
        if (enumId == null) {
            return Optional.empty();
        }
        return enumType(enumId)
                .flatMap(abilities -> boxed(abilities.intValue(id)))
                .flatMap(this::structType)
                .flatMap(struct -> Ability.fromStruct(typeCode, struct));
    }

    private Map<Integer, Integer> typeToEnum() {
        Map<Integer, Integer> loaded = typeToEnum;
        if (loaded == null) {
            loaded = enumType(TYPE_TO_ENUM.resolve(api.gamevals()))
                    .map(EnumType::intEntries)
                    .orElse(Map.of());
            if (!loaded.isEmpty()) {
                typeToEnum = Map.copyOf(loaded);
            }
        }
        return loaded;
    }

    private Optional<EnumType> enumType(int id) {
        try {
            return Optional.ofNullable(api.getEnumType(id));
        } catch (IllegalStateException noCache) {
            return Optional.empty();
        }
    }

    private Optional<StructType> structType(int id) {
        try {
            return Optional.ofNullable(api.getStructType(id));
        } catch (IllegalStateException noCache) {
            return Optional.empty();
        }
    }

    private static Optional<Integer> boxed(OptionalInt value) {
        return value.isPresent() ? Optional.of(value.getAsInt()) : Optional.empty();
    }
}
