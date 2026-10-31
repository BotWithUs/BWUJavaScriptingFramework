package com.botwithus.bot.core.voice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * {@link RecognizerBackend} over the speech engine Windows ships, through SAPI 5.4's COM
 * interfaces and the Foreign Function &amp; Memory API. No libraries, no models.
 *
 * <p>Uses the in-process recogniser ({@code SpInprocRecognizer}), not the shared one, so
 * the Windows Speech Recognition UI never appears and no system-wide commands are active.
 * The grammar is built in memory with {@code ISpGrammarBuilder}: one top-level rule per
 * phrase, so a result names the phrase by rule id. Recognition events are collected with
 * {@code SetNotifyWin32Event} / {@code WaitForNotifyEvent}, which needs no message pump, so
 * the engine thread joins the multithreaded apartment.</p>
 *
 * <p>Every method runs on the {@link VoiceInputService} engine thread (see
 * {@link RecognizerBackend}); none of the fields is shared with another thread.</p>
 */
final class SapiRecognizer implements RecognizerBackend {

    private static final Logger log = LoggerFactory.getLogger(SapiRecognizer.class);

    private static final String RULE_NAME_PREFIX = "phrase";
    private static final String WORD_SEPARATORS = " ";
    private static final float WORD_WEIGHT = 1.0f;
    /** Events fetched per {@code GetEvents} call. */
    private static final int EVENT_BATCH = 1;

    private final AudioSource source;
    /** Bound on first use, on the engine thread; null until then. */
    private Com com;
    private MemorySegment recognizer = MemorySegment.NULL;
    private MemorySegment context = MemorySegment.NULL;
    private MemorySegment grammar = MemorySegment.NULL;
    private short language;
    private long nextGrammarId = 1;
    /** The id {@link #grammar} was created under; results from any other grammar are dropped. */
    private long grammarId;

    SapiRecognizer(AudioSource source) {
        this.source = source;
    }

