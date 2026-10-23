package com.botwithus.bot.core.launcher;

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
 * <p>The fixtures are vendored from the launcher's merge that added typed SDN
 * reasons: {@code sdn.bundle.req} (with {@code allowPartial}),
 * {@code sdn.bundle.req.legacy} (without it, which this host never sends),
 * {@code sdn.bundle.resp.partial} and {@code sdn.bundle.error.not_delivered}. Chunk
 * errors stay an untyped {@code sdn_not_delivered}.
 */
class LauncherSdnFixturesTest {

    /** The launcher's manifest names; each is its file name less {@code .msgpack}. */
    private static final String REQUEST = "sdn.bundle.req";
    private static final String LEGACY_REQUEST = "sdn.bundle.req.legacy";
    private static final String PARTIAL_OFFER = "sdn.bundle.resp.partial";
    private static final String TYPED_REFUSAL = "sdn.bundle.error.not_delivered";

    private static final LauncherFixtures FIXTURES = LauncherFixtures.load();

    @Test
    void theRequest_asksForAPartialDelivery_andTheLegacyOneDoesNot() {
        Envelope request = Envelope.decode(FIXTURES.named(REQUEST).bytes());
        Envelope legacy = Envelope.decode(FIXTURES.named(LEGACY_REQUEST).bytes());

        assertTrue(WireValues.bool(request.body(), SdnWire.ALLOW_PARTIAL, false));
        assertFalse(WireValues.bool(legacy.body(), SdnWire.ALLOW_PARTIAL, false));
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
