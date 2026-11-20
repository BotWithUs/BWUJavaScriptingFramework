package com.botwithus.bot.api.gameui;

/** Properties of a {@link Button}. */
public final class ButtonProps extends UiProps<ButtonProps> {

    public ButtonProps() {
    }

    @Override
    ButtonProps self() {
        return this;
    }

    /** The label, shorter than {@link UiLimits#LABEL_CAPACITY_BYTES} bytes of UTF-8. */
    public ButtonProps label(String label) {
        return putString("label", label);
    }

    /** Whether a press toggles the button on and off rather than just firing. */
    public ButtonProps canToggle(boolean canToggle) {
        return put("can_toggle", canToggle);
    }

    /**
     * Whether the button is toggled on. The agent refuses this for a button that
     * cannot toggle, unless {@link #canToggle(boolean) canToggle(true)} is in the
     * same call.
     */
    public ButtonProps toggled(boolean isToggled) {
        return put("toggled", isToggled);
    }
}
