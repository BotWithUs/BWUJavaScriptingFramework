package com.botwithus.bot.cli.gui.notify;

import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.gui.notify.Notification.Kind;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The overlay's own rules, headless: posts wait for the render thread, a
 * client has one connection toast, errors stay, at most three show.
 */
class NotificationOverlayTest {

    private static final Duration LIFETIME = Duration.ofSeconds(6);
    private static final Duration PAST_SLIDE = Duration.ofSeconds(1);
    private static final ClientKey OAK = ClientKey.pipe("BotWithUs_1");
    private static final ClientKey FERN = ClientKey.pipe("BotWithUs_2");

    private final MutableClock clock = new MutableClock();
    private final NotificationOverlay overlay = new NotificationOverlay(clock);

    @Test
    void post_isShownOnlyOnceTheRenderThreadUpdates() {
        overlay.post(timed(Kind.CONNECTION_LOST, OAK, "lost"));

        assertTrue(overlay.active().isEmpty(), "a post from the event thread must wait for the render thread");
        overlay.update();
        assertEquals(1, overlay.active().size());
    }

    @Test
    void timedToast_goesAfterItsLifetime() {
        overlay.post(timed(Kind.RECONNECTED, OAK, "back"));
        overlay.update();

        clock.advance(LIFETIME.plus(PAST_SLIDE));
        overlay.update();

        assertTrue(overlay.active().isEmpty(), "an expired toast must be culled");
    }

    @Test
    void errorToast_staysUntilClosed() {
        overlay.post(sticky(Kind.SCRIPT_CRASHED, OAK, "crashed"));
        overlay.update();

        clock.advance(Duration.ofHours(1));
        overlay.update();

        assertEquals(1, overlay.active().size());
        assertEquals(Optional.empty(), overlay.active().getFirst().expiresAt());
    }

    @Test
    void retry_updatesTheLostToastInPlace() {
        overlay.post(timed(Kind.CONNECTION_LOST, OAK, "lost"));
        overlay.update();
        Notification lost = overlay.active().getFirst();
        clock.advance(PAST_SLIDE);

        overlay.post(timed(Kind.RECONNECTING, OAK, "attempt 2").updateOnly());
        overlay.update();

        Notification now = overlay.active().getFirst();
        assertAll(
                () -> assertEquals(1, overlay.active().size()),
                () -> assertEquals(Kind.RECONNECTING, now.kind()),
                () -> assertEquals("attempt 2", now.message()),
                () -> assertEquals(lost.id(), now.id(), "updated in place, not a new toast"),
                () -> assertEquals(lost.createdAt(), now.createdAt(), "no second slide-in"));
    }

    @Test
    void recovery_replacesTheOutageToast() {
        overlay.post(timed(Kind.RECONNECTING, OAK, "attempt 1"));
        overlay.update();

        overlay.post(timed(Kind.RECONNECTED, OAK, "back"));
        overlay.update();

        assertEquals(List.of(Kind.RECONNECTED), kinds());
    }

    @Test
    void connectionToasts_areOnePerClient_notOneOverall() {
        overlay.post(timed(Kind.CONNECTION_LOST, OAK, "oak lost"));
        overlay.post(timed(Kind.CONNECTION_LOST, FERN, "fern lost"));
        overlay.update();

        assertEquals(2, overlay.active().size());
    }

    @Test
    void updateOnly_withNothingToUpdate_isDropped() {
        overlay.post(timed(Kind.RECONNECTING, OAK, "attempt 7").updateOnly());
        overlay.update();

        assertTrue(overlay.active().isEmpty());
    }

    @Test
    void withdraw_slidesTheClientsConnectionToastOut() {
        overlay.post(sticky(Kind.GAVE_UP, OAK, "gave up"));
        overlay.post(sticky(Kind.SCRIPT_CRASHED, OAK, "crashed"));
        overlay.update();

        overlay.withdraw(OAK);
        overlay.update();
        clock.advance(PAST_SLIDE);
        overlay.update();

        assertEquals(List.of(Kind.SCRIPT_CRASHED), kinds(), "only the connection toast is withdrawn");
    }

    @Test
    void aFourthToast_pushesTheOldestOut() {
        overlay.post(sticky(Kind.SCRIPT_CRASHED, OAK, "first"));
        overlay.post(sticky(Kind.SCRIPT_CRASHED, OAK, "second"));
        overlay.post(sticky(Kind.SCRIPT_CRASHED, OAK, "third"));
        overlay.post(sticky(Kind.SCRIPT_CRASHED, OAK, "fourth"));
        overlay.update();

        assertEquals(List.of("second", "third", "fourth"),
                overlay.active().stream().map(Notification::message).toList());
    }

    private List<Kind> kinds() {
        return overlay.active().stream().map(Notification::kind).toList();
    }

    private static Toast timed(Kind kind, ClientKey client, String message) {
        return new Toast(kind, "title", message, Optional.of(client), Optional.of(LIFETIME), true);
    }

    private static Toast sticky(Kind kind, ClientKey client, String message) {
        return new Toast(kind, "title", message, Optional.of(client), Optional.empty(), true);
    }
}
