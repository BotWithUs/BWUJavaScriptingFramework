package com.botwithus.bot.core.voice;

import org.junit.jupiter.api.Test;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The pure half of the SAPI binding: GUID byte order, HRESULT checks, and the struct
 * layouts' sizes and offsets against what {@code sapi.h} gives on 64-bit Windows (worked by
 * hand from the field lists, with natural alignment).
 */
class SapiBindingTest {

    @Test
    void guidBytes_isWindowsMixedEndianLayout() {
        byte[] expected = {
                0x41, (byte) 0xF2, (byte) 0xB5, (byte) 0xC2,   // Data1 C2B5F241, little-endian
                (byte) 0xA0, (byte) 0xDA,                       // Data2 DAA0
                0x07, 0x45,                                     // Data3 4507
                (byte) 0x9E, 0x16, 0x5A, 0x1E, (byte) 0xAA, 0x2B, 0x7A, 0x5C}; // Data4 as written

        assertArrayEquals(expected, Com.guidBytes(Sapi.IID_ISP_RECOGNIZER));
    }

    @Test
    void check_failureCodes_throwAndSuccessCodesPassThrough() {
        assertEquals(Com.S_OK, Com.check(Com.S_OK, "ok"));
        assertEquals(Com.S_FALSE, Com.check(Com.S_FALSE, "false"));
        SapiException e = assertThrows(SapiException.class, () -> Com.check(0x8004503A, "SetId"));

        assertEquals("SetId failed: HRESULT 0x8004503a", e.getMessage());
    }

    @Test
    void spevent_is32BytesWithLParamLast() {
        assertEquals(32, Sapi.SPEVENT.byteSize());
        assertEquals(0, Sapi.EVENT_ID);
        assertEquals(2, Sapi.EVENT_LPARAM_TYPE);
        assertEquals(24, Sapi.EVENT_LPARAM);
    }

    @Test
    void recognizerStatus_is128BytesWithLanguagesAfterTheEngineClsid() {
        assertEquals(40, Sapi.SPAUDIOSTATUS.byteSize());
        assertEquals(128, Sapi.SPRECOGNIZERSTATUS.byteSize());
        assertEquals(72, Sapi.STATUS_LANG_ID_COUNT);
        assertEquals(76, Sapi.STATUS_FIRST_LANG_ID);
        assertEquals(120, Sapi.SPRECOGNIZERSTATUS.byteOffset(groupElement("ullRecognitionStreamTime")));
    }

    @Test
    void phraseHead_ruleStartsAt48WithIdAndConfidenceInside() {
        assertEquals(48, Sapi.SPPHRASERULE.byteSize());
        assertEquals(48, Sapi.SPPHRASE_HEAD.byteOffset(groupElement("Rule")));
        assertEquals(8, Sapi.PHRASE_GRAMMAR_ID);
        assertEquals(56, Sapi.PHRASE_RULE_ID);
        assertEquals(88, Sapi.PHRASE_RULE_CONFIDENCE);
    }

    @Test
    void interestIn_recognition_setsItsBitAndTheTwoCheckBits() {
        assertEquals((1L << 38) | (1L << 33) | (1L << 30), Sapi.interestIn(Sapi.SPEI_RECOGNITION));
    }
}