    @Override
    public boolean probe() {
        try {
            Com c = com();
            boolean hasInput = switch (source) {
                case AudioSource.DefaultMicrophone _ -> defaultTokenId(c, Sapi.SPCAT_AUDIOIN).isPresent();
                case AudioSource.WaveFile _ -> true;
            };
            return hasInput && defaultTokenId(c, Sapi.SPCAT_RECOGNIZERS).isPresent();
        } catch (RuntimeException e) {
            log.debug("Speech recognition is not available: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public void listen(VoiceGrammar phrases) {
        try {
            if (context.equals(MemorySegment.NULL)) {
                open();
            }
            replaceGrammar(phrases);
        } catch (RuntimeException e) {
            stop();
            throw e;
        }
    }

    @Override
    public List<Recognition> await(Duration timeout) {
        int hr = Com.check(com.callI(context, Sapi.CONTEXT_WAIT_FOR_NOTIFY_EVENT, (int) timeout.toMillis()),
                "WaitForNotifyEvent");
        if (hr == Com.S_FALSE) {
            return List.of();
        }
        return drainEvents();
    }

    @Override
    public void stop() {
        if (com == null) {
            return;
        }
        grammar = com.release(grammar);
        context = com.release(context);
        recognizer = com.release(recognizer);
    }

    @Override
    public void shutdown() {
        if (com == null) {
            return;
        }
        stop();
        com.uninitializeThread();
        com = null;
    }

    /** Binds COM and joins this thread to the apartment, on first use. */
    private Com com() {
        if (com == null) {
            Com bound = new Com();
            bound.initializeThread();
            com = bound;
        }
        return com;
    }

    /** Recogniser, input, context, event notification, and the engine's language. */
    private void open() {
        Com c = com();
        recognizer = c.create(Sapi.CLSID_SP_INPROC_RECOGNIZER, Sapi.IID_ISP_RECOGNIZER);
        attachInput(c);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            Com.check(c.callA(recognizer, Sapi.RECOGNIZER_CREATE_RECO_CONTEXT, out), "CreateRecoContext");
            context = out.get(ADDRESS, 0);
        }
        Com.check(c.call(context, Sapi.CONTEXT_SET_NOTIFY_WIN32_EVENT), "SetNotifyWin32Event");
        long interest = Sapi.interestIn(Sapi.SPEI_RECOGNITION);
        Com.check(c.callJJ(context, Sapi.CONTEXT_SET_INTEREST, interest, interest), "SetInterest");
        language = engineLanguage(c);
    }

    private void attachInput(Com c) {
        MemorySegment input = switch (source) {
            case AudioSource.DefaultMicrophone _ -> defaultMicrophoneToken(c);
            case AudioSource.WaveFile file -> waveFileStream(c, file);
        };
        try {
            Com.check(c.callAI(recognizer, Sapi.RECOGNIZER_SET_INPUT, input, Com.TRUE), "SetInput");
        } finally {
            c.release(input);
        }
    }

    /** An {@code ISpObjectToken} for the default audio input device. */
    private static MemorySegment defaultMicrophoneToken(Com c) {
        String id = defaultTokenId(c, Sapi.SPCAT_AUDIOIN)
                .orElseThrow(() -> new SapiException("No audio input device is set up"));
        MemorySegment token = c.create(Sapi.CLSID_SP_OBJECT_TOKEN, Sapi.IID_ISP_OBJECT_TOKEN);
        try (Arena arena = Arena.ofConfined()) {
            Com.check(c.callAAI(token, Sapi.TOKEN_SET_ID, MemorySegment.NULL, Com.wide(arena, id), Com.FALSE),
                    "ISpObjectToken.SetId");
            return token;
        } catch (RuntimeException e) {
            c.release(token);
            throw e;
        }
    }

    /** An {@code ISpStream} reading {@code file}; the format comes from the WAV header. */
    private static MemorySegment waveFileStream(Com c, AudioSource.WaveFile file) {
        MemorySegment stream = c.create(Sapi.CLSID_SP_STREAM, Sapi.IID_ISP_STREAM);
        try (Arena arena = Arena.ofConfined()) {
            Com.check(c.callAIAAJ(stream, Sapi.STREAM_BIND_TO_FILE,
                    Com.wide(arena, file.file().toAbsolutePath().toString()), Sapi.SPFM_OPEN_READONLY,
                    MemorySegment.NULL, MemorySegment.NULL, 0L), "BindToFile");
            return stream;
        } catch (RuntimeException e) {
            c.release(stream);
            throw e;
        }
    }

    /** The engine's first supported language, which every grammar is built in. */
    private short engineLanguage(Com c) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment status = arena.allocate(Sapi.SPRECOGNIZERSTATUS);
            Com.check(c.callA(recognizer, Sapi.RECOGNIZER_GET_STATUS, status), "ISpRecognizer.GetStatus");
            if (status.get(JAVA_INT, Sapi.STATUS_LANG_ID_COUNT) == 0) {
                throw new SapiException("The speech recogniser reports no language");
            }
            return status.get(JAVA_SHORT, Sapi.STATUS_FIRST_LANG_ID);
        }
    }

    /** Builds {@code phrases} as a fresh grammar, then drops the old one. */
    private void replaceGrammar(VoiceGrammar phrases) {
        grammar = com.release(grammar);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            grammarId = nextGrammarId++;
            Com.check(com.callJA(context, Sapi.CONTEXT_CREATE_GRAMMAR, grammarId, out), "CreateGrammar");
            grammar = out.get(ADDRESS, 0);
            Com.check(com.callS(grammar, Sapi.GRAMMAR_RESET_GRAMMAR, language), "ResetGrammar");
            List<String> texts = phrases.engineTexts();
            for (int i = 0; i < texts.size(); i++) {
                addPhraseRule(arena, i, texts.get(i));
            }
            Com.check(com.callI(grammar, Sapi.GRAMMAR_COMMIT, 0), "Commit");
            Com.check(com.callAAI(grammar, Sapi.GRAMMAR_SET_RULE_STATE, MemorySegment.NULL, MemorySegment.NULL,
                    Sapi.SPRS_ACTIVE), "SetRuleState");
        }
    }

    /** One top-level rule, id {@code index + 1}, matching {@code text} word by word. */
    private void addPhraseRule(Arena arena, int index, String text) {
        MemorySegment state = arena.allocate(ADDRESS);
        Com.check(com.callAIIIA(grammar, Sapi.GRAMMAR_GET_RULE, Com.wide(arena, RULE_NAME_PREFIX + index),
                VoiceGrammar.ruleIdOf(index), Sapi.SPRAF_TOP_LEVEL | Sapi.SPRAF_ACTIVE, Com.TRUE, state),
                "GetRule");
        Com.check(com.callAAAAIFA(grammar, Sapi.GRAMMAR_ADD_WORD_TRANSITION, state.get(ADDRESS, 0),
                MemorySegment.NULL, Com.wide(arena, text), Com.wide(arena, WORD_SEPARATORS),
                Sapi.SPWT_LEXICAL, WORD_WEIGHT, MemorySegment.NULL), "AddWordTransition \"" + text + "\"");
    }

    /** Every queued event; recognitions are read, and each event's payload is released. */
    private List<Recognition> drainEvents() {
        List<Recognition> heard = new ArrayList<>();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment event = arena.allocate(Sapi.SPEVENT);
            MemorySegment fetched = arena.allocate(JAVA_INT);
            while (true) {
                event.fill((byte) 0);
                Com.check(com.callIAA(context, Sapi.CONTEXT_GET_EVENTS, EVENT_BATCH, event, fetched), "GetEvents");
                if (fetched.get(JAVA_INT, 0) == 0) {
                    return heard;
                }
                try {
                    recognitionIn(event).ifPresent(heard::add);
                } catch (SapiException e) {
                    // One unreadable result is dropped; it must not stop recognition.
                    log.debug("Skipped a recognition result: {}", e.getMessage());
                } finally {
                    clearEvent(event);
                }
            }
        }
    }

    private Optional<Recognition> recognitionIn(MemorySegment event) {
        if (event.get(JAVA_SHORT, Sapi.EVENT_ID) != Sapi.SPEI_RECOGNITION
                || event.get(JAVA_SHORT, Sapi.EVENT_LPARAM_TYPE) != Sapi.SPET_LPARAM_IS_OBJECT) {
            return Optional.empty();
        }
        MemorySegment result = MemorySegment.ofAddress(event.get(JAVA_LONG, Sapi.EVENT_LPARAM));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            Com.check(com.callA(result, Sapi.RESULT_GET_PHRASE, out), "GetPhrase");
            MemorySegment phrase = out.get(ADDRESS, 0).reinterpret(Sapi.SPPHRASE_HEAD.byteSize());
            try {
                // A result queued before listen() swapped grammars would otherwise be read
                // against the new phrase list, whose rule ids mean different phrases.
                if (phrase.get(JAVA_LONG, Sapi.PHRASE_GRAMMAR_ID) != grammarId) {
                    return Optional.empty();
                }
                long ruleId = Integer.toUnsignedLong(phrase.get(JAVA_INT, Sapi.PHRASE_RULE_ID));
                return Optional.of(new Recognition(ruleId, phrase.get(JAVA_FLOAT, Sapi.PHRASE_RULE_CONFIDENCE)));
            } finally {
                com.free(phrase);
            }
        }
    }

    /** {@code SpClearEvent} (sphelper.h): release or free the event's {@code lParam}. */
    private void clearEvent(MemorySegment event) {
        MemorySegment payload = MemorySegment.ofAddress(event.get(JAVA_LONG, Sapi.EVENT_LPARAM));
        switch (event.get(JAVA_SHORT, Sapi.EVENT_LPARAM_TYPE)) {
            case Sapi.SPET_LPARAM_IS_TOKEN, Sapi.SPET_LPARAM_IS_OBJECT -> com.release(payload);
            case Sapi.SPET_LPARAM_IS_POINTER, Sapi.SPET_LPARAM_IS_STRING -> com.free(payload);
            default -> { }
        }
    }

    /** The id of a category's default token, or empty when it has none. */
    private static Optional<String> defaultTokenId(Com c, String category) {
        MemorySegment tokens = c.create(Sapi.CLSID_SP_OBJECT_TOKEN_CATEGORY, Sapi.IID_ISP_OBJECT_TOKEN_CATEGORY);
        try (Arena arena = Arena.ofConfined()) {
            if (c.callAI(tokens, Sapi.CATEGORY_SET_ID, Com.wide(arena, category), Com.FALSE) < 0) {
                return Optional.empty();
            }
            MemorySegment out = arena.allocate(ADDRESS);
            if (c.callA(tokens, Sapi.CATEGORY_GET_DEFAULT_TOKEN_ID, out) < 0) {
                return Optional.empty();
            }
            MemorySegment id = out.get(ADDRESS, 0);
            try {
                return Optional.of(Com.readWide(id));
            } finally {
                c.free(id);
            }
        } finally {
            c.release(tokens);
        }
    }
}
