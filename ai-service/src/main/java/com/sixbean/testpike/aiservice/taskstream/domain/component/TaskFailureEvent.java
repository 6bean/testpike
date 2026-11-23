package com.sixbean.testpike.aiservice.taskstream.domain.component;

/**
 * One formatted terminal-failure process event: the closed
 * reason category (model side / validation side / environment side), the
 * actionable suggestion, the retry entry text, the human summary line the
 * drawer shows last, and the machine-readable detail face carrying the same
 * fields as JSON. Produced only by {@link TaskFailureEventFormatter} — the
 * single source every failure branch of every job family consumes, so the
 * drawer never ends with a bare "failed" or a blank tail.
 */
public record TaskFailureEvent(String category, String suggestion, String retryEntry,
                               String summary, String detailJson) {
}
