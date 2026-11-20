package com.botwithus.bot.api.gameui;

/**
 * A panel: a container that lays its children out in a row or a column.
 */
public final class Panel extends InteractiveComponent {

    Panel(UiNode node) {
        super(node);
    }

    /** Applies {@code props}, all or nothing. */
    public void set(PanelProps props) {
        node().set(props);
    }

    /** The panel's own keys, read back through the client ({@code ui.get}). */
    public UiWidgetState.PanelState state() {
        return switch (node().widget()) {
            case UiWidgetState.PanelState s -> s;
            default -> throw new IllegalStateException("not PanelState keys: " + this);
        };
    }
}
