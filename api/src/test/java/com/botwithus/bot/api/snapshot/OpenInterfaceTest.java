package com.botwithus.bot.api.snapshot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the v23 {@code openIfaceFlags} byte decode. The constants are literals from the
 * producer's header, deliberately not recomputed, so a bit moving on either side of the wire
 * makes this disagree.
 */
class OpenInterfaceTest {

    private static final int IFACE = 517;

    @Test
    void bitLayout_matchesTheProducerHeader() {
        assertEquals(0x07, OpenInterface.TYPE_MASK, "kOpenIfaceTypeMask");
        assertEquals(0x07, OpenInterface.TYPE_UNKNOWN, "kOpenIfaceTypeUnknown");
        assertEquals(0x08, OpenInterface.FLAG_CLIENT_OPENED, "kOpenIfaceFlagClientOpened");
        assertEquals(0, OpenInterface.TYPE_MODAL);
        assertEquals(1, OpenInterface.TYPE_OVERLAY);
        assertEquals(3, OpenInterface.TYPE_CLIENT_CHILD);
    }

    @ParameterizedTest
    @CsvSource({
        // flags, type, clientOpened, modal, overlay
        "0x00, 0, false, true,  false",
        "0x01, 1, false, false, true",
        "0x03, 3, false, false, false",
        "0x0B, 3, true,  false, false",
        "0x08, 0, true,  true,  false",
        "0x07, 7, false, false, false",
    })
    void fromWire_decodesTypeAndClientOpened(String flags, int type, boolean clientOpened,
                                             boolean modal, boolean overlay) {
        OpenInterface open = OpenInterface.fromWire(IFACE, Integer.decode(flags));

        assertEquals(IFACE, open.id());
        assertEquals(type, open.type());
        assertEquals(clientOpened, open.clientOpened());
        assertEquals(modal, open.isModal());
        assertEquals(overlay, open.isOverlay());
    }

    @Test
    void fromWire_typeSeven_isUnknown() {
        assertTrue(OpenInterface.fromWire(IFACE, OpenInterface.TYPE_UNKNOWN).isTypeUnknown());
        assertFalse(OpenInterface.fromWire(IFACE, OpenInterface.TYPE_MODAL).isTypeUnknown());
    }

    /** Reserved bits 4-7 must not leak into the type or the client-opened bit. */
    @ParameterizedTest
    @ValueSource(ints = {0x10, 0x20, 0x40, 0x80, 0xF0})
    void fromWire_reservedBits_areIgnored(int reserved) {
        assertEquals(OpenInterface.fromWire(IFACE, 0x00), OpenInterface.fromWire(IFACE, reserved));
        assertEquals(OpenInterface.fromWire(IFACE, 0x0B),
                OpenInterface.fromWire(IFACE, reserved | 0x0B));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 8, 255})
    void constructor_typeOutsideThreeBits_throws(int type) {
        assertThrows(IllegalArgumentException.class, () -> new OpenInterface(IFACE, type, false));
    }
}
