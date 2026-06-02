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

    private void printPlayer(PrintStream out, GameSnapshot snap, LocalPlayer self) {
        out.println("Local Player");
        out.println("-".repeat(46));
        out.printf("  %-14s: %d (%s)%n", "Game state", snap.gameState(), gameStateLabel(snap.gameState()));
        out.printf("  %-14s: %d%n", "Tick id", snap.tickId());
        out.printf("  %-14s: %d%n", "Server index", self.serverIndex());
        out.printf("  %-14s: (%d, %d, plane %d)%n", "Position", self.tileX(), self.tileY(), self.plane());
        out.printf("  %-14s: %d%n", "Combat level", self.combatLevel());
        out.printf("  %-14s: %b%n", "Moving", self.isMoving());
        out.printf("  %-14s: 0x%X%n", "Flags", self.flags());
        out.printf("  %-14s: %d%n", "Animation", self.animationId());
        out.printf("  %-14s: %d%n", "Stance", self.stanceId());
        out.printf("  %-14s: %d%n", "Following", self.followingIndex());
        out.printf("  %-14s: %d (type %d)%n", "Target", self.targetIndex(), self.targetType());
        out.printf("  %-14s: %b%n", "Member", self.isMember());
        out.printf("  %-14s: %d tracked, total level %d%n", "Skills",
                self.skills().size(), totalLevel(self.skills()));
        out.printf("  %-14s: %d%n", "Scene version", snap.sceneVersion());
        out.println("Use 'player skills' for the full skills table.");
    }

    private void printSkills(PrintStream out, List<Skill> skills) {
        if (skills.isEmpty()) {
            out.println("No skills in snapshot.");
            return;
        }
        out.printf("%-8s %8s %8s %14s%n", "TypeId", "Actual", "Boosted", "Experience");
        out.println("-".repeat(42));
        for (Skill skill : skills) {
            out.printf("%-8d %8d %8d %14d%n",
                    skill.typeId(), skill.actualLevel(), skill.boostedLevel(), skill.experience());
        }
        out.printf("%nTotal level: %d%n", totalLevel(skills));
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
