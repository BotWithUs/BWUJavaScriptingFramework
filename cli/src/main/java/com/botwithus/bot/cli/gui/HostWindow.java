package com.botwithus.bot.cli.gui;

import com.botwithus.bot.cli.gui.window.GlfwWindow;
import com.botwithus.bot.cli.gui.window.PlacementRestore;
import com.botwithus.bot.cli.gui.window.PlacementTracker;
import com.botwithus.bot.cli.gui.window.WindowPlacement;
import com.botwithus.bot.cli.gui.window.WindowPlacementStore;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.InvalidSettingException;
import com.botwithus.bot.cli.settings.SettingKeys;

import imgui.app.Configuration;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The host's one OS window: frameless unless {@code ui.nativeFrame} is on, opened
 * where it was left, and saved as it moves.
 *
 * <p>Two steps, because GLFW creates the window in the middle of the app's
 * start-up: {@link #prepare} runs before the window exists and sets it up to be
 * created frameless and at its saved size; {@link Pending#attach} runs right
 * after and moves it into place. The window appears where the framework centres
 * it for the frame between the two; the framework offers no earlier hook.</p>
 */
public final class HostWindow {

    private static final Logger log = LoggerFactory.getLogger(HostWindow.class);

    /** Smallest window, in unscaled pixels, before the monitor's content scale. */
    private static final int MIN_WIDTH_PX = 640;
    private static final int MIN_HEIGHT_PX = 400;
    /** A saved window must keep a square this big on a monitor to open where it was. */
    private static final int MIN_VISIBLE_SIDE_PX = 100;

    private final GlfwWindow window;
    private final WindowPlacementStore store;
    private final PlacementTracker tracker;
    private final boolean isNativeFrame;
    private final int minWidth;
    private final int minHeight;

    private HostWindow(GlfwWindow window, WindowPlacementStore store, WindowPlacement placement,
                       boolean isNativeFrame, int minWidth, int minHeight) {
        this.window = window;
        this.store = store;
        this.tracker = new PlacementTracker(placement);
        this.isNativeFrame = isNativeFrame;
        this.minWidth = minWidth;
        this.minHeight = minHeight;
    }

    /**
     * Before the window is created: starts GLFW so the monitors can be read,
     * asks for a frameless window unless the native frame is on, works out where
     * the window goes and gives {@code config} its size.
     */
    public static Pending prepare(HostSettings settings, Configuration config) {
        if (!GLFW.glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }
        boolean isNativeFrame = settings.get(SettingKeys.NATIVE_FRAME);
        GLFW.glfwWindowHint(GLFW.GLFW_DECORATED, isNativeFrame ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        float scale = GlfwWindow.primaryContentScale();
        int minWidth = Math.round(MIN_WIDTH_PX * scale);
        int minHeight = Math.round(MIN_HEIGHT_PX * scale);
        PlacementRestore restore = new PlacementRestore(
                Math.toIntExact(SettingKeys.WINDOW_WIDTH.defaultValue()),
                Math.toIntExact(SettingKeys.WINDOW_HEIGHT.defaultValue()),
                minWidth, minHeight, MIN_VISIBLE_SIDE_PX);
        WindowPlacementStore store = new WindowPlacementStore(settings);
        WindowPlacement placement = restore.resolve(store.load(), GlfwWindow.workAreas());
        config.setWidth(placement.normal().width());
        config.setHeight(placement.normal().height());
        return new Pending(store, placement, isNativeFrame, minWidth, minHeight);
    }

    /** A window worked out by {@link #prepare} and not yet created. */
    public record Pending(WindowPlacementStore store, WindowPlacement placement, boolean isNativeFrame,
                          int minWidth, int minHeight) {

        /** Right after the window is created: puts it where {@link #prepare} worked out. */
        public HostWindow attach(long handle) {
            GlfwWindow window = new GlfwWindow(handle);
            window.clearSizeCallback();
            window.setMinimumSize(minWidth, minHeight);
            window.setBounds(placement.normal());
            if (placement.isMaximised()) {
                window.maximise();
            }
            return new HostWindow(window, store, placement, isNativeFrame, minWidth, minHeight);
        }
    }

    /** The chrome the top bar and shell draw: our own when frameless, none over the native frame. */
    public WindowChrome chrome(Controls ui) {
        if (isNativeFrame) {
            return new NativeChrome();
        }
        return new FramelessChrome(ui, window, minWidth, minHeight);
    }

    /** Once per frame: saves the placement if the window moved, resized, or was maximised or restored. */
    public void endFrame() {
        tracker.observe(window.bounds(), window.isMaximised(), window.isIconified()).ifPresent(this::save);
    }

    private void save(WindowPlacement placement) {
        try {
            store.save(placement);
        } catch (InvalidSettingException e) {
            // Only reachable for a window dragged far beyond any monitor; it just is not remembered.
            log.debug("Not saving the window placement: {}", e.getMessage());
        }
    }
}
