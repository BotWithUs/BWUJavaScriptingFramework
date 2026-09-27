package com.botwithus.bot.cli.gui.window;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWWindowSizeCallback;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link WindowControls} over a GLFW window. Kept to direct GLFW calls; every
 * decision about where the window goes lives in the classes that are tested
 * without a window.
 *
 * <p>Bounds are the <em>outer</em> bounds. GLFW positions and sizes the content
 * area, which for the frameless window is the whole window; with the native
 * frame the frame's own thickness is added and removed here, so a saved
 * placement means the same thing in both.</p>
 */
public final class GlfwWindow implements WindowControls {

    private final long handle;

    public GlfwWindow(long handle) {
        this.handle = handle;
    }

    /** Each monitor's work area (the screen less the taskbar), the primary monitor first. */
    public static List<WindowRect> workAreas() {
        PointerBuffer monitors = GLFW.glfwGetMonitors();
        List<WindowRect> areas = new ArrayList<>();
        if (monitors == null) {
            return areas;
        }
        int[] x = new int[1];
        int[] y = new int[1];
        int[] w = new int[1];
        int[] h = new int[1];
        for (int i = 0; i < monitors.limit(); i++) {
            GLFW.glfwGetMonitorWorkarea(monitors.get(i), x, y, w, h);
            if (w[0] > 0 && h[0] > 0) {
                areas.add(new WindowRect(x[0], y[0], w[0], h[0]));
            }
        }
        return areas;
    }

    /** The primary monitor's content scale, 1 when there is none or it reports less. */
    public static float primaryContentScale() {
        long monitor = GLFW.glfwGetPrimaryMonitor();
        float[] xScale = {1f};
        float[] yScale = {1f};
        if (monitor != 0L) {
            GLFW.glfwGetMonitorContentScale(monitor, xScale, yScale);
        }
        return Math.max(xScale[0], 1f);
    }

    /**
     * Removes the window's size callback. The one the framework installs draws a
     * whole frame from inside every resize, and before ImGui is set up that frame
     * throws, so it has to go before the window is first sized or maximised.
     */
    public void clearSizeCallback() {
        GLFWWindowSizeCallback previous = GLFW.glfwSetWindowSizeCallback(handle, null);
        if (previous != null) {
            previous.free();
        }
    }

    /**
     * Stops the window being resized, natively or by the chrome, below
     * {@code width} x {@code height} outer pixels. GLFW limits the content area,
     * so the native frame's thickness comes off first.
     */
    public void setMinimumSize(int width, int height) {
        Insets frame = frame();
        GLFW.glfwSetWindowSizeLimits(handle, width - frame.left() - frame.right(),
                height - frame.top() - frame.bottom(), GLFW.GLFW_DONT_CARE, GLFW.GLFW_DONT_CARE);
    }

    @Override
    public WindowRect bounds() {
        Insets frame = frame();
        int[] x = new int[1];
        int[] y = new int[1];
        int[] w = new int[1];
        int[] h = new int[1];
        GLFW.glfwGetWindowPos(handle, x, y);
        GLFW.glfwGetWindowSize(handle, w, h);
        return new WindowRect(x[0] - frame.left(), y[0] - frame.top(),
                w[0] + frame.left() + frame.right(), h[0] + frame.top() + frame.bottom());
    }

    @Override
    public void setBounds(WindowRect bounds) {
        WindowRect now = bounds();
        Insets frame = frame();
        if (bounds.x() != now.x() || bounds.y() != now.y()) {
            GLFW.glfwSetWindowPos(handle, bounds.x() + frame.left(), bounds.y() + frame.top());
        }
        if (bounds.width() != now.width() || bounds.height() != now.height()) {
            GLFW.glfwSetWindowSize(handle, bounds.width() - frame.left() - frame.right(),
                    bounds.height() - frame.top() - frame.bottom());
        }
    }

    @Override
    public ScreenPoint cursor() {
        double[] cx = new double[1];
        double[] cy = new double[1];
        int[] x = new int[1];
        int[] y = new int[1];
        GLFW.glfwGetCursorPos(handle, cx, cy);
        GLFW.glfwGetWindowPos(handle, x, y);
        return new ScreenPoint(x[0] + (int) Math.floor(cx[0]), y[0] + (int) Math.floor(cy[0]));
    }

    @Override
    public boolean isMaximised() {
        return GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_MAXIMIZED) == GLFW.GLFW_TRUE;
    }

    @Override
    public boolean isIconified() {
        return GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE;
    }

    @Override
    public void minimise() {
        GLFW.glfwIconifyWindow(handle);
    }

    @Override
    public void maximise() {
        GLFW.glfwMaximizeWindow(handle);
    }

    @Override
    public void restore() {
        GLFW.glfwRestoreWindow(handle);
    }

    @Override
    public void close() {
        GLFW.glfwSetWindowShouldClose(handle, true);
    }

    private Insets frame() {
        int[] left = new int[1];
        int[] top = new int[1];
        int[] right = new int[1];
        int[] bottom = new int[1];
        GLFW.glfwGetWindowFrameSize(handle, left, top, right, bottom);
        return new Insets(left[0], top[0], right[0], bottom[0]);
    }

    /** The native frame's thickness on each side; all zero for the frameless window. */
    private record Insets(int left, int top, int right, int bottom) {}
}
