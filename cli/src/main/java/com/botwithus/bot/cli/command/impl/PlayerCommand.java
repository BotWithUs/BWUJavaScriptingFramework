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
            ctx.out().println(String.format("Not in-game — game state %d (%s), own index %d.",
                    snap.gameState(), gameStateLabel(snap.gameState()), snap.ownIndex()));
            return;
        }

        String sub = parsed.arg(0);
        if ("skills".equals(sub)) {
            printSkills(ctx.out(), self.skills());
        } else {
            printPlayer(ctx.out(), snap, self);
        }
    }

    // The GUI console's PrintStream commits a rendered line on each write from
    // java.util.Formatter, so a single printf() to ctx.out() fragments into one
    // line per format chunk ("  Target: " / "0" / " (type " / ...). Build each
    // line fully with String.format first, then emit it with one println().
    private void printPlayer(PrintStream out, GameSnapshot snap, LocalPlayer self) {
        out.println("Local Player");
        out.println(String.format("  Game state: %d (%s)", snap.gameState(), gameStateLabel(snap.gameState())));
        out.println(String.format("  Server tick: %d", snap.serverTick()));
        out.println(String.format("  Game cycle: %d", snap.gameCycle()));
        out.println(String.format("  Server index: %d", self.serverIndex()));
        out.println(String.format("  Position: %d, %d (plane %d)", self.tileX(), self.tileY(), self.plane()));
        out.println(String.format("  Combat level: %d", self.combatLevel()));
        out.println(String.format("  Moving: %b", self.isMoving()));
        out.println(String.format("  Flags: 0x%X", self.flags()));
        out.println(String.format("  Animation: %d", self.animationId()));
        out.println(String.format("  Stance: %d", self.stanceId()));
        out.println(String.format("  Following: %d", self.followingIndex()));
        out.println(String.format("  Target: %d (type %d)", self.targetIndex(), self.targetType()));
        out.println(String.format("  Member: %b", self.isMember()));
        out.println(String.format("  Skills: %d tracked, total level %d",
                self.skills().size(), totalLevel(self.skills())));
        out.println(String.format("  Scene version: %d", snap.sceneVersion()));
        out.println("Use 'player skills' for the full skills table.");
    }

    // One self-contained line per skill — build with String.format, see printPlayer.
    private void printSkills(PrintStream out, List<Skill> skills) {
        if (skills.isEmpty()) {
            out.println("No skills in snapshot.");
            return;
        }
        out.println(String.format("Skills (%d):", skills.size()));
        for (Skill skill : skills) {
            out.println(String.format("  type %d: level %d/%d, xp %d",
                    skill.typeId(), skill.actualLevel(), skill.boostedLevel(), skill.experience()));
        }
        out.println(String.format("Total level: %d", totalLevel(skills)));
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
