package com.botwithus.bot.cli.gui.pages.dashboard;

import java.util.List;

/**
 * What the Dashboard page reads and what it can do. The live model reads the
 * host; the preview's fixture model returns canned views, so every state of the
 * page can be drawn with no game client.
 *
 * <p>Every read runs on the render thread every frame the page (or the tab) is
 * shown, and returns an immutable snapshot.</p>
 */
public interface DashboardModel {

    /** The page body for {@code scope}. */
    DashboardView view(Scope scope);

    /** The Logs tab for {@code scope}, at {@code level}. */
    LogsView logs(Scope scope, LogLevel level);

    /** The Events tab for {@code scope}, oldest first. */
    List<EventRow> events(Scope scope);

    /** The Console tab. */
    ConsoleView console();

    DashboardActions actions();
}
