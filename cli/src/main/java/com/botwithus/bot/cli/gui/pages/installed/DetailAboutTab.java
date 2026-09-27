package com.botwithus.bot.cli.gui.pages.installed;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;

import java.util.List;

/**
 * The detail pane's About tab: the description, the update panel when the
 * Store has a newer build, where the script is on disk and where it came from,
 * and what it offers to configure.
 */
final class DetailAboutTab {

    private static final float PARAGRAPH_LINE = 1.55f;
    private static final float KV_LINE = 1.6f;
    private static final String NONE = "—";

    /** One line of a key-value list. */
    private record Entry(String key, String value) {

        static Entry of(String key, String value) {
            return new Entry(key, value);
        }
    }

    private final InstalledWidgets w;

    DetailAboutTab(InstalledWidgets widgets) {
        this.w = widgets;
    }

    /** Draws the tab from the cursor, {@code width} wide. */
    void render(InstalledView view, InstalledScript s, InstalledState state, float width) {
        ImGuiTheme.Metrics m = w.m();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        String description = s.identity().description().isBlank() ? "No description." : s.identity().description();
        float cy = paragraph(x, y, width, description, w.ui().fonts().small(), PARAGRAPH_LINE);
        if (s.provenance().update().isPresent()) {
            cy = updatePanel(s, s.provenance().update().get(), state, x, cy + m.u(4), width);
        }
        cy = section(x, cy + m.u(4), width, "On disk", disk(view, s));
        cy = section(x, cy + m.u(4), width, "Script", script(s));
        String note = noteFor(s);
        if (!note.isEmpty()) {
            cy = paragraph(x, cy + m.u(4), width, note, w.ui().fonts().caption(), PARAGRAPH_LINE);
        }
        ImGui.setCursorScreenPos(x, y);
        ImGui.dummy(width, cy - y);
    }

    private static List<Entry> disk(InstalledView view, InstalledScript s) {
        Provenance p = s.provenance();
        String file = p.jarName().map(j -> view.header().folderLabel() + j)
                .orElse(p.source() == ScriptSource.STORE ? "none: held in memory" : NONE);
        String source = p.source() == ScriptSource.STORE ? "Script Store" : "local build";
        String version = ScriptRowPainter.versionLabel(s.identity().version());
        return List.of(Entry.of("File", file), Entry.of("Source", source),
                Entry.of("Changed", p.changed().orElse(NONE)),
                Entry.of("Version", version.isEmpty() ? NONE : version));
    }

    private static List<Entry> script(InstalledScript s) {
        ScriptIdentity id = s.identity();
        String settings = id.settingsCount() == 0 ? "none" : InstalledView.plural(id.settingsCount(), "field");
        if (id.hasUi()) {
            settings += " + custom UI";
        }
        return List.of(Entry.of("Category", id.categoryLabel()), Entry.of("Settings", settings),
                Entry.of("Author", id.author().isBlank() ? NONE : id.author()));
    }

    private static String noteFor(InstalledScript s) {
        if (s.provenance().isStoreNotLoaded()) {
            return "Store scripts are kept in memory, so a host restart unloads them. "
                    + "Install it again from the Script Store to use it.";
        }
        if (s.provenance().source() == ScriptSource.LOCAL && s.provenance().jar().isPresent()) {
            return "Local builds reload the moment the JAR changes while Watch folder is on.";
        }
        return "";
    }

    /** Wrapped text; returns the y under it. */
    private float paragraph(float x, float y, float width, String text, ImFont font, float lineHeight) {
        Controls ui = w.ui();
        float lh = font.getFontSize() * lineHeight;
        float ly = y;
        for (String line : ui.wrap(font, text, width)) {
            ui.text(ImGui.getWindowDrawList(), font, x, ly, ImGuiTheme.COL_FG2, line);
            ly += lh;
        }
        return ly;
    }

    /** A heading over key-value lines, keys left, values right in mono; returns the y under it. */
    private float section(float x, float y, float width, String title, List<Entry> entries) {
        Controls ui = w.ui();
        ImDrawList draw = ImGui.getWindowDrawList();
        w.heading(draw, x, y, title);
        ImFont key = ui.fonts().caption();
        ImFont value = ui.fonts().monoCaption();
        float lh = key.getFontSize() * KV_LINE;
        float ly = y + ui.fonts().captionMedium().getFontSize() + w.m().u(2);
        for (Entry e : entries) {
            float kw = ui.width(key, e.key()) + w.m().u(4);
            ui.text(draw, key, x, ly, ImGuiTheme.COL_FG2, e.key());
            String shown = ui.ellipsize(value, e.value(), width - kw);
            ui.text(draw, value, x + width - ui.width(value, shown), ly, ImGuiTheme.COL_FG, shown);
            w.tooltipOver(x + kw, ly, width - kw, lh, e.value());
            ly += lh;
        }
        return ly;
    }

    /** The Store's newer build, with Update showing the script in the Store; returns the y under it. */
    private float updatePanel(InstalledScript s, UpdateBadge update, InstalledState state, float x, float y,
                              float width) {
        ImGuiTheme.Metrics m = w.m();
        Controls ui = w.ui();
        ImDrawList draw = ImGui.getWindowDrawList();
        float bw = ui.buttonWidth(InstalledWidgets.ARROW_UP, "Update", Tone.SOFT);
        float textW = width - m.u(3) - m.u(2) * 2f - bw;
        List<String> detail = ui.wrap(ui.fonts().caption(), update.detail(), textW);
        float lh = ui.fonts().caption().getFontSize() * KV_LINE;
        float h = m.u(2) * 2f + ui.fonts().small().getFontSize() + m.u(1) + lh * detail.size();
        draw.addRectFilled(x, y, x + width, y + h, ImGuiTheme.COL_INFO_SOFT, m.radius());
        ui.text(draw, ui.fonts().small(), x + m.u(3), y + m.u(2), ImGuiTheme.COL_FG, update.headline());
        float ly = y + m.u(2) + ui.fonts().small().getFontSize() + m.u(1);
        for (String line : detail) {
            ui.text(draw, ui.fonts().caption(), x + m.u(3), ly, ImGuiTheme.COL_FG2, line);
            ly += lh;
        }
        ImGui.setCursorScreenPos(x + width - m.u(2) - bw, y + (h - m.controlSmallHeight()) * 0.5f);
        if (ui.button("##detail-update", InstalledWidgets.ARROW_UP, "Update", Tone.SOFT, true,
                m.controlSmallHeight())) {
            state.showInStore(s);
        }
        return y + h;
    }
}
