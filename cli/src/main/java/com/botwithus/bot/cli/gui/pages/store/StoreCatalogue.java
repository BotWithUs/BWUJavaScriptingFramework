package com.botwithus.bot.cli.gui.pages.store;

import com.botwithus.bot.core.sdn.SdnCatalogueRefresher;
import com.botwithus.bot.core.sdn.SdnCatalogueSource;

import java.time.InstantSource;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The host's one catalogue loop. The Store page and Normal mode's "Your
 * subscriptions" group read the same refresher, so the launcher is asked once.
 *
 * <p>The refresher owns no timer: whoever shows the catalogue ticks it each frame,
 * which also covers coming back to the page after the interval passed, and a
 * background ticker asks while nothing is showing it. Fetches run on their own
 * virtual thread, never the render thread or the shared command executor, because
 * each can wait seconds for the launcher.</p>
 */
public final class StoreCatalogue {

    /** How often the background ticker asks whether a fetch is due; a tick is cheap. */
    private static final long BACKGROUND_TICK_SECONDS = 15;

    private StoreCatalogue() {}

    /** The host's catalogue refresher over {@code source}, fetching each time on its own virtual thread. */
    public static SdnCatalogueRefresher refresherFor(SdnCatalogueSource source) {
        return new SdnCatalogueRefresher(source::fetch, virtualThreadPerFetch(), InstantSource.system(),
                new Random()::nextDouble);
    }

    /** Starts ticking {@code refresher} in the background; shut the returned scheduler down on exit. */
    public static ScheduledExecutorService tickInBackground(SdnCatalogueRefresher refresher) {
        ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("sdn-catalogue-ticker").factory());
        ticker.scheduleWithFixedDelay(refresher::tick, BACKGROUND_TICK_SECONDS, BACKGROUND_TICK_SECONDS,
                TimeUnit.SECONDS);
        return ticker;
    }

    private static Executor virtualThreadPerFetch() {
        return task -> Thread.ofVirtual().name("sdn-catalogue-fetch").start(task);
    }
}
