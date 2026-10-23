package com.botwithus.bot.cli.gui.pages.groups;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Which rows of a list are ticked. Lives as long as the page, not a frame, so a
 * tick stays ticked while the list is redrawn; that is what the old Groups panel
 * got wrong, recreating its selection every frame so it always acted on the
 * first entry.
 *
 * <p>The ticks belong to one scope at a time, such as the group being shown:
 * asking about another scope forgets them. A tick on a key that has left the
 * list is forgotten too, so a removed member cannot come back ticked.</p>
 *
 * <p>Render thread only.</p>
 *
 * @param <S> what the ticks belong to
 */
public final class TickedKeys<S> {

    private final Set<String> keys = new HashSet<>();
    private S scope;

    /**
     * The ticked keys of {@code present}, in its order, in scope {@code of}.
     * Forgets every tick on a key that is not in {@code present}.
     */
    public List<String> ticked(S of, List<String> present) {
        enter(of);
        keys.retainAll(new HashSet<>(present));
        return present.stream().filter(keys::contains).toList();
    }

    public boolean isTicked(S of, String key) {
        return Objects.equals(scope, of) && keys.contains(key);
    }

    /** Ticks {@code key} if it is not ticked, else unticks it. */
    public void toggle(S of, String key) {
        enter(of);
        if (!keys.remove(key)) {
            keys.add(key);
        }
    }

    /** Ticks, or unticks, every one of {@code all}. */
    public void setAll(S of, List<String> all, boolean isOn) {
        enter(of);
        if (isOn) {
            keys.addAll(all);
        } else {
            all.forEach(keys::remove);
        }
    }

    /** Unticks everything. */
    public void clear() {
        keys.clear();
    }

    /** Makes {@code of} the scope, forgetting the ticks of any other. */
    private void enter(S of) {
        Objects.requireNonNull(of, "of");
        if (!of.equals(scope)) {
            keys.clear();
            scope = of;
        }
    }
}
