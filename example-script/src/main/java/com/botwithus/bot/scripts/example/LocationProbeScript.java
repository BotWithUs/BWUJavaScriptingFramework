package com.botwithus.bot.scripts.example;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.debug.ScriptContextPublisher;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.api.snapshot.Location;
import com.botwithus.bot.api.snapshot.LocationFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Phase B verification probe. Each tick reads
 * {@code api.snapshot().locations()} and logs:
 * <ul>
 *   <li>The total location count + producer sceneVersion.</li>
 *   <li>The first few rows in full, so all 9 fields can be eyeballed.</li>
 *   <li>Aggregate counts: direct vs combined-section, hidden, deleted, animating.</li>
 *   <li>Any locations whose {@code animationId != -1}, to catch animating
 *       scenery (open doors, harvested rocks).</li>
 * </ul>
 * <p>Drop into {@code scripts/} via {@code ./gradlew :example-script:build}
 * and start from the CLI. Stop manually after enough samples.</p>
 */
@ScriptManifest(
        name = "Location Probe",
        version = "1.0",
        author = "BotWithUs",
        description = "Logs snapshot.locations() count + sample rows each tick",
        category = ScriptCategory.UTILITY
)
public class LocationProbeScript implements BotScript {

    private static final Logger log = LoggerFactory.getLogger(LocationProbeScript.class);

    private static final int LOOP_DELAY_MS = 2000;
    private static final int SAMPLE_ROW_COUNT = 5;

    private GameAPI api;
    private ScriptContextPublisher publisher;
    private int loopCount;
    private int lastSceneVersion = -1;

    @Override
    public void onStart(ScriptContext ctx) {
        this.api = ctx.getGameAPI();
        this.publisher = ctx.getScriptContext();
        this.loopCount = 0;
        this.lastSceneVersion = -1;
        log.info("LocationProbe started");
    }

    @Override
    public int onLoop() {
        loopCount++;
        GameSnapshot snap = api.snapshot();
        if (snap == null) {
            log.warn("snapshot() returned null on loop {}", loopCount);
            publisher.trace("WARN", "snapshot() returned null on loop " + loopCount);
            return LOOP_DELAY_MS;
        }

        int count = snap.locations().count();
        int sceneVer = snap.sceneVersion();
        LocalPlayer me = snap.self();
        String where = me == null
                ? "(no self)"
                : "(" + me.tileX() + "," + me.tileY() + ",p" + me.plane() + ")";

        publisher.annotation("loop_count", loopCount);
        publisher.annotation("locations_count", count);
        publisher.annotation("scene_version", sceneVer);
        publisher.annotation("position", where);

        if (sceneVer != lastSceneVersion) {
            log.info("sceneVersion bumped {} -> {}", lastSceneVersion, sceneVer);
            publisher.trace("INFO", "sceneVersion bumped " + lastSceneVersion + " -> " + sceneVer);
            lastSceneVersion = sceneVer;
        }

        log.info("loop={} tick={} self={} locations.count={} sceneVer={}",
                loopCount, snap.serverTick(), where, count, sceneVer);

        if (count == 0) {
            return LOOP_DELAY_MS;
        }

        int directCount = snap.locations().filter(LocationFilter.direct()).size();
        int sectionCount = snap.locations().filter(LocationFilter.combinedSection()).size();
        List<Location> animating = snap.locations().filter(LocationFilter.animating());
        List<Location> hidden = snap.locations().filter(l -> l.isHidden());
        List<Location> deleted = snap.locations().filter(l -> l.isDeleted());
        log.info("  direct={} combined_section={} hidden={} deleted={} animating={}",
                directCount, sectionCount, hidden.size(), deleted.size(), animating.size());

        int sampleN = Math.min(SAMPLE_ROW_COUNT, count);
        for (int i = 0; i < sampleN; i++) {
            Location l = snap.locations().at(i);
            log.info("  [{}] type={} interact={} anim={} tile=({},{},p{}) shape={} rot={} flags=0x{}{}{}{}",
                    i,
                    l.typeId(), l.interactId(), l.animationId(),
                    l.tileX(), l.tileY(), l.plane(),
                    l.shape(), l.rotation(),
                    Integer.toHexString(l.flags()),
                    l.isHidden() ? " HIDDEN" : "",
                    l.isCombinedSection() ? " SECTION" : "",
                    l.isDeleted() ? " DELETED" : "");
        }

        for (Location a : animating) {
            log.info("  animating: type={} interact={} anim={} tile=({},{},p{})",
                    a.typeId(), a.interactId(), a.animationId(),
                    a.tileX(), a.tileY(), a.plane());
        }

        return LOOP_DELAY_MS;
    }

    @Override
    public void onStop() {
        log.info("LocationProbe stopped after {} loops", loopCount);
    }
}
