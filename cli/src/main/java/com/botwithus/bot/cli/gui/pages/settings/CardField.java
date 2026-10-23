package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.core.alerts.AlertService;

import java.util.Objects;

/**
 * One field under an Integrations service card: a label on the left, one
 * control on the right. Sealed so the card draws each with an exhaustive
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
     * A plain setting in a text box, such as the ntfy topic. Saved like any
     * other setting, when the box is left or Enter is pressed.
     *
     * @param keyName     the setting's property name
     * @param text        its stored value
     * @param placeholder shown in the box while it is empty
     */
    record Setting(String keyName, String label, String text, String placeholder) implements CardField {

        public Setting {
            Objects.requireNonNull(keyName, "keyName");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(placeholder, "placeholder");
        }

        @Override
        public String findText() {
            return label + " " + keyName;
        }
    }

    /**
     * The service's webhook URL or token, kept in the credential store. The
     * value itself is never part of the view: the box reads it only when the user
     * asks to see it.
     *
     * @param hasSecret   whether one is saved
     * @param isOptional  whether the service works without it
     * @param placeholder shown in the box while nothing is saved or typed
     */
    record SecretBox(AlertService service, String label, boolean hasSecret, boolean isOptional,
                     String placeholder) implements CardField {

        public SecretBox {
            Objects.requireNonNull(service, "service");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(placeholder, "placeholder");
        }

        @Override
        public String findText() {
            return label + " secret";
        }
    }

    /**
     * An on/off setting inside the card, such as Discord's {@code @here}.
     *
     * @param keyName the setting's property name
     */
    record Toggle(String keyName, String label, boolean isOn) implements CardField {

        public Toggle {
            Objects.requireNonNull(keyName, "keyName");
            Objects.requireNonNull(label, "label");
        }

        @Override
        public String findText() {
            return label + " " + keyName;
        }
    }
}
