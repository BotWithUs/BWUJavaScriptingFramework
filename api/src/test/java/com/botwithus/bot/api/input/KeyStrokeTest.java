package com.botwithus.bot.api.input;

import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.GameAction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The packed key-trigger argument, checked against the values the agent's
 * {@code iface-input-bank-x} scenario sent to a live client.
 */
class KeyStrokeTest {

    @Test
    void character_packsTheCharIntoTheLowHalfWithNoKeyCode() {
        assertEquals(-65485, KeyStroke.character('3').packed(), "(-1 << 16) | '3'");
        assertEquals(new KeyStroke(-1, '3'), KeyStroke.character('3'));
    }

    @Test
    void controlKeys_packTheCodeIntoTheHighHalfWithNoChar() {
        assertEquals(5505024, KeyStroke.ENTER.packed(), "(84 << 16) | 0");
        assertEquals(5570560, KeyStroke.BACKSPACE.packed(), "(85 << 16) | 0");
        assertEquals(851968, KeyStroke.ESCAPE.packed(), "(13 << 16) | 0");
    }

    /** The scenario's "faithful" Enter: the character event that follows the key-down. */
    @Test
    void codeAndCharTogether_packBothHalves() {
        assertEquals(-65523, new KeyStroke(-1, 13).packed(), "(-1 << 16) | 13");
        assertEquals(1179648, new KeyStroke(18, 0).packed(), "keydown for '3': (18 << 16) | 0");
    }

    @Test
    void character_refusesAnythingOutsidePrintableAscii() {
        assertThrows(IllegalArgumentException.class, () -> KeyStroke.character('\n'));
        assertThrows(IllegalArgumentException.class, () -> KeyStroke.character('\u007F'));
        assertThrows(IllegalArgumentException.class, () -> KeyStroke.character('€'));
    }

    @Test
    void constructor_refusesValuesThatDoNotFitTheirHalf() {
        assertThrows(IllegalArgumentException.class, () -> new KeyStroke(Short.MAX_VALUE + 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new KeyStroke(Short.MIN_VALUE - 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new KeyStroke(-1, -1));
        assertThrows(IllegalArgumentException.class, () -> new KeyStroke(-1, 0x10000));
    }

    @Test
    void componentTrigger_packsLikeTheScenario() {
        GameAction action = GameAction.componentTrigger(1469, 4, -1,
                ActionTypes.TRIGGER_TYPE_KEY, KeyStroke.ENTER.packed());

        assertEquals(new GameAction(5003, 96272388, 720895, 5505024), action);
    }
}
