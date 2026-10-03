package com.botwithus.bot.core.launcher;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The two scope test vectors of launcher ADR 0007, section 2.1. */
class ServiceScopeTest {

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "S-1-5-21-1-2-3-1001;1;9fec1d0680d64900",
            "S-1-5-21-3623811015-3361044348-30300820-1013;2;c32d8261bd9a04f5",
            // The SID is upper-cased before hashing, so case does not change the scope.
            "s-1-5-21-1-2-3-1001;1;9fec1d0680d64900",
    })
    void compute_reproducesTheAdrVectors(String sid, long sessionId, String scope) {
        assertEquals(scope, ServiceScope.compute(sid, sessionId));
    }
}
