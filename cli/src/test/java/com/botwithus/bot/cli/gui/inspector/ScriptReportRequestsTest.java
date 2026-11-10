package com.botwithus.bot.cli.gui.inspector;

import com.botwithus.bot.cli.gui.inspector.InspectorSubject.ClientScript;
import com.botwithus.bot.cli.gui.inspector.InspectorSubject.ManagementScript;
import com.botwithus.bot.cli.report.ReportSubject;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Carrying "Report a problem" from a script's UI to the host's dialog: only a
 * client script on a named connection can be reported, only the newest request
 * in a frame is kept, and a request made on another thread is delivered on the
 * render thread that next calls deliver.
 */
class ScriptReportRequestsTest {

    private static final String PIPE = "BotWithUs_1";
    private static final String SCRIPT = "Agility";
    private static final String OTHER_SCRIPT = "Fishing";
    private static final String CLIENT_TAG = "client-thread";
    private static final String SCRIPT_TAG = "script-thread";
    private static final int NO_REQUESTS = 0;
    private static final int ONE_REQUEST = 1;

    private final ScriptReportRequests requests = new ScriptReportRequests();
    private final List<ReportSubject> opened = new ArrayList<>();

    @Test
    void beforeConnect_aRequestIsRefused_andNothingOpens() {
        assertFalse(requests.request(new ClientScript(PIPE, SCRIPT)));

        requests.connect(opened::add);
        requests.deliver();

        assertEquals(NO_REQUESTS, opened.size());
    }

    @Test
    void aClientScript_opensOnce_withNothingPreselected() {
        requests.connect(opened::add);

        assertTrue(requests.request(new ClientScript(PIPE, SCRIPT)));
        assertEquals(NO_REQUESTS, opened.size(), "deliver opens it, not request");

        requests.deliver();
        requests.deliver();

        assertEquals(List.of(new ReportSubject(PIPE, SCRIPT)), opened);
    }

    @Test
    void twoRequestsInOneFrame_openTheNewest() {
        requests.connect(opened::add);

        requests.request(new ClientScript(PIPE, SCRIPT));
        requests.request(new ClientScript(PIPE, OTHER_SCRIPT));
        requests.deliver();

        assertEquals(List.of(new ReportSubject(PIPE, OTHER_SCRIPT)), opened);
    }

    @Test
    void aManagementScript_isRefused() {
        requests.connect(opened::add);

        assertFalse(requests.request(new ManagementScript("TestPilot")));

        requests.deliver();

        assertEquals(NO_REQUESTS, opened.size());
    }

    @Test
    void aClientScriptWithNoConnection_isRefused() {
        requests.connect(opened::add);

        assertFalse(requests.request(new ClientScript("", SCRIPT)));

        requests.deliver();

        assertEquals(NO_REQUESTS, opened.size());
    }

    @Test
    void aRequestFromAnotherThread_isDeliveredOnTheNext() throws InterruptedException {
        requests.connect(opened::add);
        AtomicBoolean asked = new AtomicBoolean();
        Thread scriptThread = Thread.ofPlatform().name(SCRIPT_TAG)
                .start(() -> asked.set(requests.request(new ClientScript(PIPE, SCRIPT))));

        scriptThread.join();

        assertTrue(asked.get(), "request is safe from any thread");
        assertEquals(NO_REQUESTS, opened.size(), CLIENT_TAG);
        requests.deliver();
        assertEquals(ONE_REQUEST, opened.size());
    }
}
