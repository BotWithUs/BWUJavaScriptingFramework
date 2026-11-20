package com.botwithus.bot.core.cache;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberStrategy;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;

import java.io.IOException;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.Map;

/**
 * Parses the JSON that {@code NXTCache.dll} returns into the untyped maps the cache records
 * carry ({@code StructType.params}, {@code EnumType.entries}, and the rest).
 *
 * <p>A bare {@code new Gson()} turns every JSON number into a {@code Double}, so a struct param
 * the cache stores as the int {@code 2212} reached scripts as {@code 2212.0}: a read that
 * expected an {@code Integer} found nothing, and a {@code toString()} printed a decimal point.
 * The RPC maps come from msgpack and carry {@code Integer}, so the two sources disagreed about
 * the same kind of value. This parser makes them agree: a whole number in {@code int} range is
 * an {@link Integer}, a wider whole number a {@link Long}, and only a fraction stays a
 * {@link Double}.</p>
 */
final class CacheJson {

    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();
    private static final Gson GSON = new GsonBuilder()
            .setObjectToNumberStrategy(new NarrowestNumber())
            .create();

    private CacheJson() {}

    /** The JSON object {@code json} as a map whose numbers are narrowed as the class says. */
    static Map<String, Object> parseObject(String json) {
        return GSON.fromJson(json, MAP_TYPE);
    }

    /** Reads a JSON number as the narrowest of {@code Integer}, {@code Long}, {@code Double}. */
    private static final class NarrowestNumber implements ToNumberStrategy {

        @Override
        public Number readNumber(JsonReader in) throws IOException {
            return narrow(in.nextString());
        }

        private static Number narrow(String text) {
            BigDecimal value = new BigDecimal(text);
            try {
                return value.intValueExact();
            } catch (ArithmeticException notAnInt) {
                return wider(value);
            }
        }

        private static Number wider(BigDecimal value) {
            try {
                return value.longValueExact();
            } catch (ArithmeticException notALong) {
                return value.doubleValue();
            }
        }
    }
}
