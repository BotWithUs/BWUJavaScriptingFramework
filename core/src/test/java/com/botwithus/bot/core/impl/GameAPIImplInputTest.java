package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.input.InputDialog;
import com.botwithus.bot.api.input.InputMode;
import com.botwithus.bot.api.input.KeyStroke;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.rpc.RpcException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Interface input: the exact {@code queue_actions} rows {@link InputDialog},
 * {@code fireKeyTrigger} and {@code Bank.finishTransferX} put on the wire.
 *
 * <p>The expected rows are written as the agent's own literals rather than
 * computed with the code under test. {@link #INPUT_FIELD_HASH},
 * {@link #KEY_TRIGGER_TOP_LEVEL}, {@link #CHAR_3} and {@link #ENTER} are copied
 * from the NXTLibrary scenario {@code iface-input-bank-x}, which moved a live
 * backpack count with them. A packing change on this side has to disagree with
 * a value the game accepted.</p>
 */
class GameAPIImplInputTest {

    private static final String QUEUE_ACTIONS = "queue_actions";
    private static final String GET_VARC_INT = "get_varc_int";
    private static final String GET_VARC_STRING = "get_varc_string";

    private static final int COMPONENT_TRIGGER = 5003;
    /** {@code (1469 << 16) | 4}: MESLAYER's input field. */
    private static final int INPUT_FIELD_HASH = 96272388;
    /** {@code (10 << 16) | 0xFFFF}: a key trigger on the component itself. */
    private static final int KEY_TRIGGER_TOP_LEVEL = 720895;

    /** {@code (-1 << 16) | '3'}. */
    private static final int CHAR_3 = -65485;
    /** {@code (84 << 16) | 0}. */
    private static final int ENTER = 5505024;
    /** {@code (85 << 16) | 0}. */
    private static final int BACKSPACE = 5570560;
    /** {@code (13 << 16) | 0}. */
    private static final int ESCAPE = 851968;
    /** Printable characters are {@code (-1 << 16) | c}, which is {@code c - 65536}. */
    private static final int CHAR_BASE = -65536;

    /** A {@code queued} reply at least as large as any batch these tests send. */
    private static final int ROOMY_QUEUE = 128;

    private static final int MODE_CLOSED = 0;
    private static final int MODE_NAME = 2;
    private static final int MODE_AMOUNT = 7;
    /** A {@code MESLAYERMODE} value this API has no mode for. */
    private static final int MODE_UNLISTED = 5;

    private RpcClient rpc;
    private GameSnapshot snap;
    private GameAPIImpl api;
    private InputDialog dialog;

    @BeforeEach
    void build() {
        rpc = mock(RpcClient.class);
        snap = mock(GameSnapshot.class);
        api = new GameAPIImpl(rpc, null, () -> snap);
        dialog = api.inputDialog();
        queueReports(ROOMY_QUEUE);
        dialogText("");
    }

    /** The {@code queued} count the agent reports for every batch. */
    private void queueReports(int queued) {
        when(rpc.callSync(eq(QUEUE_ACTIONS), anyMap())).thenReturn(Map.of("queued", queued));
    }

    private void dialogText(String text) {
        when(rpc.callSync(eq(GET_VARC_STRING), eq(Map.of("var_id", 2506))))
                .thenReturn(Map.of("value", text));
    }

    private void dialogMode(int varcValue) {
        when(rpc.callSync(eq(GET_VARC_INT), eq(Map.of("var_id", 5))))
                .thenReturn(Map.of("value", varcValue));
    }

    private static int typed(char c) {
        return CHAR_BASE + c;
    }

    private static Map<String, Object> row(int arg) {
        return Map.of("action_id", COMPONENT_TRIGGER, "param1", INPUT_FIELD_HASH,
                "param2", KEY_TRIGGER_TOP_LEVEL, "param3", arg);
    }

    private static List<Map<String, Object>> rows(int... args) {
        return Arrays.stream(args).mapToObj(GameAPIImplInputTest::row).toList();
    }

    /** The one {@code queue_actions} batch sent, as rows. */
    private Object sentBatch() {
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.captor();
        verify(rpc).callSync(eq(QUEUE_ACTIONS), params.capture());
        return params.getValue().get("actions");
    }

    private void verifyNothingQueued() {
        verify(rpc, never()).callSync(eq(QUEUE_ACTIONS), anyMap());
    }

    @Nested
    class Amounts {

        @Test
        void enterAmount_singleDigit_isTheScenarioSequence() {
            dialogMode(MODE_AMOUNT);

            assertTrue(dialog.enterAmount(3));

            assertEquals(rows(CHAR_3, ENTER), sentBatch(),
                    "(-1,'3') then (84,0), in one batch: what moved the backpack count live");
        }

        @Test
        void enterAmount_multiDigit_typesEachDigitThenEnter() {
            dialogMode(MODE_AMOUNT);

            assertTrue(dialog.enterAmount(28));

            assertEquals(rows(typed('2'), typed('8'), ENTER), sentBatch());
        }

        @Test
        void enterAmount_kSuffix_typesTheSuffixAsACharacter() {
            dialogMode(MODE_AMOUNT);

            assertTrue(dialog.enterAmount("250k"));

            assertEquals(rows(-65486, -65483, -65488, -65429, ENTER), sentBatch(),
                    "'2' '5' '0' 'k' are 50 53 48 107 in the char half, then Enter");
        }

        @ParameterizedTest
        @ValueSource(strings = {"1234567890", "2147483647", "2147483k", "2147483K", "2147m", "2147M", "0", "1m", "1M",
                "5K", "5M"})
        void enterAmount_atOrUnderTheLimits_isAccepted(String amount) {
            dialogMode(MODE_AMOUNT);

            assertTrue(dialog.enterAmount(amount));
        }

        @Test
        void enterAmount_intMax_isAccepted() {
            dialogMode(MODE_AMOUNT);

            assertTrue(dialog.enterAmount(Integer.MAX_VALUE));
        }

        /**
         * Each breaks the charset, the one-trailing-suffix rule, the ten-character
         * limit or the int range once expanded.
         */
        @ParameterizedTest
        @ValueSource(strings = {"", "b", "5b", "1.5k", "1 000", "k", "k5", "5kk", "5k0", "-5",
                "5MM", "M5", "12345678901", "123456789kk", "2147483648", "2147484k", "2148m",
                "2148M", "9999999999", "999999999m"})
        void enterAmount_valueTheDialogRefuses_throwsAndSendsNothing(String amount) {
            dialogMode(MODE_AMOUNT);

            assertThrows(IllegalArgumentException.class, () -> dialog.enterAmount(amount));

            verifyNothingQueued();
            verify(rpc, never()).callSync(eq(GET_VARC_INT), anyMap());
        }

        @Test
        void enterAmount_negative_throwsAndSendsNothing() {
            assertThrows(IllegalArgumentException.class, () -> dialog.enterAmount(-1));

            verifyNothingQueued();
        }

        @Test
        void enterAmount_aboveIntMax_throws() {
            assertThrows(IllegalArgumentException.class, () -> dialog.enterAmount(2_147_483_648L));
            assertThrows(IllegalArgumentException.class, () -> dialog.enterAmount(10_000_000_000L));

            verifyNothingQueued();
        }

        /** The dialog appends, so what counts is the existing text followed by the new amount. */
        @ParameterizedTest
        @ValueSource(strings = {"12345678", "5k", "2147483", "7m"})
        void enterAmount_existingTextPlusAmountRefused_returnsFalseAndSendsNothing(String existing) {
            dialogMode(MODE_AMOUNT);
            dialogText(existing);

            assertFalse(dialog.enterAmount("648"));

            verifyNothingQueued();
        }

        @Test
        void enterAmount_existingTextPlusAmountAccepted_typesOnlyTheNewPart() {
            dialogMode(MODE_AMOUNT);
            dialogText("5");

            assertTrue(dialog.enterAmount(3));

            assertEquals(rows(CHAR_3, ENTER), sentBatch());
        }

        @Test
        void enterAmount_nameModeDialog_returnsFalseAndSendsNothing() {
            dialogMode(MODE_NAME);

            assertFalse(dialog.enterAmount(3));

            verifyNothingQueued();
        }

        @Test
        void enterAmount_closedDialog_returnsFalseAndSendsNothing() {
            dialogMode(MODE_CLOSED);

            assertFalse(dialog.enterAmount(3));

            verifyNothingQueued();
        }
    }

    @Nested
    class Names {

        @Test
        void enterText_name_keepsCaseAndSubmits() {
            dialogMode(MODE_NAME);

            assertTrue(dialog.enterText("Zez_a 9.-!"));

            assertEquals(rows(typed('Z'), typed('e'), typed('z'), typed('_'), typed('a'),
                    typed(' '), typed('9'), typed('.'), typed('-'), typed('!'), ENTER), sentBatch());
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "a@b", "tab\there", "café", "abcdefghijklm"})
        void enterText_valueNameModeRefuses_throwsAndSendsNothing(String text) {
            dialogMode(MODE_NAME);

            assertThrows(IllegalArgumentException.class, () -> dialog.enterText(text));

            verifyNothingQueued();
        }

        @Test
        void enterText_twelveCharacters_isAccepted() {
            dialogMode(MODE_NAME);

            assertTrue(dialog.enterText("abcdefghijkl"));
        }

        @Test
        void enterText_existingTextPlusTextOverTwelve_returnsFalseAndSendsNothing() {
            dialogMode(MODE_NAME);
            dialogText("abcdefghij");

            assertFalse(dialog.enterText("xyz"));

            verifyNothingQueued();
        }

        @Test
        void enterText_amountModeDialog_returnsFalse() {
            dialogMode(MODE_AMOUNT);

            assertFalse(dialog.enterText("abc"));

            verifyNothingQueued();
        }

        @Test
        void enterText_unlistedModeDialog_returnsFalse() {
            dialogMode(MODE_UNLISTED);

            assertFalse(dialog.enterText("abc"));

            verifyNothingQueued();
        }
    }

    @Nested
    class ControlKeys {

        @Test
        void submit_sendsEnterAsAKeyCode() {
            dialogMode(MODE_AMOUNT);

            assertTrue(dialog.submit());

            assertEquals(rows(ENTER), sentBatch());
        }

        @Test
        void cancel_sendsEscapeAsAKeyCode() {
            dialogMode(MODE_NAME);

            assertTrue(dialog.cancel());

            assertEquals(rows(ESCAPE), sentBatch());
        }

        @Test
        void clear_sendsOneBackspacePerTypedCharacter() {
            dialogMode(MODE_AMOUNT);
            when(rpc.callSync(eq(GET_VARC_STRING), eq(Map.of("var_id", 2506))))
                    .thenReturn(Map.of("value", "12k"));

            assertTrue(dialog.clear());

            assertEquals(rows(BACKSPACE, BACKSPACE, BACKSPACE), sentBatch());
        }

        /** Shared vector: {@code backspace(2)} is two key-downs of code 85. */
        @Test
        void backspace_two_sendsTwoBackspaceKeyCodes() {
            dialogMode(MODE_AMOUNT);

            assertTrue(dialog.backspace(2));

            assertEquals(rows(BACKSPACE, BACKSPACE), sentBatch());
        }

        @Test
        void backspace_zero_sendsNothing() {
            dialogMode(MODE_NAME);

            assertTrue(dialog.backspace(0));

            verifyNothingQueued();
        }

        @Test
        void backspace_negative_throwsWithoutReadingTheDialog() {
            assertThrows(IllegalArgumentException.class, () -> dialog.backspace(-1));

            verify(rpc, never()).callSync(eq(GET_VARC_INT), anyMap());
            verifyNothingQueued();
        }

        @Test
        void clear_emptyField_sendsNothing() {
            dialogMode(MODE_AMOUNT);
            when(rpc.callSync(eq(GET_VARC_STRING), anyMap())).thenReturn(Map.of("value", ""));

            assertTrue(dialog.clear());

            verifyNothingQueued();
        }

        @Test
        void controlKeys_closedDialog_returnFalseAndSendNothing() {
            dialogMode(MODE_CLOSED);

            assertFalse(dialog.submit());
            assertFalse(dialog.cancel());
            assertFalse(dialog.clear());
            assertFalse(dialog.backspace(2));

            verifyNothingQueued();
        }
    }

    @Nested
    class State {

        @Test
        void mode_readsMeslayerMode() {
            dialogMode(MODE_AMOUNT);
            assertEquals(InputMode.AMOUNT, dialog.mode());

            dialogMode(MODE_NAME);
            assertEquals(InputMode.NAME, dialog.mode());

            dialogMode(MODE_UNLISTED);
            assertEquals(InputMode.OTHER, dialog.mode());
            assertTrue(dialog.isOpen());

            dialogMode(MODE_CLOSED);
            assertEquals(InputMode.CLOSED, dialog.mode());
            assertFalse(dialog.isOpen());
        }

        /** Live on 950-1, MESLAYERMODE reads -1 after login until a dialog first opens. */
        @Test
        void mode_neverOpenedThisSession_isClosed() {
            dialogMode(-1);

            assertEquals(InputMode.CLOSED, dialog.mode());
            assertFalse(dialog.isOpen());
            assertFalse(dialog.enterAmount(3));
            verifyNothingQueued();
        }

        /** The typed-text variable keeps its value after close, so a closed dialog must not report it. */
        @Test
        void text_closedDialog_isEmptyWithoutReadingTheStaleVariable() {
            dialogMode(MODE_CLOSED);

            assertEquals(Optional.empty(), dialog.text());

            verify(rpc, never()).callSync(eq(GET_VARC_STRING), anyMap());
        }
    }

    /** The vectors the Python and Lua suites pin too, as literals, so the three hosts agree. */
    @Nested
    class SharedVectors {

        @Test
        void enterAmount_10k() {
            dialogMode(MODE_AMOUNT);

            assertTrue(dialog.enterAmount("10k"));

            assertEquals(rows(-65487, -65488, -65429, ENTER), sentBatch());
        }

        @Test
        void enterText_Bob_1() {
            dialogMode(MODE_NAME);

            assertTrue(dialog.enterText("Bob_1"));

            assertEquals(rows(-65470, -65425, -65438, -65441, -65487, ENTER), sentBatch());
        }

        @Test
        void fireComponentTrigger_packedCharThree_passesThrough() {
            api.fireComponentTrigger(1469, 4, -1, 10, -65485);

            verify(rpc).callSync(eq("queue_action"), eq(Map.of("action_id", COMPONENT_TRIGGER,
                    "param1", INPUT_FIELD_HASH, "param2", KEY_TRIGGER_TOP_LEVEL, "param3", CHAR_3)));
        }
    }

    @Nested
    class Failures {

        /** The queue took '3' but not Enter: the value is typed and unsubmitted, which must be loud. */
        @Test
        void enterAmount_partialBatch_throwsIllegalState() {
            dialogMode(MODE_AMOUNT);
            queueReports(1);

            assertThrows(IllegalStateException.class, () -> dialog.enterAmount(3));
        }

        @Test
        void controlKeys_emptyQueueReply_throwsIllegalState() {
            dialogMode(MODE_AMOUNT);
            queueReports(0);

            assertThrows(IllegalStateException.class, () -> dialog.submit());
        }

        /** A failed mode read must not be taken as "closed", and nothing may be sent after it. */
        @Test
        void modeRead_transportFailure_propagatesAndSendsNothing() {
            when(rpc.callSync(eq(GET_VARC_INT), anyMap())).thenThrow(new RpcException("pipe closed"));

            assertThrows(RpcException.class, () -> dialog.isOpen());
            assertThrows(RpcException.class, () -> dialog.enterAmount(3));
            assertThrows(RpcException.class, () -> dialog.submit());

            verifyNothingQueued();
        }

        @Test
        void textRead_transportFailure_propagatesAndSendsNothing() {
            dialogMode(MODE_AMOUNT);
            when(rpc.callSync(eq(GET_VARC_STRING), anyMap())).thenThrow(new RpcException("pipe closed"));

            assertThrows(RpcException.class, () -> dialog.enterAmount(3));
            assertThrows(RpcException.class, () -> dialog.clear());

            verifyNothingQueued();
        }
    }

    @Nested
    class Callers {

        /** Types only: the existing public method must not start submitting under callers that add their own Enter. */
        @Test
        void fireKeyTrigger_typesCharactersWithoutSubmitting() {
            api.fireKeyTrigger(1469, 4, "3");

            assertEquals(rows(CHAR_3), sentBatch());
        }

        /**
         * The documented form: a bare key code. Scripts pass {@code 84} for Enter,
         * which unpacked raw would be character 84, a {@code 'T'}.
         */
        @Test
        void fireComponentTrigger_bareKeyCode_isSentAsAKeyDown() {
            api.fireComponentTrigger(1469, 4, -1, 10, 84);

            verify(rpc).callSync(eq("queue_action"), eq(Map.of("action_id", COMPONENT_TRIGGER,
                    "param1", INPUT_FIELD_HASH, "param2", KEY_TRIGGER_TOP_LEVEL, "param3", 0x00540000)));
        }

        @Test
        void fireComponentTrigger_packedStroke_passesThrough() {
            api.fireComponentTrigger(1469, 4, -1, 10, KeyStroke.character('5').packed());

            verify(rpc).callSync(eq("queue_action"), eq(Map.of("action_id", COMPONENT_TRIGGER,
                    "param1", INPUT_FIELD_HASH, "param2", KEY_TRIGGER_TOP_LEVEL, "param3", typed('5'))));
        }

        /** Only key triggers are normalised; a click's packed press coordinates are left alone. */
        @Test
        void fireComponentTrigger_clickArg_isNotTouched() {
            int pressAt = (3 << 16) | 7;

            api.fireComponentTrigger(1469, 4, -1, 9, pressAt);
            api.fireComponentTrigger(1469, 4, -1, 9, 84);

            verify(rpc).callSync(eq("queue_action"), eq(Map.of("action_id", COMPONENT_TRIGGER,
                    "param1", INPUT_FIELD_HASH, "param2", (9 << 16) | 0xFFFF, "param3", pressAt)));
            verify(rpc).callSync(eq("queue_action"), eq(Map.of("action_id", COMPONENT_TRIGGER,
                    "param1", INPUT_FIELD_HASH, "param2", (9 << 16) | 0xFFFF, "param3", 84)));
        }

        @Test
        void fireKeyTrigger_nonAscii_throwsAndSendsNothing() {
            assertThrows(IllegalArgumentException.class, () -> api.fireKeyTrigger(1469, 4, "€"));

            verifyNothingQueued();
        }

        @Test
        void finishTransferX_typesIntoComponentFourAndSubmits() {
            when(snap.isInterfaceOpen(anyInt())).thenReturn(true);
            dialogMode(MODE_AMOUNT);

            assertTrue(api.bank().finishTransferX(3));

            assertEquals(rows(CHAR_3, ENTER), sentBatch(),
                    "withdraw-X / deposit-X target 1469:4 and end with Enter");
        }

        @Test
        void finishTransferX_bankClosed_returnsFalseAndSendsNothing() {
            when(snap.isInterfaceOpen(anyInt())).thenReturn(false);
            dialogMode(MODE_AMOUNT);

            assertFalse(api.bank().finishTransferX(3));

            verifyNothingQueued();
        }
    }
}
