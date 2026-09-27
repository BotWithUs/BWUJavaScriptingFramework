package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.core.alerts.AlertService;

import java.util.Objects;

/**
 * One service's column of the Integrations event grid.
 *
 * @param isEnabled whether the service is switched on; a column that is off is greyed and cannot be ticked
 */
public record GridColumn(AlertService service, boolean isEnabled) {

    public GridColumn {
        Objects.requireNonNull(service, "service");
    }

    /** The heading: the service's name. */
    public String label() {
        return service.label();
    }
}
