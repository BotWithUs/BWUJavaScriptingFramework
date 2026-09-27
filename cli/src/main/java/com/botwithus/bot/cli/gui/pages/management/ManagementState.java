package com.botwithus.bot.cli.gui.pages.management;

import com.botwithus.bot.cli.gui.nav.PageId;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * What the page remembers between frames (the selected script and tab, an
 * armed Stop all, open stack traces, the add-target row, a target change
 * waiting for its confirm) and the one place its clicks turn into model
 * calls. Render thread only.
 */
final class ManagementState {

    /** The detail pane's tabs; Script UI shows only for a script that has one. */
    enum DetailTab {
        OVERVIEW("Overview"),
        SETTINGS("Settings"),
        SCRIPT_UI("Script UI"),
        ACTIVITY("Activity");

        private final String label;

        DetailTab(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    /**
     * A target change on a running script, held until the user confirms the
     * restart it brings.
     *
     * @param label the target as the confirm names it
     */
    record PendingChange(String script, TargetChange change, String label) {
        PendingChange {
            Objects.requireNonNull(script, "script");
            Objects.requireNonNull(change, "change");
            Objects.requireNonNull(label, "label");
        }
    }

    private final ManagementModel model;
    private final Consumer<PageId> navigate;
    private final Set<Path> openTraces = new HashSet<>();
    private String selectedName;
    private DetailTab tab = DetailTab.OVERVIEW;
    private boolean isStopAllArmed;
    private TargetKind addKind = TargetKind.CLIENT_SCRIPT;
    private int addPick;
    private PendingChange pending;

    ManagementState(ManagementModel model, Consumer<PageId> navigate) {
        this.model = model;
        this.navigate = navigate;
    }

    ManagementModel model() {
        return model;
    }

    // ── Selection and tabs ─────────────────────────────────────────────────

    /** The script the detail pane shows: the one picked, while it is still loaded, else the first. */
    Optional<ManagementRow> selected(ManagementView view) {
        Optional<ManagementRow> picked = selectedName == null ? Optional.empty() : view.find(selectedName);
        return picked.isPresent() ? picked : view.scripts().stream().findFirst();
    }

    void select(String name) {
        if (!name.equals(selectedName)) {
            addPick = 0;
        }
        selectedName = name;
    }

    /** The tabs {@code row} offers, in order. */
    static List<DetailTab> tabsFor(ManagementRow row) {
        List<DetailTab> tabs = new ArrayList<>(List.of(DetailTab.OVERVIEW, DetailTab.SETTINGS));
        if (row.about().hasUi()) {
            tabs.add(DetailTab.SCRIPT_UI);
        }
        tabs.add(DetailTab.ACTIVITY);
        return tabs;
    }

    /** The tab to show for {@code row}: the one picked, unless {@code row} does not offer it. */
    DetailTab tab(ManagementRow row) {
        return tabsFor(row).contains(tab) ? tab : DetailTab.OVERVIEW;
    }

    void showTab(DetailTab next) {
        tab = next;
    }

    // ── Stop all, with an inline confirm ───────────────────────────────────

    boolean isStopAllArmed() {
        return isStopAllArmed;
    }

    void armStopAll() {
        isStopAllArmed = true;
    }

    void disarmStopAll() {
        isStopAllArmed = false;
    }

    void confirmStopAll() {
        isStopAllArmed = false;
        model.stopAll();
    }

    // ── Targets ────────────────────────────────────────────────────────────

    /**
     * Changes {@code row}'s targets. A stopped script changes at once; a
     * running one waits for {@link #confirmChange}, since the change restarts it.
     */
    void requestChange(ManagementRow row, TargetChange change, String label) {
        if (row.health().state().isRunning()) {
            pending = new PendingChange(row.name(), change, label);
            return;
        }
        pending = null;
        model.changeTargets(row.name(), change, false);
    }

    /** The change waiting on {@code script}'s confirm, if any. */
    Optional<PendingChange> pendingFor(String script) {
        return Optional.ofNullable(pending).filter(p -> p.script().equals(script));
    }

    /** Makes the waiting change and restarts the script. */
    void confirmChange() {
        PendingChange confirmed = pending;
        pending = null;
        if (confirmed != null) {
            model.changeTargets(confirmed.script(), confirmed.change(), true);
        }
    }

    void cancelChange() {
        pending = null;
    }

    TargetKind addKind() {
        return addKind;
    }

    void setAddKind(TargetKind next) {
        if (next != addKind) {
            addPick = 0;
        }
        addKind = next;
    }

    int addPick() {
        return addPick;
    }

    void setAddPick(int next) {
        addPick = next;
    }

    /** What the add row's second choice offers for {@code row} now. */
    List<TargetChoices.Option> addOptions(ManagementRow row, TargetChoices choices) {
        return addKind.options(row, choices);
    }

    /** Adds the add row's picked target to {@code row}, as {@link #requestChange} does. */
    void addPicked(ManagementRow row, TargetChoices choices) {
        List<TargetChoices.Option> options = addOptions(row, choices);
        if (addPick < 0 || addPick >= options.size()) {
            return;
        }
        TargetChoices.Option picked = options.get(addPick);
        addPick = 0;
        requestChange(row, new TargetChange.Add(picked.target()), picked.label());
    }

    // ── Stack traces ───────────────────────────────────────────────────────

    boolean isTraceOpen(Path jar) {
        return openTraces.contains(jar);
    }

    void toggleTrace(Path jar) {
        if (!openTraces.remove(jar)) {
            openTraces.add(jar);
        }
    }

    // ── Elsewhere ──────────────────────────────────────────────────────────

    /** A group chip leads to the Groups page. */
    void openGroups() {
        navigate.accept(PageId.GROUPS);
    }
}
