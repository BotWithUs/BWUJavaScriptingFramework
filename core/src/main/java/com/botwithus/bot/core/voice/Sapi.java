package com.botwithus.bot.core.voice;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.StructLayout;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * The SAPI 5.4 contract this package binds to: class and interface ids, vtable slots,
 * constants and struct layouts, every one copied from the Windows SDK's {@code um/sapi.h}
 * (SDK 10.0.26100.0, {@code _SAPI_VER 0x054}).
 *
 * <p>A vtable slot is the method's position in the interface's C vtable struct
 * ({@code typedef struct ISpXxxVtbl}), counting {@code IUnknown}'s three methods and every
 * inherited interface's methods first. The struct layouts are for 64-bit Windows.</p>
 */
final class Sapi {

    // ── Class ids (sapi.h, "class DECLSPEC_UUID(...)" near the end) ──────────────────
    static final String CLSID_SP_INPROC_RECOGNIZER = "41B89B6B-9399-11D2-9623-00C04F8EE628";
    static final String CLSID_SP_OBJECT_TOKEN_CATEGORY = "A910187F-0C7A-45AC-92CC-59EDAFB77B53";
    static final String CLSID_SP_OBJECT_TOKEN = "EF411752-3736-4CB4-9C8C-8EF4CCB58EFE";
    static final String CLSID_SP_STREAM = "715D9C59-4442-11D2-9605-00C04F8EE628";

    // ── Interface ids (sapi.h, MIDL_INTERFACE(...) on each C++ declaration) ──────────
    static final String IID_ISP_RECOGNIZER = "C2B5F241-DAA0-4507-9E16-5A1EAA2B7A5C";
    static final String IID_ISP_OBJECT_TOKEN_CATEGORY = "2D3D3845-39AF-4850-BBF9-40B49780011D";
    static final String IID_ISP_OBJECT_TOKEN = "14056589-E16C-11D2-BB90-00C04F8EE6C0";
    static final String IID_ISP_STREAM = "12E3CCA9-7518-44C5-A5E7-BA5A79CB929E";

    // ── Token categories (sapi.h SPCAT_*) ─────────────────────────────────────────────
    static final String SPCAT_AUDIOIN = "HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Speech\\AudioInput";
    static final String SPCAT_RECOGNIZERS = "HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Speech\\Recognizers";

    // ── ISpRecognizer (ISpRecognizerVtbl): IUnknown 0-2, ISpProperties 3-6 ─────────────
    static final int RECOGNIZER_SET_INPUT = 9;
    static final int RECOGNIZER_CREATE_RECO_CONTEXT = 12;
    static final int RECOGNIZER_GET_STATUS = 18;

    // ── ISpRecoContext (ISpRecoContextVtbl): ISpNotifySource 3-9, ISpEventSource 10-12 ──
    static final int CONTEXT_SET_NOTIFY_WIN32_EVENT = 7;
    static final int CONTEXT_WAIT_FOR_NOTIFY_EVENT = 8;
    static final int CONTEXT_SET_INTEREST = 10;
    static final int CONTEXT_GET_EVENTS = 11;
    static final int CONTEXT_CREATE_GRAMMAR = 14;

    // ── ISpRecoGrammar (ISpRecoGrammarVtbl): ISpGrammarBuilder 3-10 ─────────────────────
    static final int GRAMMAR_RESET_GRAMMAR = 3;
    static final int GRAMMAR_GET_RULE = 4;
    static final int GRAMMAR_ADD_WORD_TRANSITION = 7;
    static final int GRAMMAR_COMMIT = 10;
    static final int GRAMMAR_SET_RULE_STATE = 18;

    // ── ISpRecoResult (ISpRecoResultVtbl): ISpPhrase 3-6 ───────────────────────────────
    static final int RESULT_GET_PHRASE = 3;

    // ── ISpObjectTokenCategory / ISpObjectToken: ISpDataKey 3-14 ───────────────────────
    static final int CATEGORY_SET_ID = 15;
    static final int CATEGORY_GET_DEFAULT_TOKEN_ID = 20;
    static final int TOKEN_SET_ID = 15;

    // ── ISpStream (ISpStreamVtbl): IStream 3-13, ISpStreamFormat 14 ────────────────────
    static final int STREAM_BIND_TO_FILE = 17;

    // ── Enumerations and flags ─────────────────────────────────────────────────────────
    /** {@code SPEI_RECOGNITION} (SPEVENTENUM). */
    static final int SPEI_RECOGNITION = 38;
    /** {@code SPFEI_FLAGCHECK}: {@code (1 << SPEI_RESERVED1(30)) | (1 << SPEI_RESERVED2(33))}. */
    static final long SPFEI_FLAGCHECK = (1L << 30) | (1L << 33);
    /** {@code SPET_LPARAM_IS_TOKEN} (SPEVENTLPARAMTYPE); 0 is undefined. */
    static final int SPET_LPARAM_IS_TOKEN = 1;
    static final int SPET_LPARAM_IS_OBJECT = 2;
    static final int SPET_LPARAM_IS_POINTER = 3;
    static final int SPET_LPARAM_IS_STRING = 4;
    /** {@code SPRAF_TopLevel} (SPCFGRULEATTRIBUTES). */
    static final int SPRAF_TOP_LEVEL = 1;
    /** {@code SPRAF_Active}. */
    static final int SPRAF_ACTIVE = 1 << 1;
    /** {@code SPWT_LEXICAL} (SPGRAMMARWORDTYPE). */
    static final int SPWT_LEXICAL = 1;
    /** {@code SPRS_ACTIVE} (SPRULESTATE). */
    static final int SPRS_ACTIVE = 1;
    /** {@code SPFM_OPEN_READONLY} (SPFILEMODE). */
    static final int SPFM_OPEN_READONLY = 0;

