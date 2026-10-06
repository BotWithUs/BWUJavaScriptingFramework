package com.botwithus.bot.cli.command.impl;

import com.botwithus.bot.cli.command.CommandParser;
import com.botwithus.bot.cli.command.ParsedCommand;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ReportCommandTest {

    private static String note(String line) {
        ParsedCommand parsed = CommandParser.parse(line);
        return ReportCommand.note(parsed);
    }

    @Test
    void note_acceptsBothSpellings_andNone() {
        assertAll(
                () -> assertEquals("it froze at the bank", note("report Agility --note it froze at the bank")),
                () -> assertEquals("it froze", note("report Agility --note \"it froze\"")),
                () -> assertEquals("it froze", note("report Agility --note=\"it froze\"")),
                () -> assertEquals("", note("report Agility --note")),
                () -> assertEquals("", note("report Agility")));
    }
}
