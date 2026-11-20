package com.botwithus.bot.api.gameui;

/**
 * Properties of an {@link InputField}. There is no visibility mode: the agent refuses
 * {@code vis_mode}, because a non-zero one marks the field restricted.
 */
public final class InputFieldProps extends UiProps<InputFieldProps> {

    public InputFieldProps() {
    }

    @Override
    InputFieldProps self() {
        return this;
    }

    /**
     * The field's text, shorter than {@link UiLimits#LABEL_CAPACITY_BYTES} bytes of
     * UTF-8. The client may truncate it to the max length or filter it; read it back
     * to see what it kept. <b>This raises {@link UiHook#ON_CONTENT_CHANGED}</b> on a
     * subscribed field, as typed input does.
     */
    public InputFieldProps content(String content) {
        return putString("content", content);
    }

    /** What input the field accepts. {@link InputFilter#OTHER} is readback only. */
    public InputFieldProps filter(InputFilter filter) {
        if (filter == null || filter == InputFilter.OTHER) {
            throw new IllegalArgumentException("not a settable filter: " + filter);
        }
        return put("filter", filter.wireName());
    }

    /** The longest content kept, {@link UiLimits#MIN_INPUT_LENGTH}..{@link UiLimits#MAX_INPUT_LENGTH}. */
    public InputFieldProps maxLength(int maxLength) {
        return put("max_length", maxLength);
    }
}
