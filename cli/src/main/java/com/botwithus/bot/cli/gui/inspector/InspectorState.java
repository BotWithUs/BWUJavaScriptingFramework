package com.botwithus.bot.cli.gui.inspector;

import com.botwithus.bot.cli.gui.AppMode;
import com.botwithus.bot.cli.gui.nav.PageId;
import com.botwithus.bot.cli.gui.nav.PageRegistry;
import com.botwithus.bot.cli.management.Target;
import com.botwithus.bot.core.runtime.ManagementScriptRunner;
import com.botwithus.bot.core.runtime.ScriptRunner;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Which script the one shared inspector is open on, and on which tab. Both modes
 * read the same state, so there is only ever one inspector. It also owns which
 * scripts' own UIs are popped out into windows of their own, since a "Settings"
 * request may open the drawer, pop a UI out, or both.
 *
 * <p>Everything here runs on the render thread except {@link #request}, which
 * any thread may call: console commands run on the command executor. A request
 * waits until the next frame's {@link #route}, which opens it and switches to
 * the page it docks beside.</p>
 */
public final class InspectorState {

    /**
     * How long a subject that does not resolve yet stays open. A script started
     * with "Review settings" may take a moment to register its runner; without
     * this the inspector would close before its script arrived.
     */
    static final Duration STARTING_GRACE = Duration.ofSeconds(3);

    private final InstantSource clock;
    private final AtomicReference<InspectorRequest> pending = new AtomicReference<>();
    private final ScriptUiPopouts popouts = new ScriptUiPopouts();
    private InspectorSubject subject;
    private InspectorTab tab = InspectorTab.SETTINGS;
    private Instant openedAt = Instant.EPOCH;
    private boolean hasResolved;

    public InspectorState(InstantSource clock) {
        this.clock = clock;
    }

    /** Asks for the inspector on the next frame. Any thread; the latest request wins. */
    public void request(InspectorRequest request) {
        pending.set(request);
    }

    /** What the host's "Settings" on a client script calls. Any thread. */
    public Consumer<ScriptRunner> clientScriptOpener() {
        return runner -> request(InspectorRequest.forClientScript(runner));
    }

    /** What the host's "Settings" on a management script calls. Any thread. */
    public Consumer<ManagementScriptRunner> managementScriptOpener() {
        return runner -> request(InspectorRequest.forManagementScript(runner));
    }

    /**
     * Shows the pending request, if any. When it opens the drawer, also selects
     * the page the drawer docks beside; a request that only pops a script's UI
     * out leaves the page and the mode alone, as that window floats over any page.
     *
     * @return the mode to draw this frame: Advanced when that page is not Normal mode's
     */
    public AppMode route(PageRegistry pages, AppMode mode) {
        InspectorRequest next = pending.getAndSet(null);
        if (next == null) {
            return mode;
        }
        show(next);
        if (next.drawer().isEmpty()) {
            return mode;
        }
        PageId owner = next.subject().ownerPage();
        if (!pages.select(owner) || owner == PageRegistry.DEFAULT_PAGE) {
            return mode;
        }
        return AppMode.ADVANCED;
    }

    /**
     * Shows {@code request} now, without switching page: pops the script's UI
     * out when asked, and opens the drawer on the tab it names. Render thread;
     * a caller already on the owning page uses this rather than {@link #request}.
     */
    public void show(InspectorRequest request) {
        if (request.popsOutUi()) {
            popouts.popOut(request.subject());
        }
        request.drawer().ifPresent(drawerTab -> open(request.subject(), drawerTab));
    }

    /** Opens the inspector on {@code subject}, replacing whatever it showed. */
    public void open(InspectorSubject subject, InspectorTab tab) {
        this.subject = subject;
        this.tab = tab;
        this.openedAt = clock.instant();
        this.hasResolved = false;
    }

    public void close() {
        subject = null;
    }

    /**
     * Points an open management script's form at {@code target}'s own
     * settings, or at the defaults when empty: the "Settings for" picker. Each
     * target is its own subject, so its form starts afresh. Does nothing when
     * the inspector is closed or open on a client script.
     */
    public void pickSettingsFor(Optional<Target> target) {
        switch (subject) {
            case null -> { }
            case InspectorSubject.ManagementScript script -> open(script.withSettingsFor(target), InspectorTab.SETTINGS);
            case InspectorSubject.ClientScript _ -> { }
        }
    }

    public boolean isOpen() {
        return subject != null;
    }

    public Optional<InspectorSubject> subject() {
        return Optional.ofNullable(subject);
    }

    public InspectorTab tab() {
        return tab;
    }

    public void showTab(InspectorTab next) {
        tab = next;
    }

    /** Whether the inspector docks beside {@code shown}: it is open and that is its page. */
    public boolean docksBeside(PageId shown) {
        return subject != null && subject.ownerPage() == shown;
    }

    /**
     * The open subject's script as it is now. Closes the inspector once its runner
     * is disposed or gone; a subject that has not resolved yet gets
     * {@link #STARTING_GRACE} to appear first.
     */
    public Optional<InspectorTarget> resolve(InspectorSource source) {
        if (subject == null) {
            return Optional.empty();
        }
        Optional<InspectorTarget> target = source.resolve(subject);
        if (target.isPresent() && !target.get().isGone().getAsBoolean()) {
            hasResolved = true;
            return target;
        }
        if (!hasResolved && clock.instant().isBefore(openedAt.plus(STARTING_GRACE))) {
            return Optional.empty();
        }
        close();
        return Optional.empty();
    }

    // -- Popped-out script UIs ----------------------------------------------

    /** Opens {@code subject}'s own UI in a window of its own. */
    void popOut(InspectorSubject subject) {
        popouts.popOut(subject);
    }

    /** Closes {@code subject}'s window, if open; its UI draws in the drawer's Script UI tab again. */
    void bringBack(InspectorSubject subject) {
        popouts.bringBack(subject);
    }

    boolean isPoppedOut(InspectorSubject subject) {
        return popouts.isPoppedOut(subject);
    }

    /** The drawer is showing {@code subject}'s Script UI tab: see {@link ScriptUiPopouts#reopenIfRemembered}. */
    void reopenIfRemembered(InspectorSubject subject) {
        popouts.reopenIfRemembered(subject);
    }

    /**
     * The popped-out windows to draw this frame, each script as it is now.
     * A window whose runner is disposed or gone closes here.
     */
    List<InspectorTarget> poppedOut(InspectorSource source) {
        return popouts.resolve(source);
    }
}
