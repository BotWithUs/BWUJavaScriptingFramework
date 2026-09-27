package com.botwithus.bot.cli.alerts;

/** The state an Integrations service card shows next to the service's name. */
public enum ServiceStatus {

    /** The service is switched off. */
    OFF("Off"),
    /** On, and nothing has been sent to it since the host started. */
    NOT_TESTED("Not tested"),
    /** The last send (a test or an alert) was delivered. */
    WORKING("Working"),
    /** The last send failed. */
    FAILED("Last send failed"),
    /** A test send is on its way. */
    SENDING("Sending test…");

    private final String label;

    ServiceStatus(String label) {
        this.label = label;
    }

    /** The text to show, e.g. {@code Last send failed}. */
    public String label() {
        return label;
    }
}
