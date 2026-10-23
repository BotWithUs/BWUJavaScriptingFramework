package com.botwithus.bot.cli.gui.pages.store;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;

import imgui.ImDrawList;
import imgui.ImGui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The one-line notes between the filters and the list: a stale catalogue, a
 * refresh that failed, a host that cannot install, and how the last install went.
 * Each is a tinted strip in its status colour; none of them blocks the list.
 */
final class StoreBanners {

    private static final String TRY_AGAIN = "Try again";

    /** What a strip shows and does. */
    private enum Kind { RETRY, DISMISS, NONE }

    private record Banner(String icon, String text, int fg, int bg, Kind kind, boolean isBusy) {}

    private final StoreWidgets w;
    private final Controls ui;

    StoreBanners(StoreWidgets widgets) {
        this.w = widgets;
        this.ui = widgets.ui();
    }

    /** The height {@link #render} will use for {@code view}, gaps included. */
    float height(StoreView view) {
        int n = banners(view).size();
        return n == 0 ? 0f : n * (stripHeight() + ui.m().u(2)) + ui.m().u(1);
    }

    /** Draws every strip that applies, stacked from (x, y). */
    void render(StoreView view, StoreActions actions, float x, float y, float width) {
        float cy = y + ui.m().u(3);
        int i = 0;
        for (Banner b : banners(view)) {
            strip(b, actions, x, cy, width, i++);
            cy += stripHeight() + ui.m().u(2);
        }
    }

    private float stripHeight() {
        return ui.m().controlSmallHeight() + ui.m().u(1) * 2f;
    }

    private static List<Banner> banners(StoreView view) {
        List<Banner> out = new ArrayList<>();
        switch (view.status()) {
            case StoreStatus.Ready ready -> catalogueBanners(view, ready, out);
            case StoreStatus.Loading ignored -> { }
            case StoreStatus.Unavailable ignored -> { }
        }
        install(view).ifPresent(out::add);
        return out;
    }

    /** The strips about the catalogue itself, shown only while there is one. */
    private static void catalogueBanners(StoreView view, StoreStatus.Ready ready, List<Banner> out) {
        if (ready.isStale()) {
            out.add(warn("Showing the last catalogue we saw. The launcher is not answering.", Kind.RETRY));
        }
        refreshError(ready).ifPresent(out::add);
        if (!view.canInstall()) {
            out.add(new Banner(Icons.INFO, LiveStoreModel.DELIVERY_DISABLED, ImGuiTheme.COL_FG2,
                    ImGuiTheme.COL_ELEVATED, Kind.NONE, false));
        }
    }

    private static Optional<Banner> refreshError(StoreStatus.Ready ready) {
        return ready.refreshError().map(error -> warn("Could not refresh (" + error
                + "). Showing the last list; retrying automatically.", Kind.NONE));
    }

    private static Banner warn(String text, Kind kind) {
        return new Banner(Icons.CLOCK, text, ImGuiTheme.COL_WARN, ImGuiTheme.COL_WARN_SOFT, kind, false);
    }

    private static Optional<Banner> install(StoreView view) {
        return switch (view.install()) {
            case InstallActivity.Idle ignored -> Optional.empty();
            case InstallActivity.Installing i -> Optional.of(new Banner(null, "Installing " + i.count() + " script"
                    + (i.count() == 1 ? "" : "s") + "…", ImGuiTheme.COL_INFO, ImGuiTheme.COL_INFO_SOFT, Kind.NONE,
                    true));
            case InstallActivity.Finished f -> Optional.of(new Banner(Icons.CIRCLE_CHECK, f.message(),
                    ImGuiTheme.COL_ACCENT, ImGuiTheme.COL_ACCENT_SOFT, Kind.DISMISS, false));
            // The host-cannot-install strip already says this; do not say it twice.
            case InstallActivity.Failed f when !view.canInstall() && f.message().equals(LiveStoreModel.DELIVERY_DISABLED)
                    -> Optional.empty();
            case InstallActivity.Failed f -> Optional.of(new Banner(Icons.WARNING, f.message(),
                    ImGuiTheme.COL_DANGER, ImGuiTheme.COL_DANGER_SOFT, Kind.DISMISS, false));
        };
    }

    private void strip(Banner b, StoreActions actions, float x, float y, float width, int index) {
        ImGuiTheme.Metrics m = ui.m();
        float h = stripHeight();
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + width, y + h, b.bg(), m.radius());
        float tx = x + m.u(3);
        if (b.isBusy()) {
            w.spinner(draw, tx + m.u(1.5f), y + h * 0.5f, m.u(1.5f), b.fg());
            tx += m.u(3) + m.u(2);
        } else {
            ui.textCentredY(draw, ui.fonts().caption(), tx, y, h, b.fg(), b.icon());
            tx += ui.width(ui.fonts().caption(), b.icon()) + m.u(2);
        }
        float actionW = actionWidth(b.kind());
        float room = x + width - m.u(2) - actionW - m.u(2) - tx;
        ui.textCentredY(draw, ui.fonts().small(), tx, y, h, b.fg(), ui.ellipsize(ui.fonts().small(), b.text(), room));
        float bx = x + width - m.u(1) - actionW;
        ImGui.setCursorScreenPos(bx, y + m.u(1));
        switch (b.kind()) {
            case RETRY -> {
                if (ui.button("##banner-retry-" + index, Icons.ROTATE, TRY_AGAIN, Tone.GHOST, true,
                        m.controlSmallHeight())) {
                    actions.refresh();
                }
            }
            case DISMISS -> {
                if (w.link("##banner-dismiss-" + index, Icons.XMARK, "Dismiss", true, m.controlSmallHeight())) {
                    actions.dismissInstallMessage();
                }
            }
            case NONE -> { }
        }
    }

    private float actionWidth(Kind kind) {
        return switch (kind) {
            case RETRY -> ui.buttonWidth(Icons.ROTATE, TRY_AGAIN, Tone.GHOST);
            case DISMISS -> w.linkWidth(Icons.XMARK, "Dismiss");
            case NONE -> 0f;
        };
    }
}
