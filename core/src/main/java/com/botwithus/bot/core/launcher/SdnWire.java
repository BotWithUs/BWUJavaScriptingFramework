package com.botwithus.bot.core.launcher;

import com.botwithus.msgpack.MsgValue;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The one place that knows where the launcher puts the typed SDN fields: the
 * reason on an {@code sdn_not_delivered} error, and the partial-delivery lists in
 * the {@code sdn.bundle} offer. A field this host does not find reads as absent,
 * which is what a launcher that predates them sends.
 */
final class SdnWire {

    /** The request flag that asks for a partial delivery rather than all or nothing. */
    static final String ALLOW_PARTIAL = "allowPartial";

    private static final String REASON = "reason";
    private static final String SCRIPT_ID = "scriptId";
    private static final String HTTP_STATUS = "httpStatus";
    private static final String CF_RAY = "cfRay";
    private static final String FAILED = "failed";
    private static final String DELIVERED = "delivered";

    private SdnWire() {
    }

    /** @return the refusal an {@code sdn_not_delivered} error's {@code detail} map describes */
    static SdnRefusal refusal(MsgValue detail) {
        return new SdnRefusal(WireValues.string(detail, REASON), WireValues.string(detail, SCRIPT_ID),
                WireValues.integer(detail, HTTP_STATUS), WireValues.string(detail, CF_RAY), failed(detail));
    }

    /**
     * @return the offer's {@code delivered} ids, in jar order; empty when the
     *         launcher did not send the field, which an older one never does
     */
    static Optional<List<String>> delivered(MsgValue offer) {
        return WireValues.field(offer, DELIVERED).flatMap(MsgValue::arrayValue)
                .map(ids -> ids.values().stream()
                        .map(id -> id.stringValue().orElse(""))
                        .toList());
    }

    /** @return the {@code failed} list in {@code map}, an offer or an error's detail; empty when absent */
    static List<SdnScriptFailure> failed(MsgValue map) {
        return WireValues.array(map, FAILED).stream().filter(WireValues::isMap)
                .map(f -> new SdnScriptFailure(WireValues.string(f, SCRIPT_ID, ""), WireValues.string(f, REASON, ""),
                        WireValues.integer(f, HTTP_STATUS), WireValues.string(f, CF_RAY)))
                .toList();
    }

    /**
     * @return why {@code delivered} cannot pair the jars with what was asked for, or
     *         empty when it can: each id once, and each one requested
     */
    static Optional<String> checkDelivered(List<String> delivered, List<String> requested) {
        Set<String> seen = new HashSet<>();
        for (String id : delivered) {
            if (!seen.add(id)) {
                return Optional.of("the launcher listed script " + id + " twice");
            }
            if (!requested.contains(id)) {
                return Optional.of("the launcher delivered script " + id + ", which was not asked for");
            }
        }
        return Optional.empty();
    }
}
