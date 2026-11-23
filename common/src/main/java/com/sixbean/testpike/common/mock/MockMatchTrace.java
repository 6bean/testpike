package com.sixbean.testpike.common.mock;

/**
 * One line of the adjudication trail : why a stub did or did not
 * answer. The inspector renders these rows as the traffic-light list that
 * turns "why is my mock not firing" from an hour of guessing into one glance.
 *
 * @param stubName rule the line belongs to
 * @param step compared facet (method / path / query:key / header:... / body:...)
 * @param outcome PASS or FAIL
 * @param detail human-word expectation vs actual (null for PASS rows)
 */
public record MockMatchTrace(String stubName, String step, String outcome, String detail) {

    static final String PASS = "PASS";
    static final String FAIL = "FAIL";
}
