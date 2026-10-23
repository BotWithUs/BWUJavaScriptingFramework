package com.botwithus.bot.core.launcher;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The typed SDN fields decoded from the launcher's own golden fixtures, so the
 * field paths in {@link SdnWire} are held to what the launcher actually sends.
 *
 * <p>Disabled until the fixtures exist in the vendored copy: the launcher adds
 * them with its typed-reasons change, and this directory is re-vendored from that
 * merge. The fixture names are the expected ones; correct them, if they differ,
 * when re-vendoring, and remove {@link Disabled}.
 */
@Disabled("pending re-vendoring from the launcher merge that adds the typed SDN fixtures")
class LauncherSdnFixturesTest {

    private static final String PARTIAL_OFFER = "sdn.bundle.resp.partial";
    private static final String TYPED_REFUSAL = "sdn.bundle.err.not_delivered";

    private static final LauncherFixtures FIXTURES = LauncherFixtures.load();

    @Test
    void theRequest_asksForAPartialDelivery() {
        Envelope request = Envelope.decode(FIXTURES.named("sdn.bundle.req").bytes());

        assertTrue(WireValues.bool(request.body(), SdnWire.ALLOW_PARTIAL, false));
    }

    @Test
    void aPartialOffer_namesEachJarAndEachFailure() {
        Envelope response = Envelope.decode(FIXTURES.named(PARTIAL_OFFER).bytes());
        SdnBundleOffer offer = LauncherDecoder.sdnBundleOffer(response.body());

        Optional<List<String>> delivered = offer.delivered();
        assertTrue(delivered.isPresent(), "the offer carries delivered");
        assertFalse(offer.failed().isEmpty(), "the fixture carries at least one failure");
        assertTrue(offer.failed().stream().noneMatch(f -> f.scriptId().isEmpty() || f.reason().isEmpty()));
    }

    @Test
    void aTypedRefusal_carriesItsReason() {
        Envelope response = Envelope.decode(FIXTURES.named(TYPED_REFUSAL).bytes());
        WireError error = response.error().orElseThrow();
        SdnRefusal refusal = SdnWire.refusal(error.detail());

        assertEquals(LauncherProtocol.ERROR_SDN_NOT_DELIVERED, error.code());
        assertTrue(refusal.reason().isPresent(), "the error's detail carries a reason");
    }
}
