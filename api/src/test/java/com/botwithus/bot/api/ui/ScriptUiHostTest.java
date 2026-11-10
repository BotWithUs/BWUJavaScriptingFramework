package com.botwithus.bot.api.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * What a host that says nothing about reporting is taken to mean: it cannot
 * open its report dialog for a script, so the script offers its own help.
 */
class ScriptUiHostTest {

    @Test
    void aHostWithoutReporting_saysItCannotReport() {
        ScriptUiHost host = new ScriptUiHost() {
            @Override
            public void close() {
            }

            @Override
            public boolean poppedOut() {
                return false;
            }
        };

        assertFalse(host.reportProblem());
    }
}