    // ── Structs ────────────────────────────────────────────────────────────────────────

    /** {@code SPEVENT}: two WORDs, then ULONG, ULONGLONG, WPARAM, LPARAM. 32 bytes. */
    static final StructLayout SPEVENT = MemoryLayout.structLayout(
            JAVA_SHORT.withName("eEventId"),
            JAVA_SHORT.withName("elParamType"),
            JAVA_INT.withName("ulStreamNum"),
            JAVA_LONG.withName("ullAudioStreamOffset"),
            JAVA_LONG.withName("wParam"),
            JAVA_LONG.withName("lParam"));
    static final long EVENT_ID = SPEVENT.byteOffset(groupElement("eEventId"));
    static final long EVENT_LPARAM_TYPE = SPEVENT.byteOffset(groupElement("elParamType"));
    static final long EVENT_LPARAM = SPEVENT.byteOffset(groupElement("lParam"));

    /** {@code SPAUDIOSTATUS}: long, ULONG, SPAUDIOSTATE, then two ULONGLONGs and two DWORDs. */
    static final StructLayout SPAUDIOSTATUS = MemoryLayout.structLayout(
            JAVA_INT.withName("cbFreeBuffSpace"),
            JAVA_INT.withName("cbNonBlockingIO"),
            JAVA_INT.withName("State"),
            MemoryLayout.paddingLayout(Integer.BYTES),
            JAVA_LONG.withName("CurSeekPos"),
            JAVA_LONG.withName("CurDevicePos"),
            JAVA_INT.withName("dwAudioLevel"),
            JAVA_INT.withName("dwReserved2"));

    /** Number of entries in {@code SPRECOGNIZERSTATUS.aLangID}. */
    static final int MAX_LANG_IDS = 20;
    /** The 16-byte {@code CLSID clsidEngine}, as raw bytes: only its size matters here. */
    private static final long CLSID_BYTES = 16;
    /** Pads {@code aLangID[20]} (40 bytes, ending at 116) to the next 8-byte boundary. */
    private static final long LANG_IDS_TAIL_PADDING = 4;

    /** {@code SPRECOGNIZERSTATUS}. 128 bytes. */
    static final StructLayout SPRECOGNIZERSTATUS = MemoryLayout.structLayout(
            SPAUDIOSTATUS.withName("AudioStatus"),
            JAVA_LONG.withName("ullRecognitionStreamPos"),
            JAVA_INT.withName("ulStreamNumber"),
            JAVA_INT.withName("ulNumActive"),
            MemoryLayout.sequenceLayout(CLSID_BYTES, JAVA_BYTE).withName("clsidEngine"),
            JAVA_INT.withName("cLangIDs"),
            MemoryLayout.sequenceLayout(MAX_LANG_IDS, JAVA_SHORT).withName("aLangID"),
            MemoryLayout.paddingLayout(LANG_IDS_TAIL_PADDING),
            JAVA_LONG.withName("ullRecognitionStreamTime"));
    static final long STATUS_LANG_ID_COUNT = SPRECOGNIZERSTATUS.byteOffset(groupElement("cLangIDs"));
    static final long STATUS_FIRST_LANG_ID = SPRECOGNIZERSTATUS.byteOffset(groupElement("aLangID"));

    /** {@code SPPHRASERULE}. 48 bytes. */
    static final StructLayout SPPHRASERULE = MemoryLayout.structLayout(
            ADDRESS.withName("pszName"),
            JAVA_INT.withName("ulId"),
            JAVA_INT.withName("ulFirstElement"),
            JAVA_INT.withName("ulCountOfElements"),
            MemoryLayout.paddingLayout(Integer.BYTES),
            ADDRESS.withName("pNextSibling"),
            ADDRESS.withName("pFirstChild"),
            JAVA_FLOAT.withName("SREngineConfidence"),
            JAVA_BYTE.withName("Confidence"),
            MemoryLayout.paddingLayout(Integer.BYTES - 1));

    /**
     * The leading fields of {@code SPPHRASE} (all versions start with {@code SPPHRASE_50}),
     * up to and including {@code Rule}: all this package reads.
     */
    static final StructLayout SPPHRASE_HEAD = MemoryLayout.structLayout(
            JAVA_INT.withName("cbSize"),
            JAVA_SHORT.withName("LangID"),
            JAVA_SHORT.withName("wHomophoneGroupId"),
            JAVA_LONG.withName("ullGrammarID"),
            JAVA_LONG.withName("ftStartTime"),
            JAVA_LONG.withName("ullAudioStreamPosition"),
            JAVA_INT.withName("ulAudioSizeBytes"),
            JAVA_INT.withName("ulRetainedSizeBytes"),
            JAVA_INT.withName("ulAudioSizeTime"),
            MemoryLayout.paddingLayout(Integer.BYTES),
            SPPHRASERULE.withName("Rule"));
    static final long PHRASE_GRAMMAR_ID = SPPHRASE_HEAD.byteOffset(groupElement("ullGrammarID"));
    static final long PHRASE_RULE_ID = SPPHRASE_HEAD.byteOffset(
            groupElement("Rule"), groupElement("ulId"));
    static final long PHRASE_RULE_CONFIDENCE = SPPHRASE_HEAD.byteOffset(
            groupElement("Rule"), groupElement("SREngineConfidence"));

    private Sapi() {
    }

    /** {@code SPFEI(ord)}: the interest bit for one event, plus the required check bits. */
    static long interestIn(int eventOrdinal) {
        return (1L << eventOrdinal) | SPFEI_FLAGCHECK;
    }
}
