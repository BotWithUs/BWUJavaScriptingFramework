package com.botwithus.bot.api.gameui;

/** Properties of a {@link Text}. */
public final class TextProps extends UiProps<TextProps> {

    public TextProps() {
    }

    @Override
    TextProps self() {
        return this;
    }

    /**
     * The text shown. Must be shorter than {@link UiLimits#TEXT_CAPACITY_BYTES}
     * bytes of UTF-8; the agent refuses a longer one.
     */
    public TextProps text(String text) {
        return putText(text);
    }
}
