package com.botwithus.bot.cli.gui.inspector;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The keys held to click a pinned overlay: how they are written, read back and checked. */
class InteractKeysTest {

    private static final int VK_Q = 'Q';
    private static final int VK_TAB = 0x09;
    private static final int VK_F5 = InteractKeys.VK_F1 + 4;

    @Test
    void theDefault_isCtrl() {
        assertEquals("Ctrl", InteractKeys.DEFAULT.label());
    }

    @Test
    void aCombination_isWrittenModifiersFirst_andReadBackInAnyCaseAndOrder() {
        InteractKeys keys = InteractKeys.parse("q + alt+ctrl").orElseThrow();
        InteractKeys shiftF5 = new InteractKeys(false, true, false, VK_F5);

        assertAll(
                () -> assertEquals("Ctrl+Alt+Q", keys.label()),
                () -> assertEquals(Optional.of(keys), InteractKeys.parse(keys.label())),
                () -> assertEquals("Shift+F5", shiftF5.label()),
                () -> assertEquals(Optional.of(shiftF5), InteractKeys.parse("Shift+F5")));
    }

    @Test
    void textThatIsNoCombination_readsAsNone() {
        assertAll(
                () -> assertEquals(Optional.empty(), InteractKeys.parse("Q")),
                () -> assertEquals(Optional.empty(), InteractKeys.parse("Ctrl+Q+W")),
                () -> assertEquals(Optional.empty(), InteractKeys.parse("Ctrl+Tab")),
                () -> assertEquals(Optional.empty(), InteractKeys.parse("")));
    }

    @Test
    void aCombination_needsAModifier_andAKeyItCanUse() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new InteractKeys(false, false, false, VK_Q)),
                () -> assertThrows(IllegalArgumentException.class, () -> new InteractKeys(true, false, false, VK_TAB)));
    }

    @Test
    void itIsHeld_onlyWhileEveryKeyIsDown_whateverElseIsDownToo() {
        InteractKeys ctrlShiftQ = new InteractKeys(true, true, false, VK_Q);
        Set<Integer> allOfThemAndAlt =
                Set.of(InteractKeys.VK_CONTROL, InteractKeys.VK_SHIFT, VK_Q, InteractKeys.VK_MENU);
        Set<Integer> noQ = Set.of(InteractKeys.VK_CONTROL, InteractKeys.VK_SHIFT);

        assertAll(
                () -> assertTrue(ctrlShiftQ.isHeld(allOfThemAndAlt::contains)),
                () -> assertFalse(ctrlShiftQ.isHeld(noQ::contains)),
                () -> assertTrue(InteractKeys.DEFAULT.isHeld(Set.of(InteractKeys.VK_CONTROL)::contains)),
                () -> assertFalse(InteractKeys.DEFAULT.isHeld(Set.of(InteractKeys.VK_SHIFT)::contains)));
    }
}
