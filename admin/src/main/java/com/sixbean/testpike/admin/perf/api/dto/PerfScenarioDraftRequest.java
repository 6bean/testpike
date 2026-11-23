package com.sixbean.testpike.admin.perf.api.dto;

/**
 * AI scenario-draft request on the admin door (B-11 : the wizard's
 * one-sentence load-test intent plus the project anchor that pays for it
 * (routing + cost attribution + access guard all read projectId).
 */
public record PerfScenarioDraftRequest(
        Long projectId,
        String description) {
}
