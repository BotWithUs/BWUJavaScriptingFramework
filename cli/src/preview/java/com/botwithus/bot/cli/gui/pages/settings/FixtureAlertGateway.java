package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.core.alerts.AlertDelivery;
import com.botwithus.bot.core.alerts.AlertGateway;
import com.botwithus.bot.core.alerts.AlertService;
import com.botwithus.bot.core.alerts.DeliverReply;
import com.botwithus.bot.core.alerts.IntegrationChange;
import com.botwithus.bot.core.alerts.IntegrationReply;
import com.botwithus.bot.core.alerts.IntegrationState;
import com.botwithus.bot.core.alerts.ServiceState;
import com.botwithus.bot.core.alerts.TestReply;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * DEV ONLY. A stand-in launcher for the preview: it keeps each service's set-up in
 * memory, answers a change the way the launcher words a refusal, queues a test and
 * never reports on it, and sends nothing, ever.
 */
final class FixtureAlertGateway implements AlertGateway {

    private static final String HTTPS_REQUIRED = "The webhook URL must start with https://";
    private static final String HTTPS = "https://";
    private static final String SECRET = "secret";
    private static final String NTFY_SERVER = "https://ntfy.sh";
    private static final String TOKEN_HINT = "************";
    private static final long TEST_ID = 1L;

    private final Map<AlertService, IntegrationState> setups = new EnumMap<>(AlertService.class);
    private boolean isAvailable = true;

    FixtureAlertGateway() {
        for (AlertService service : AlertService.values()) {
            setups.put(service, new IntegrationState(service, false, Optional.empty(), false,
                    service == AlertService.NTFY ? NTFY_SERVER : "", ""));
        }
    }

    /** As if the connected launcher predated alerts. */
    void predateAlerts() {
        isAvailable = false;
    }

    void put(IntegrationState state) {
        setups.put(state.service(), state);
    }

    IntegrationState setup(AlertService service) {
        return setups.get(service);
    }

    @Override
    public boolean isAvailable() {
        return isAvailable;
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public DeliverReply deliver(AlertDelivery delivery) {
        return new DeliverReply.Unavailable("the preview never sends");
    }

    @Override
    public Optional<List<IntegrationState>> integrations() {
        return isAvailable ? Optional.of(List.copyOf(setups.values())) : Optional.empty();
    }

    @Override
    public IntegrationReply change(IntegrationChange change) {
        if (!isAvailable) {
            return new IntegrationReply.Unavailable(NEEDS_NEWER_LAUNCHER);
        }
        AlertService service = change.service();
        boolean isUrl = service != AlertService.NTFY;
        if (isUrl && change.secret().filter(s -> !s.reveal().startsWith(HTTPS)).isPresent()) {
            return new IntegrationReply.Refused(Optional.of(SECRET), HTTPS_REQUIRED);
        }
        IntegrationState now = setups.get(service);
        boolean hasSecret = change.secret().isPresent() || (now.hasSecret() && !change.isClearingSecret());
        Optional<String> hint = hasSecret ? Optional.of(isUrl ? hintOf(change, now) : TOKEN_HINT) : Optional.empty();
        IntegrationState next = new IntegrationState(service, hasSecret, hint,
                change.isMentioningHere().orElse(now.isMentioningHere()), change.server().orElse(now.server()),
                change.topic().orElse(now.topic()));
        setups.put(service, next);
        return new IntegrationReply.Updated(next);
    }

    @Override
    public TestReply sendTest(AlertService service) {
        return isAvailable ? new TestReply.Queued(TEST_ID) : new TestReply.Unavailable(NEEDS_NEWER_LAUNCHER);
    }

    @Override
    public Optional<List<ServiceState>> status() {
        return Optional.empty();
    }

    @Override
    public Runnable addListener(Listener listener) {
        return () -> { };
    }

    private static String hintOf(IntegrationChange change, IntegrationState now) {
        return change.secret().map(s -> hostOf(s.reveal()) + "/…").orElse(now.secretHint().orElse(""));
    }

    private static String hostOf(String url) {
        String rest = url.substring(HTTPS.length());
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }
}
