package com.sixbean.testpike.common.webelement;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One row of the whole-site survey run ledger, shared by the core service
 * that reads it and the admin bridge that mirrors it to the browser.
 *
 * <p>The profile name and start URL are joined server-side so a list row
 * never needs a second lookup; the counters are the values the ledger keeps
 * while the crawl runs and {@code failureNotes} is the decoded list of
 * refusal/timeout notes collected during the run. Both modules speak the
 * same wire shape, so the record lives once here instead of being written
 * out a second time per module.
 */
public record SiteSurveyRunView(
        Long runId,
        Long profileId,
        String profileName,
        String startUrl,
        String status,
        int pagesTotal,
        int pagesOk,
        int pagesFailed,
        int ledgerUpserted,
        int namingDegraded,
        Long exploreSessionId,
        List<String> failureNotes,
        LocalDateTime startedAt,
        LocalDateTime finishedAt) {

    /** Bean-style alias of the record component, for getter-shaped callers. */
    public Long getRunId() {
        return runId;
    }

    /** True when the crawl reached any terminal state (tail is static then). */
    public boolean terminal() {
        return !"running".equals(status);
    }
}
