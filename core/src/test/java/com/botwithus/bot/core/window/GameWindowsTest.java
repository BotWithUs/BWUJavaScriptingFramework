package com.botwithus.bot.core.window;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The user32 bindings against the real desktop: the test JVM shows no window,
 * and Explorer, where it runs, shows its taskbar.
 */
@EnabledOnOs(OS.WINDOWS)
class GameWindowsTest {

    private static final String EXPLORER = "explorer.exe";

    @Test
    void aProcessShowingNoWindow_hasNoGameWindow() {
        long self = ProcessHandle.current().pid();

        assertAll(
                () -> assertEquals(OptionalLong.empty(), GameWindows.mainWindowOf(self)),
                () -> assertFalse(GameWindows.isWindowOf(0L, self)),
                () -> assertFalse(GameWindows.isForeground(0L)),
                () -> assertFalse(GameWindows.setOwner(0L, 0L)));
    }

    @Test
    void explorersWindow_isFound_belongsToIt_andHasAnArea() {
        Optional<ProcessHandle> explorer = ProcessHandle.allProcesses()
                .filter(p -> p.info().command().map(c -> c.toLowerCase(Locale.ROOT).endsWith(EXPLORER)).orElse(false))
                .filter(p -> GameWindows.mainWindowOf(p.pid()).isPresent())
                .findFirst();
        assumeTrue(explorer.isPresent(), "no Explorer showing a window on this desktop");
        long pid = explorer.get().pid();
        long window = GameWindows.mainWindowOf(pid).orElseThrow();
        Optional<ClientArea> area = GameWindows.shownClientArea(window);

        assertAll(
                () -> assertTrue(GameWindows.isWindowOf(window, pid)),
                () -> assertFalse(GameWindows.isWindowOf(window, ProcessHandle.current().pid())),
                () -> assertTrue(area.isPresent()),
                () -> assertTrue(area.orElseThrow().width() > 0 && area.orElseThrow().height() > 0));
    }
}
