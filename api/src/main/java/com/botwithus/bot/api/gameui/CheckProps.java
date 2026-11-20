package com.botwithus.bot.api.gameui;

/**
 * Properties of a {@link Check}. There is no label: the agent refuses one on a
 * check as not supported yet, so put a {@link Text} beside it.
 */
public final class CheckProps extends UiProps<CheckProps> {

    public CheckProps() {
    }

    @Override
    CheckProps self() {
        return this;
    }

    /** Whether the check is ticked. Setting it raises no {@link UiHook#ON_BUTTON_PRESSED}. */
    public CheckProps checked(boolean isChecked) {
        return put("checked", isChecked);
    }
}
