package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.cli.alerts.IntegrationField;
import com.botwithus.bot.core.alerts.AlertService;

import java.util.Objects;
import java.util.Optional;

/**
 * One field under an Integrations service card: a label on the left, one
 * control on the right. Every field is part of the service's set-up on the
 * launcher, which keeps it. Sealed so the card draws each with an exhaustive
 * {@code switch}.
 */
public sealed interface CardField {

    /** The words on the left. */
    String label();

    /** What "Find a setting" matches for the field. */
    String findText();

    /** Whether the service works with the field left empty; its label then says so. */
    default boolean isOptional() {
        return false;
    }

    /**
     * A plain field in a text box, such as the ntfy topic. Sent to the launcher
     * when the box is left or Enter is pressed.
     *
     * @param field       which field
     * @param text        its value on the launcher
     * @param placeholder shown in the box while it is empty
     * @param error       why the launcher refused the last value, until the next one
     */
    record Setting(IntegrationField field, String label, String text, String placeholder,
                   Optional<String> error) implements CardField {

        public Setting {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(placeholder, "placeholder");
            Objects.requireNonNull(error, "error");
        }

        @Override
        public String findText() {
            return label + " " + field.id();
        }
    }

    /**
     * The service's webhook URL or token, kept by the launcher. The value itself
     * is never part of the view and cannot be read back: only whether one is saved
     * and a masked hint.
     *
     * @param hasSecret   whether one is saved
     * @param secretHint  what may be shown of the saved one, e.g. {@code discord.com/…}
     * @param isOptional  whether the service works without it
     * @param placeholder shown in the box while nothing is saved or typed
     */
    record SecretBox(AlertService service, String label, boolean hasSecret, Optional<String> secretHint,
                     boolean isOptional, String placeholder) implements CardField {

        public SecretBox {
            Objects.requireNonNull(service, "service");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(secretHint, "secretHint");
            Objects.requireNonNull(placeholder, "placeholder");
        }

        @Override
        public String findText() {
            return label + " secret";
        }
    }

    /**
     * An on/off field inside the card, such as Discord's {@code @here}.
     *
     * @param error why the launcher refused the last change, until the next one
     */
    record Toggle(IntegrationField field, String label, boolean isOn, Optional<String> error) implements CardField {

        public Toggle {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(error, "error");
        }

        @Override
        public String findText() {
            return label + " " + field.id();
        }
    }
}
