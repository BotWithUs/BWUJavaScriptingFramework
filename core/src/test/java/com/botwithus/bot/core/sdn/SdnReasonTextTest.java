package com.botwithus.bot.core.sdn;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SdnReasonTextTest {

    private static final String NAME = "FightWithUs";
    private static final String RAY = "8d1f0c0ffee-LHR";
    private static final List<String> REASONS = List.of(SdnReasonText.NOT_OWNED, SdnReasonText.NOT_SIGNED_IN,
            SdnReasonText.NO_LINK, SdnReasonText.HEARTBEAT_REFUSED, SdnReasonText.SESSION_REJECTED,
            SdnReasonText.NOT_ENTITLED, SdnReasonText.NO_BUILD, SdnReasonText.UNSUPPORTED,
            SdnReasonText.CLOUDFLARE_CHALLENGE, SdnReasonText.SERVER_ERROR, SdnReasonText.NETWORK,
            SdnReasonText.TIMEOUT, SdnReasonText.HOST_NOT_ON_PIPE);

    private static String text(String reason) {
        return SdnReasonText.of(Optional.of(reason), NAME, Optional.of(RAY));
    }

    private static String generic() {
        return SdnReasonText.of(Optional.empty(), NAME, Optional.empty());
    }

    /** Every reason the launcher sends has words of its own, not the fallback. */
    @Test
    void everyReason_hasItsOwnText() {
        assertAll(REASONS.stream().map(r -> () -> assertNotEquals(generic(), text(r), r)));
    }

    /** "Subscription needed" appears for a real refusal of entitlement and for nothing else. */
    @Test
    void onlyNotEntitled_speaksOfASubscription() {
        assertTrue(text(SdnReasonText.NOT_ENTITLED).startsWith("Subscription needed"));
        assertAll(REASONS.stream().filter(r -> !r.equals(SdnReasonText.NOT_ENTITLED))
                .map(r -> () -> assertFalse(text(r).toLowerCase(Locale.ROOT).contains("subscri"), r)));
        assertFalse(generic().toLowerCase(Locale.ROOT).contains("subscri"));
    }

    @Test
    void anUnknownReason_andNoReason_getTheGenericText() {
        assertEquals(generic(), SdnReasonText.of(Optional.of("something_new"), NAME, Optional.empty()));
        assertTrue(generic().contains(NAME));
    }

    @Test
    void aCloudflareChallenge_quotesItsReference() {
        assertTrue(text(SdnReasonText.CLOUDFLARE_CHALLENGE).contains(RAY));
    }

    /** No text tells the user to check whether the launcher "is still running". */
    @Test
    void noText_blamesALauncherThatIsNotRunning() {
        List<String> all = List.of(SdnReasonText.NOT_CONNECTED_TEXT, SdnReasonText.TIMED_OUT_TEXT,
                SdnReasonText.FILE_ROUTE_SILENT_TEXT, generic());
        assertAll(all.stream().map(t -> () -> assertFalse(t.toLowerCase(Locale.ROOT).contains("still running"), t)));
    }
}
