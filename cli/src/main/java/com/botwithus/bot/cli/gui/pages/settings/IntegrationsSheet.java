package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.cli.alerts.Integrations;
import com.botwithus.bot.cli.alerts.ServiceStatus;
import com.botwithus.bot.cli.alerts.ServiceView;
import com.botwithus.bot.cli.settings.AlertSettingKeys;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.SettingKey;
import com.botwithus.bot.core.alerts.AlertKind;
import com.botwithus.bot.core.alerts.AlertService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Builds the Integrations section: a card per alert service, the event grid,
 * and the burst, quiet-hours and daily-summary rows. Cards come from the alert
 * back end's {@link ServiceView}s, which are cheap to read every frame; everything
 * else is a plain {@code alerts.*} setting. No secret is ever part of what this
 * builds: a card only says whether one is saved.
 */
public final class IntegrationsSheet {

    /** Shown when the credential store could not be opened. */
    static final String SESSION_ONLY = "Windows Credential Manager could not be opened, so webhook URLs "
            + "and tokens are kept only until the host closes. Enter them again after a restart.";
    /** Shown when the alert back end is not running. */
    static final String NOT_RUNNING = "Alerts did not start in this session, so services cannot be set up "
            + "or tested here. The settings below still save.";
    static final String QUIET_LABEL = "Quiet hours";
    /** The back end drops a quiet-hours alert rather than holding it, so the copy says "mute". */
    static final String QUIET_DESCRIPTION = "Mute everything except crashes between these times "
            + "(24-hour, local time). Muted alerts are not sent later.";

    private static final String AT = ", at ";
    private static final String GRID = "Event grid · ";
    /** Where each {@code alerts.*} key that is not a plain row is edited, for the All config keys table. */
    private static final Map<String, String> SHOWN_AS = shownAsTable();

    private IntegrationsSheet() {
    }

    /**
     * The section's items in page order.
     *
     * @param keyRow builds the row for a placed setting, as the other sections' rows are built
     */
    public static List<SettingsItem> items(HostSettings settings, Optional<Integrations> integrations,
                                           Function<Placement, SettingsItem> keyRow) {
        List<SettingsItem> items = new ArrayList<>();
        if (integrations.isEmpty()) {
            items.add(new SettingsItem.Notice(NOT_RUNNING, true));
        }
        integrations.ifPresent(live -> {
            if (!live.isPersistent()) {
                items.add(new SettingsItem.Notice(SESSION_ONLY, true));
            }
            for (ServiceView view : live.services()) {
                items.add(card(view, settings));
            }
        });
        items.add(grid(settings));
        items.add(keyRow.apply(placed(AlertSettingKeys.BURST_SECONDS)));
        items.add(new SettingsItem.QuietHours(QUIET_LABEL, QUIET_DESCRIPTION,
                settings.get(AlertSettingKeys.QUIET_ENABLED), settings.get(AlertSettingKeys.QUIET_FROM),
                settings.get(AlertSettingKeys.QUIET_TO)));
        items.add(keyRow.apply(placed(AlertSettingKeys.SUMMARY_AT)));
        return items;
    }

    /**
     * The card for one service as the back end reports it. While a test is on its
     * way the card says so, its button is disabled and the previous result is
     * hidden, so the line that appears next is the test's own.
     */
    static SettingsItem.ServiceCard card(ServiceView view, HostSettings settings) {
        AlertService service = view.service();
        StatusChip chip = StatusChip.of(view.status());
        boolean isSending = view.status() == ServiceStatus.SENDING;
        Optional<ResultLine> result = isSending ? Optional.empty()
                : view.lastLine().map(line -> new ResultLine(line, chip.tone()));
        return new SettingsItem.ServiceCard(service, ServiceCopy.description(service),
                AlertSettingKeys.enabled(service).name(), view.isEnabled(), chip, fields(view, settings),
                ServiceCopy.hint(service), result, !isSending);
    }

