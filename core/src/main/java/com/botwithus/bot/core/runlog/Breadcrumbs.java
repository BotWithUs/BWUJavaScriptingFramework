package com.botwithus.bot.core.runlog;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The last {@value #CAPACITY} host-level events of one run (spec §1.3): RPC calls
 * the script made, its state changes, and the player's tile when it moved.
 *
 * <p>Details are kept raw and redacted only when rendered, so a name the host
 * learns after the crumb was dropped is still redacted, and clipped to
 * {@value #MAX_DETAIL} characters only after redaction, so the clip cannot split
 * a secret into a half the redactor no longer recognises. A raw detail longer
 * than {@value #MAX_RAW_DETAIL} characters is dropped to its length rather than
 * cut, for the same reason.</p>
 *
 * <p>Thread-safe: a script and the threads it spawns record into one ring.</p>
 */
public final class Breadcrumbs {

    public static final int CAPACITY = 200;
    /** Longest detail a rendered crumb carries, after redaction. */
    public static final int MAX_DETAIL = 200;
    private static final int MAX_RAW_DETAIL = 1_000;

    private final Clock clock;
    private final Deque<Crumb> ring = new ArrayDeque<>(CAPACITY);

    public Breadcrumbs(Clock clock) {
        this.clock = clock;
    }

    /** Records one event. {@code kind} is a single word such as {@code rpc}, {@code state}, {@code tile}. */
    public void add(String kind, String detail) {
        String raw = detail == null ? "" : detail;
        if (raw.length() > MAX_RAW_DETAIL) {
            raw = "<" + raw.length() + " chars>";
        }
        Crumb crumb = new Crumb(clock.instant(), kind, raw);
        synchronized (ring) {
            if (ring.size() == CAPACITY) {
                ring.removeFirst();
            }
            ring.addLast(crumb);
        }
    }

    /** Oldest first: {@code <ts> <kind> <detail>}, detail redacted then clipped. */
    public List<String> render(Redactor redactor) {
        List<Crumb> copy;
        synchronized (ring) {
            copy = new ArrayList<>(ring);
        }
        List<String> lines = new ArrayList<>(copy.size());
        for (Crumb crumb : copy) {
            String detail = redactor.redact(crumb.detail().replaceAll("\\R", " "));
            if (detail.length() > MAX_DETAIL) {
                detail = detail.substring(0, MAX_DETAIL);
            }
            lines.add(RunLogClock.format(crumb.at()) + " " + crumb.kind() + " " + detail);
        }
        return lines;
    }

    private record Crumb(Instant at, String kind, String detail) {
    }
}
