package com.botwithus.bot.cli.gui.pages.installed;

import com.botwithus.bot.cli.gui.nav.PageId;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * What the page remembers between frames (filters, the selected script and
 * tab, an armed "Stop everywhere", open stack traces, the Start-on dialog) and
 * the one place its clicks turn into model calls. Render thread only.
 */
final class InstalledState {

    /** The detail pane's two tabs. */
    enum DetailTab { CLIENTS, ABOUT }

    private final InstalledModel model;
    private final Consumer<PageId> navigate;
    private final Set<Path> openTraces = new HashSet<>();
    private final Set<String> ticked = new LinkedHashSet<>();
    private InstalledQuery query = InstalledQuery.DEFAULT;
    private String selectedKey;
    private DetailTab tab = DetailTab.CLIENTS;
    private String armedStopKey;
    private String startOnKey;
    private boolean isStartOnPending;

    InstalledState(InstalledModel model, Consumer<PageId> navigate) {
        this.model = model;
        this.navigate = navigate;
    }

    InstalledModel model() {
        return model;
    }

    // ── Filters and selection ──────────────────────────────────────────────

    InstalledQuery query() {
        return query;
    }

    void setQuery(InstalledQuery next) {
        query = next;
    }

    /**
     * The script the detail pane shows: the one picked, while it is still
     * listed, else the first row that passes the filters.
     */
    Optional<InstalledScript> selected(InstalledView view, List<InstalledScript> shown) {
        Optional<InstalledScript> picked = selectedKey == null ? Optional.empty() : view.find(selectedKey);
        if (picked.isPresent()) {
            return picked;
        }
        return shown.stream().findFirst();
    }

    boolean isSelected(InstalledScript script, Optional<InstalledScript> selected) {
        return selected.map(s -> s.key().equals(script.key())).orElse(false);
    }

    void select(String key) {
        selectedKey = key;
    }

    DetailTab tab() {
        return tab;
    }

    void showTab(DetailTab next) {
        tab = next;
    }

    // ── Stop everywhere, with an inline confirm ────────────────────────────

    boolean isStopArmed(String key) {
        return key.equals(armedStopKey);
    }

    /** The first click on Stop everywhere asks; the second, on the confirm button, stops. */
    void armStop(String key) {
        armedStopKey = key;
    }

    void disarmStop() {
        armedStopKey = null;
    }

    void confirmStop(String key) {
        armedStopKey = null;
        model.stopEverywhere(key);
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

    // ── Start on… dialog ───────────────────────────────────────────────────

    void openStartOn(String key) {
        startOnKey = key;
        ticked.clear();
        isStartOnPending = true;
    }

    /** True once, on the frame after {@link #openStartOn}: the dialog opens its popup then. */
    boolean takeStartOnOpening() {
        boolean was = isStartOnPending;
        isStartOnPending = false;
        return was;
    }

    Optional<String> startOnKey() {
        return Optional.ofNullable(startOnKey);
    }

    boolean isTicked(String clientId) {
        return ticked.contains(clientId);
    }

    void toggleTick(String clientId) {
        if (!ticked.remove(clientId)) {
            ticked.add(clientId);
        }
    }

    /** The ticked clients that can still take the script; one may have disconnected since it was ticked. */
    List<String> tickedAmong(List<StartTarget> targets) {
        return targets.stream()
                .filter(t -> t.isSelectable() && ticked.contains(t.clientId()))
                .map(StartTarget::clientId)
                .toList();
    }

    void closeStartOn() {
        startOnKey = null;
        ticked.clear();
    }

    /** Starts on the ticked clients, then shows the script's Clients tab so the starts can be watched. */
    void confirmStartOn(String key, List<String> clientIds) {
        closeStartOn();
        if (!clientIds.isEmpty()) {
            model.startOn(key, clientIds);
        }
        selectedKey = key;
        tab = DetailTab.CLIENTS;
    }

    // ── Navigation ─────────────────────────────────────────────────────────

    /** The Store is where a script is installed again or updated, and where more are found. */
    void openStore() {
        navigate.accept(PageId.STORE);
    }

    void openManagement() {
        navigate.accept(PageId.MANAGEMENT);
    }
}
