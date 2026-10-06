package com.botwithus.bot.cli.command.impl;

import com.botwithus.bot.cli.command.CommandParser;
import com.botwithus.bot.core.report.ProblemKind;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ReportCommandTest {

    private static ReportCommand.Args args(String line) {
        return ReportCommand.Args.of(CommandParser.parse(line));
    }

    @Test
    void args_readBothSpellingsOfEachFlag() {
        ReportCommand.Args spaced = args("report Agility --problem stuck --note it froze at the bank");
        ReportCommand.Args equals = args("report Agility --problem=wrong_action --note=\"it froze\"");
        ReportCommand.Args mixed = args("report Agility --problem=crashed --note it froze at the bank");
        assertAll(
                () -> assertEquals(new ReportCommand.Args(Optional.of("Agility"), Optional.of(ProblemKind.STUCK),
                        "it froze at the bank"), spaced),
                () -> assertEquals(new ReportCommand.Args(Optional.of("Agility"),
                        Optional.of(ProblemKind.WRONG_ACTION), "it froze"), equals),
                () -> assertEquals(new ReportCommand.Args(Optional.of("Agility"), Optional.of(ProblemKind.CRASHED),
                        "it froze at the bank"), mixed));
    }

    @Test
    void args_missingOrUnknownParts_areEmpty() {
        assertAll(
                () -> assertEquals(Optional.empty(), args("report Agility --note it froze").problem()),
                () -> assertEquals(Optional.empty(), args("report Agility --problem frozen --note x").problem()),
                () -> assertEquals(Optional.empty(), args("report Agility --problem").problem()),
                () -> assertEquals("", args("report Agility --problem stuck").note()),
                () -> assertEquals("", args("report Agility --problem stuck --note").note()),
                () -> assertEquals(Optional.empty(), args("report").script()));
    }
}
