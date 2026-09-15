package com.botwithus.bot.skilling.script;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.Navigation;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.inventory.Backpack;
import com.botwithus.bot.api.model.PlayerStat;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.skilling.atlas.Atlas;
import com.botwithus.bot.skilling.atlas.AtlasPaths;
import com.botwithus.bot.skilling.banking.Banking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for skill-focused scripts. Wires the common surface every skill
 * needs — the {@link GameAPI}, blocking {@link Navigation}, the {@link Backpack},
 * the {@link Atlas} data layer, and the {@link Banking} helper — and exposes the
 * player-state gates (idle / level / xp) scripts check each tick.
 *
 * <p>Subclasses implement {@link #onTick()} (the loop body) and declare a
 * {@link #capability()} so the future cross-skill orchestrator can route work to
 * them. {@link #onStart} is sealed; per-script init goes in {@link #onSetup()}.</p>
 *
 * <p>The Atlas is opened from {@code ~/.botwithus/native/resolved.sqlite} (or the
 * {@code -Dbotwithus.atlas} override). When it is absent the script still runs in
 * a degraded "scene-only" mode — it can act on what's loaded around the player but
 * cannot walk to off-screen resources or banks.</p>
 */
public abstract class SkillScript implements BotScript {

    protected SkillScript() {}

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected ScriptContext ctx;
    protected GameAPI api;
    protected Navigation nav;
    protected Backpack backpack;
    /** The baked data layer, or {@code null} when no {@code resolved.sqlite} was found. */
    protected Atlas atlas;
    protected Banking banking;

    /** What this script gathers/produces — used by the orchestrator for routing. */
    protected abstract Capability capability();

    /** Per-script initialisation, after the common surface is wired. */
    protected void onSetup() {}

    /** The loop body. Return the delay in ms before the next call, or {@code -1} to stop. */
    protected abstract int onTick();

    @Override
    public final void onStart(ScriptContext ctx) {
        this.ctx = ctx;
        this.api = ctx.getGameAPI();
        this.nav = ctx.getNavigation();
        this.backpack = api.backpack();
        this.atlas = Atlas.openDefault().orElse(null);
        if (atlas == null) {
            log.warn("No Atlas (resolved.sqlite) at {} — scene-only mode (no world walking / "
                            + "data enrichment). Set -Dbotwithus.atlas=<path> or let the launcher "
                            + "place it in ~/.botwithus/native/.",
                    AtlasPaths.defaultPath());
        } else {
            log.info("Atlas loaded: {} gather spots, {} npc locations",
                    atlas.meta("gather_spots").orElse("?"), atlas.meta("npc_locations").orElse("?"));
        }
        this.banking = new Banking(api, nav, atlas);
        onSetup();
    }

    @Override
    public int onLoop() {
        try {
            return onTick();
        } catch (RuntimeException e) {
            log.error("error in onTick", e);
            return 5000;
        }
    }

    @Override
    public void onStop() {
        if (atlas != null) {
            atlas.close();
        }
    }

    // ------------------------------------------------------------- helpers

    // All player-state helpers tolerate being called before the script is bound
    // (api == null): the host renders a script's getUI() tab even when the script
    // isn't running, so these return safe "not in-game" defaults rather than NPE.
    // Script authors never have to null-check api when using these.

    protected LocalPlayer player() {
        return api == null ? null : api.getLocalPlayer();
    }

    protected boolean inGame() {
        return player() != null;
    }

    /** True when the player is neither animating nor moving — safe to act. */
    protected boolean isIdle() {
        LocalPlayer p = player();
        return p != null && p.animationId() == -1 && !p.isMoving();
    }

    /** Base (unboosted) level in a skill, or {@code -1} when not bound / in-game. */
    protected int level(int skillId) {
        if (api == null) {
            return -1;
        }
        PlayerStat s = api.getPlayerStat(skillId);
        return s == null ? -1 : s.level();
    }

    /** Total xp in a skill, or {@code -1} when not bound / in-game. */
    protected int xp(int skillId) {
        if (api == null) {
            return -1;
        }
        PlayerStat s = api.getPlayerStat(skillId);
        return s == null ? -1 : s.experience();
    }
}
