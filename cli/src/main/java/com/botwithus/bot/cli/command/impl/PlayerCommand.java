package com.botwithus.bot.cli.command.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.api.snapshot.Skill;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.command.Command;
import com.botwithus.bot.cli.command.ParsedCommand;

import java.io.PrintStream;
import java.util.List;

/**
 * Debug command: prints the local player's position and live state from the
 * current SHM snapshot. Reads only the tick-scoped {@link GameSnapshot} — it
 * issues no RPC, so it's safe to run at any time.
 */
public class PlayerCommand implements Command {

    private static final int STATE_LOGIN = 10;
    private static final int STATE_LOBBY = 20;
    private static final int STATE_INGAME = 30;

    @Override public String name() { return "player"; }
    @Override public List<String> aliases() { return List.of("self", "pos"); }
    @Override public String description() { return "Print local player position and state (debug)"; }
    @Override public String usage() { return "player [skills]"; }

    @Override
    public void execute(ParsedCommand parsed, CliContext ctx) {
        Connection conn = ctx.getActiveConnection();
        if (conn == null) {
            ctx.out().println("No active connection. Use 'connect' first.");
            return;
        }

        GameAPI gameAPI = conn.getGameAPI();
        if (gameAPI == null) {
            ctx.out().println("Game API not available for this connection.");
            return;
        }

        GameSnapshot snap = gameAPI.snapshot();
        if (snap == null) {
            ctx.out().println("Snapshot not available (no shared-memory region bound).");
            return;
        }

        LocalPlayer self = snap.self();
        if (self == null) {
            ctx.out().printf("Not in-game — game state %d (%s), own index %d.%n",
                    snap.gameState(), gameStateLabel(snap.gameState()), snap.ownIndex());
            return;
        }

        String sub = parsed.arg(0);
        if ("skills".equals(sub)) {
            printSkills(ctx.out(), self.skills());
        } else {
            printPlayer(ctx.out(), snap, self);
        }
    }

    // The GUI console renders with a proportional font (Inter / Segoe UI), so
    // space-padded column alignment renders ragged ("warped"). Keep every line
    // self-contained as "label: value" with no padding — reads cleanly in both
    // the proportional GUI and a monospace terminal.
    private void printPlayer(PrintStream out, GameSnapshot snap, LocalPlayer self) {
        out.println("Local Player");
        out.printf("  Game state: %d (%s)%n", snap.gameState(), gameStateLabel(snap.gameState()));
        out.printf("  Tick: %d%n", snap.tickId());
        out.printf("  Server index: %d%n", self.serverIndex());
        out.printf("  Position: %d, %d (plane %d)%n", self.tileX(), self.tileY(), self.plane());
        out.printf("  Combat level: %d%n", self.combatLevel());
        out.printf("  Moving: %b%n", self.isMoving());
        out.printf("  Flags: 0x%X%n", self.flags());
        out.printf("  Animation: %d%n", self.animationId());
        out.printf("  Stance: %d%n", self.stanceId());
        out.printf("  Following: %d%n", self.followingIndex());
        out.printf("  Target: %d (type %d)%n", self.targetIndex(), self.targetType());
        out.printf("  Member: %b%n", self.isMember());
        out.printf("  Skills: %d tracked, total level %d%n",
                self.skills().size(), totalLevel(self.skills()));
        out.printf("  Scene version: %d%n", snap.sceneVersion());
        out.println("Use 'player skills' for the full skills table.");
    }

    // One self-contained line per skill (no aligned columns) — see printPlayer.
    private void printSkills(PrintStream out, List<Skill> skills) {
        if (skills.isEmpty()) {
            out.println("No skills in snapshot.");
            return;
        }
        out.printf("Skills (%d):%n", skills.size());
        for (Skill skill : skills) {
            out.printf("  type %d: level %d/%d, xp %d%n",
                    skill.typeId(), skill.actualLevel(), skill.boostedLevel(), skill.experience());
        }
        out.printf("Total level: %d%n", totalLevel(skills));
    }

    private static int totalLevel(List<Skill> skills) {
        int total = 0;
        for (Skill skill : skills) {
            total += skill.actualLevel();
        }
        return total;
    }

    private static String gameStateLabel(int gameState) {
        return switch (gameState) {
            case STATE_LOGIN -> "login";
            case STATE_LOBBY -> "lobby";
            case STATE_INGAME -> "in-game";
            default -> "unknown";
        };
    }
}
