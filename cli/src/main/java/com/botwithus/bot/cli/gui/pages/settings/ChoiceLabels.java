package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.cli.settings.FreeToPlayRouting;
import com.botwithus.bot.cli.settings.SettingKeys;
import com.botwithus.bot.cli.settings.StartMode;
import com.botwithus.bot.cli.settings.TextSize;

import java.util.Locale;

/**
 * What a choice setting's options are called on the page. The file stores the
 * constant name ({@code PERCENT_125}); the control shows words ({@code 125%}).
 */
final class ChoiceLabels {

    private static final String PERCENT = "%";

    private ChoiceLabels() {
    }

    /**
     * @param keyName        the setting the option belongs to
     * @param optionName     the option's constant name
     * @param windowsPercent the monitor's display scaling, for "Match Windows (150%)"
     */
    static String label(String keyName, String optionName, int windowsPercent) {
        if (keyName.equals(SettingKeys.TEXT_SIZE.name())) {
            return textSize(TextSize.valueOf(optionName), windowsPercent);
        }
        if (keyName.equals(SettingKeys.START_MODE.name())) {
            return startMode(StartMode.valueOf(optionName));
        }
        if (keyName.equals(SettingKeys.WALK_FREE_TO_PLAY.name())) {
            return freeToPlay(FreeToPlayRouting.valueOf(optionName));
        }
        return words(optionName);
    }

    private static String textSize(TextSize size, int windowsPercent) {
        return switch (size) {
            case MATCH_WINDOWS -> "Match Windows (" + windowsPercent + PERCENT + ")";
            case PERCENT_100, PERCENT_125, PERCENT_150, PERCENT_175 -> size.percent() + PERCENT;
        };
    }

    private static String startMode(StartMode mode) {
        return switch (mode) {
            case NORMAL -> "Normal";
            case ADVANCED -> "Advanced";
        };
    }

    private static String freeToPlay(FreeToPlayRouting routing) {
        return switch (routing) {
            case OFF -> "Off";
            case RESTRICT_WHEN_FREE_TO_PLAY -> "Restrict when the account is free-to-play";
        };
    }

    /** {@code SOME_CONSTANT} → {@code "Some constant"}. */
    static String words(String constantName) {
        String spaced = constantName.replace('_', ' ').toLowerCase(Locale.ROOT);
        return spaced.isEmpty() ? spaced : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