    /** Rows are alert kinds, columns are services; a service that is off has its column greyed. */
    static SettingsItem.EventGrid grid(HostSettings settings) {
        List<GridColumn> columns = new ArrayList<>();
        for (AlertService service : AlertService.values()) {
            columns.add(new GridColumn(service, settings.get(AlertSettingKeys.enabled(service))));
        }
        List<GridRow> rows = new ArrayList<>();
        for (AlertKind kind : AlertKind.values()) {
            List<GridRow.Cell> cells = new ArrayList<>();
            for (GridColumn column : columns) {
                SettingKey<Boolean> route = AlertSettingKeys.route(column.service(), kind);
                cells.add(new GridRow.Cell(route.name(), settings.get(route), column.isEnabled()));
            }
            rows.add(new GridRow(kind, kind.label(), detail(kind, settings), cells));
        }
        return new SettingsItem.EventGrid(columns, rows);
    }

    private static List<CardField> fields(ServiceView view, HostSettings settings) {
        AlertService service = view.service();
        CardField secret = new CardField.SecretBox(service, service.secretLabel(), view.hasSecret(),
                service.isSecretOptional(), ServiceCopy.secretPlaceholder(service));
        return switch (service) {
            case NTFY -> List.of(
                    setting(settings, AlertSettingKeys.NTFY_SERVER, "Server", ServiceCopy.NTFY_SERVER_PLACEHOLDER),
                    setting(settings, AlertSettingKeys.NTFY_TOPIC, "Topic", ServiceCopy.NTFY_TOPIC_PLACEHOLDER),
                    secret);
            case SLACK -> List.of(secret);
            case DISCORD -> List.of(secret, new CardField.Toggle(AlertSettingKeys.DISCORD_MENTION_HERE.name(),
                    ServiceCopy.MENTION_LABEL, settings.get(AlertSettingKeys.DISCORD_MENTION_HERE)));
        };
    }

    private static CardField setting(HostSettings settings, SettingKey<String> key, String label, String placeholder) {
        return new CardField.Setting(key.name(), label, settings.get(key), placeholder);
    }

    /** The row's second line; the daily summary's says when it is sent. */
    private static String detail(AlertKind kind, HostSettings settings) {
        if (kind != AlertKind.DAILY_SUMMARY) {
            return kind.detail();
        }
        return kind.detail() + AT + settings.get(AlertSettingKeys.SUMMARY_AT);
    }

    /**
     * Where the section edits {@code keyName} when it is not one of its plain rows,
     * e.g. {@code Event grid · Discord}; empty for any other key.
     */
    static Optional<String> shownAs(String keyName) {
        return Optional.ofNullable(SHOWN_AS.get(keyName));
    }

    private static Map<String, String> shownAsTable() {
        Map<String, String> table = new HashMap<>();
        for (AlertService service : AlertService.values()) {
            table.put(AlertSettingKeys.enabled(service).name(), service.label() + " switch");
            for (AlertKind kind : AlertKind.values()) {
                table.put(AlertSettingKeys.route(service, kind).name(), GRID + service.label());
            }
        }
        table.put(AlertSettingKeys.NTFY_SERVER.name(), "ntfy · Server");
        table.put(AlertSettingKeys.NTFY_TOPIC.name(), "ntfy · Topic");
        table.put(AlertSettingKeys.DISCORD_MENTION_HERE.name(), "Discord · " + ServiceCopy.MENTION_LABEL);
        for (SettingKey<?> key : List.of(AlertSettingKeys.QUIET_ENABLED, AlertSettingKeys.QUIET_FROM,
                AlertSettingKeys.QUIET_TO)) {
            table.put(key.name(), QUIET_LABEL);
        }
        return Map.copyOf(table);
    }

    private static Placement placed(SettingKey<?> key) {
        return SettingsLayout.find(key.name())
                .orElseThrow(() -> new IllegalStateException(key.name() + " has no row in the Integrations section"));
    }
}
