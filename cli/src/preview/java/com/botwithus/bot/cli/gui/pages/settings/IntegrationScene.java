package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.cli.settings.AlertSettingKeys;
import com.botwithus.bot.cli.settings.SettingKey;
import com.botwithus.bot.core.alerts.AlertKind;
import com.botwithus.bot.core.alerts.AlertService;

/**
 * DEV ONLY. The Integrations section's preview captures: each service status,
 * the event grid, session-only secrets, a secret shown and one refused, and the
 * rows under the grid. Each scene sets the fixture up on the first frame, does
 * what a user would on the next, and scrolls to the section once it has laid out.
 */
public enum IntegrationScene {

    OFF("130-settings-integrations-off"),
    NOT_TESTED("131-settings-integrations-not-tested"),
    WORKING("132-settings-integrations-working"),
    FAILED("133-settings-integrations-last-send-failed"),
    SENDING("134-settings-integrations-sending-test"),
    GRID("135-settings-integrations-event-grid"),
    SESSION_ONLY("136-settings-integrations-session-only-secrets"),
    SHOWN("137-settings-integrations-secret-shown"),
    REFUSED("138-settings-integrations-secret-refused"),
    LOWER_ROWS("139-settings-integrations-bursts-quiet-summary");

    private static final int SET_UP_FRAME = 0;
    private static final int ACT_FRAME = 1;
    private static final int SCROLL_FRAME = 2;
    private static final int NUDGE_FRAME = 4;
    /** Far enough to bring the rows under the grid into view with every service off. */
    private static final float LOWER_ROWS_PX = 360f;
    private static final String GRID_QUERY = "which alerts";
    /** Not https, so the back end refuses it. Placeholder only. */
    private static final String NOT_HTTPS = "http://hooks.slack.com/services/…";

    private final String fileName;

    IntegrationScene(String fileName) {
        this.fileName = fileName;
    }

    /** The PNG's name, without the extension. */
    public String fileName() {
        return fileName;
    }

    /** Plays frame {@code frame} of the scene on the Settings page. */
    public void play(SettingsPage page, FixtureSettingsModel model, int frame) {
        switch (frame) {
            case SET_UP_FRAME -> setUp(model);
            case ACT_FRAME -> act(page, model);
            case SCROLL_FRAME -> SettingsPreviewSeams.showSection(page, SettingsSection.INTEGRATIONS);
            case NUDGE_FRAME -> {
                if (this == LOWER_ROWS) {
                    SettingsPreviewSeams.scrollDown(page, LOWER_ROWS_PX);
                }
            }
            default -> { }
        }
    }

    private void setUp(FixtureSettingsModel model) {
        FixtureIntegrations alerts = model.integrations();
        switch (this) {
            case OFF -> { }
            case NOT_TESTED -> alerts.switchOn(AlertService.NTFY);
            case WORKING -> {
                alerts.switchOn(AlertService.NTFY);
                alerts.testDelivered(AlertService.NTFY);
            }
            case FAILED -> {
                alerts.switchOn(AlertService.DISCORD);
                alerts.saveFakeSecret(AlertService.DISCORD);
                alerts.lastSendFailed(AlertService.DISCORD);
            }
            case SENDING -> {
                alerts.switchOn(AlertService.SLACK);
                alerts.saveFakeSecret(AlertService.SLACK);
            }
            case REFUSED -> alerts.switchOn(AlertService.SLACK);
            case GRID -> routeSome(model);
            case SESSION_ONLY -> {
                alerts.keepSecretsForThisSessionOnly();
                alerts.switchOn(AlertService.SLACK);
            }
            case SHOWN -> {
                alerts.switchOn(AlertService.DISCORD);
                alerts.saveFakeSecret(AlertService.DISCORD);
                set(model, AlertSettingKeys.DISCORD_MENTION_HERE, "true");
            }
            case LOWER_ROWS -> {
                set(model, AlertSettingKeys.BURST_SECONDS, "60");
                set(model, AlertSettingKeys.QUIET_ENABLED, "true");
                set(model, AlertSettingKeys.QUIET_FROM, "23:00");
                set(model, AlertSettingKeys.QUIET_TO, "07:00");
            }
        }
    }

    private void act(SettingsPage page, FixtureSettingsModel model) {
        switch (this) {
            case SENDING -> model.sendTest(AlertService.SLACK);
            case GRID -> SettingsPreviewSeams.find(page, GRID_QUERY);
            case SHOWN -> SettingsPreviewSeams.revealSecret(page, AlertService.DISCORD);
            case REFUSED -> SettingsPreviewSeams.typeSecret(page, AlertService.SLACK, NOT_HTTPS);
            case OFF, NOT_TESTED, WORKING, FAILED, SESSION_ONLY, LOWER_ROWS -> { }
        }
    }

    /** ntfy and Discord on, Slack off, and Discord sent a little more than the defaults. */
    private static void routeSome(FixtureSettingsModel model) {
        model.integrations().switchOn(AlertService.NTFY);
        model.integrations().switchOn(AlertService.DISCORD);
        set(model, AlertSettingKeys.route(AlertService.DISCORD, AlertKind.SCRIPT_STALL), "true");
        set(model, AlertSettingKeys.route(AlertService.DISCORD, AlertKind.DAILY_SUMMARY), "true");
        set(model, AlertSettingKeys.route(AlertService.NTFY, AlertKind.CLIENT_CLOSED), "false");
    }

    private static void set(FixtureSettingsModel model, SettingKey<?> key, String text) {
        model.editRaw(key.name(), text);
    }
}
