package com.botwithus.bot.cli.command.impl;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.command.Command;
import com.botwithus.bot.cli.command.ParsedCommand;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.InvalidSettingException;
import com.botwithus.bot.cli.settings.SaveStatus;
import com.botwithus.bot.cli.settings.SettingKey;

import java.io.PrintStream;
import java.util.List;
import java.util.Map;

/**
 * {@code config}: shows and edits {@link HostSettings}. {@code config set} saves at
 * once; {@code config save} is kept so existing habits and scripts still work, and
 * only confirms that everything is on disk.
 */
public class ConfigCommand implements Command {

    private final HostSettings settings;

    public ConfigCommand(HostSettings settings) {
        this.settings = settings;
    }

    @Override public String name() { return "config"; }
    @Override public List<String> aliases() { return List.of("cfg"); }
    @Override public String description() { return "View or change host settings (saved automatically)"; }
    @Override public String usage() { return "config [show | set <key> <value> | reset <key> | save]"; }

    @Override
    public void execute(ParsedCommand parsed, CliContext ctx) {
        String sub = parsed.arg(0);
        PrintStream out = ctx.out();
        if (sub == null || "show".equals(sub)) {
            showConfig(out);
            return;
        }
        switch (sub) {
            case "set" -> set(parsed.arg(1), parsed.arg(2), out);
            case "reset" -> reset(parsed.arg(1), out);
            case "save" -> reportSave(out);
            default -> out.println("Unknown subcommand: " + sub + ". Usage: " + usage());
        }
    }

    private void set(String name, String value, PrintStream out) {
        if (name == null || value == null) {
            out.println("Usage: config set <key> <value>");
            return;
        }
        SettingKey<?> key;
        try {
            key = settings.setText(name, value);
        } catch (InvalidSettingException e) {
            out.println("Not set: " + e.getMessage() + ".");
            return;
        }
        out.println("Set " + key.name() + " = " + settings.text(key));
        reportSave(out);
    }

    private void reset(String name, PrintStream out) {
        if (name == null) {
            out.println("Usage: config reset <key>");
            return;
        }
        var key = settings.find(name);
        if (key.isEmpty()) {
            out.println("Unknown setting '" + name + "'. Run 'config' to list them.");
            return;
        }
        settings.reset(key.get());
        out.println("Reset " + name + " to its default, " + settings.text(key.get()));
        reportSave(out);
    }

    private void reportSave(PrintStream out) {
        switch (settings.flush()) {
            case SaveStatus.Failed failed -> out.println("Warning: " + failed.message());
            case SaveStatus.Saved _, SaveStatus.Saving _ -> out.println("Saved to " + settings.file());
        }
    }

    private void showConfig(PrintStream out) {
        out.println("Host settings (" + settings.file() + "):");
        for (SettingKey<?> key : settings.keys()) {
            String marker = settings.isExplicit(key) ? "" : "  (default)";
            out.println("  " + key.name() + " = " + settings.text(key) + marker);
        }
        Map<String, String> unknown = settings.unknownEntries();
        if (!unknown.isEmpty()) {
            out.println("Other entries in the file (not used by this host, kept as-is):");
            unknown.forEach((name, text) -> out.println("  " + name + " = " + text));
        }
        out.println("Change one with: config set <key> <value>");
    }
}
