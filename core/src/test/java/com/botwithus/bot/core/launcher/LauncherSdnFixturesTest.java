package com.botwithus.bot.core.launcher;

import com.botwithus.bot.core.sdn.SdnReasonText;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

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
    /** The ids, statuses and support reference the two fixtures carry, as their manifest entries list them. */
    private static final String PARTIAL_DELIVERED = "7";
    private static final String PARTIAL_FAILED = "12";
    private static final long HTTP_FORBIDDEN = 403;
    private static final long HTTP_NOT_FOUND = 404;
    private static final String RAY = "8d2f3a1b4c5d6e7f-AMS";

    private static final LauncherFixtures FIXTURES = LauncherFixtures.load();

    @Test
    void theRequest_asksForAPartialDelivery_andTheLegacyOneDoesNot() {
        Envelope request = Envelope.decode(FIXTURES.named(REQUEST).bytes());
        Envelope legacy = Envelope.decode(FIXTURES.named(LEGACY_REQUEST).bytes());

        assertTrue(WireValues.bool(request.body(), SdnWire.ALLOW_PARTIAL, false));
        assertFalse(WireValues.bool(legacy.body(), SdnWire.ALLOW_PARTIAL, false));
    }

    /** The values are the manifest's own for this fixture. */
    @Test
    void aPartialOffer_namesEachJarAndEachFailure() {
        Envelope response = Envelope.decode(FIXTURES.named(PARTIAL_OFFER).bytes());
        SdnBundleOffer offer = LauncherDecoder.sdnBundleOffer(response.body());

        assertEquals(Optional.of(List.of(PARTIAL_DELIVERED)), offer.delivered());
        assertEquals(List.of(new SdnScriptFailure(PARTIAL_FAILED, SdnReasonText.CLOUDFLARE_CHALLENGE,
                OptionalLong.of(HTTP_FORBIDDEN), Optional.of(RAY))), offer.failed());
    }

    /** The values are the manifest's own for this fixture. */
    @Test
    void aTypedRefusal_carriesItsReason() {
        Envelope response = Envelope.decode(FIXTURES.named(TYPED_REFUSAL).bytes());
        WireError error = response.error().orElseThrow();
        SdnRefusal refusal = SdnWire.refusal(error.detail());

        assertEquals(LauncherProtocol.ERROR_SDN_NOT_DELIVERED, error.code());
        assertEquals(Optional.of(SdnReasonText.CLOUDFLARE_CHALLENGE), refusal.reason(),
                "the error's detail carries a reason");
        assertEquals(Optional.of(PARTIAL_DELIVERED), refusal.scriptId());
        assertEquals(Optional.of(RAY), refusal.cfRay());
        assertEquals(OptionalLong.of(HTTP_FORBIDDEN), refusal.httpStatus());
        assertEquals(2, refusal.failed().size());
        assertEquals(new SdnScriptFailure(PARTIAL_FAILED, SdnReasonText.NO_BUILD, OptionalLong.of(HTTP_NOT_FOUND),
                Optional.empty()), refusal.failed().get(1));
    }
}
