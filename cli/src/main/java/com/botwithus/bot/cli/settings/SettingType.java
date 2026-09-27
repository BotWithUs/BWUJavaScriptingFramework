package com.botwithus.bot.cli.settings;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The value type of a {@link SettingKey}: how its text form in
 * {@code config.properties} is parsed, formatted and bounded.
 *
 * <p>Sealed so a settings page can pick a control with an exhaustive
 * {@code switch}: a switch for {@link Flag}, a number box with bounds for
 * {@link WholeNumber} and {@link Decimal}, a text field for {@link Text} and a
 * segmented control or combo for {@link Choice}.</p>
 *
 * <p>{@link #parse} and {@link #validate} throw {@link IllegalArgumentException}
 * whose message is a rule a user can act on, such as
 * {@code "must be a whole number from 500 to 600000"}. {@link SettingKey} prefixes
 * it with the key name.</p>
 *
 * @param <T> the Java type a setting of this type holds
 */
public sealed interface SettingType<T> {

    /** Parses the text form, throwing {@link IllegalArgumentException} if it is not a valid value. */
    T parse(String raw);

    /** Formats a value to the text form {@link #parse} reads back. */
    String format(T value);

    /** Throws {@link IllegalArgumentException} if {@code value} is outside this type's bounds. */
    void validate(T value);

    /** One-line rule for help text and error messages, e.g. {@code "true or false"}. */
    String rule();

    /** A boolean, written {@code true} or {@code false} (either case). */
    record Flag() implements SettingType<Boolean> {

        @Override
        public Boolean parse(String raw) {
            String text = raw.strip().toLowerCase(Locale.ROOT);
            return switch (text) {
                case "true" -> Boolean.TRUE;
                case "false" -> Boolean.FALSE;
                default -> throw new IllegalArgumentException("must be " + rule() + ", got '" + raw + "'");
            };
        }

        @Override
        public String format(Boolean value) {
            return value.toString();
        }

        @Override
        public void validate(Boolean value) {
            if (value == null) {
                throw new IllegalArgumentException("must be " + rule());
            }
        }

        @Override
        public String rule() {
            return "true or false";
        }
    }

    /** A whole number in {@code [min, max]}. */
    record WholeNumber(long min, long max) implements SettingType<Long> {

        public WholeNumber {
            if (min > max) {
                throw new IllegalArgumentException("min " + min + " > max " + max);
            }
        }

        @Override
        public Long parse(String raw) {
            long value;
            try {
                value = Long.parseLong(raw.strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("must be " + rule() + ", got '" + raw + "'", e);
            }
            validate(value);
            return value;
        }

        @Override
        public String format(Long value) {
            return value.toString();
        }

        @Override
        public void validate(Long value) {
            if (value == null || value < min || value > max) {
                throw new IllegalArgumentException("must be " + rule() + ", got " + value);
            }
        }

        @Override
        public String rule() {
            return "a whole number from " + min + " to " + max;
        }
    }

    /** A decimal number in {@code [min, max]}; NaN and infinities are refused. */
    record Decimal(double min, double max) implements SettingType<Double> {

        public Decimal {
            if (!(min <= max)) {
                throw new IllegalArgumentException("min " + min + " > max " + max);
            }
        }

        @Override
        public Double parse(String raw) {
            double value;
            try {
                value = Double.parseDouble(raw.strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("must be " + rule() + ", got '" + raw + "'", e);
            }
            validate(value);
            return value;
        }

        @Override
        public String format(Double value) {
            return value.toString();
        }

        @Override
        public void validate(Double value) {
            if (value == null || !Double.isFinite(value) || value < min || value > max) {
                throw new IllegalArgumentException("must be " + rule() + ", got " + value);
            }
        }

        @Override
        public String rule() {
            return "a number from " + min + " to " + max;
        }
    }

    /**
     * Free text that must fully match {@code pattern}.
     *
     * @param pattern     what a valid value looks like
     * @param description the same rule in words, for messages
     */
    record Text(Pattern pattern, String description) implements SettingType<String> {

        @Override
        public String parse(String raw) {
            String value = raw.strip();
            validate(value);
            return value;
        }

        @Override
        public String format(String value) {
            return value;
        }

        @Override
        public void validate(String value) {
            if (value == null || !pattern.matcher(value).matches()) {
                throw new IllegalArgumentException("must be " + rule() + ", got '" + value + "'");
            }
        }

        @Override
        public String rule() {
            return description;
        }
    }

    /**
     * One of a fixed set of enum constants, written by constant name and read
     * back ignoring case.
     *
     * @param options every allowed value, in display order
     * @param <E>     the enum type
     */
    record Choice<E extends Enum<E>>(List<E> options) implements SettingType<E> {

        public Choice {
            options = List.copyOf(options);
            if (options.isEmpty()) {
                throw new IllegalArgumentException("a choice needs at least one option");
            }
        }

        @Override
        public E parse(String raw) {
            String name = raw.strip();
            for (E option : options) {
                if (option.name().equalsIgnoreCase(name)) {
                    return option;
                }
            }
            throw new IllegalArgumentException("must be " + rule() + ", got '" + raw + "'");
        }

        @Override
        public String format(E value) {
            return value.name();
        }

        @Override
        public void validate(E value) {
            if (value == null || !options.contains(value)) {
                throw new IllegalArgumentException("must be " + rule() + ", got " + value);
            }
        }

        @Override
        public String rule() {
            return "one of " + options.stream().map(Enum::name).collect(Collectors.joining(", "));
        }
    }
}
