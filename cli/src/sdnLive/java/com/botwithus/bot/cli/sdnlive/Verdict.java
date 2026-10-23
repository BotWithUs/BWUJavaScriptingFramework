package com.botwithus.bot.cli.sdnlive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The checks one run made and which of them failed. Every line it logs starts
 * {@code SDN-LIVE}, so a run's evidence can be pulled out of a mixed log.
 */
final class Verdict {

    private static final Logger log = LoggerFactory.getLogger(Verdict.class);

    private final List<String> failures = new ArrayList<>();
    private int checks;

    /** Records one check: {@code what} held, or did not, with {@code detail} saying what was seen. */
    void expect(String what, boolean holds, Supplier<String> detail) {
        checks++;
        if (holds) {
            log.info("SDN-LIVE PASS {}", what);
            return;
        }
        String seen = detail.get();
        failures.add(what + " -- " + seen);
        log.error("SDN-LIVE FAIL {} -- {}", what, seen);
    }

    /** A run that checked nothing has not passed: a check that cannot fail is not evidence. */
    boolean passed() {
        return checks > 0 && failures.isEmpty();
    }

    void summarise() {
        log.info("SDN-LIVE RESULT {}: {} check(s), {} failed", passed() ? "PASS" : "FAIL", checks, failures.size());
        failures.forEach(f -> log.info("SDN-LIVE   failed: {}", f));
    }
}
