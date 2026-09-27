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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Which script the one shared inspector is open on, and on which tab. Both modes
 * read the same state, so there is only ever one inspector.
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
     * Opens the pending request, if any, and selects the page it docks beside.
     *
     * @return the mode to draw this frame: Advanced when that page is not Normal mode's
     */
    public AppMode route(PageRegistry pages, AppMode mode) {
        InspectorRequest next = pending.getAndSet(null);
        if (next == null) {
            return mode;
        }
        open(next.subject(), next.tab());
        PageId owner = next.subject().ownerPage();
        if (!pages.select(owner) || owner == PageRegistry.DEFAULT_PAGE) {
            return mode;
        }
        return AppMode.ADVANCED;
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
}
