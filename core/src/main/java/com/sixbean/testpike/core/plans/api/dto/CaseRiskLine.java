package com.sixbean.testpike.core.plans.api.dto;

/**
 * One failed-case line of the rehearsal risk report card.
 * <p>The row is read straight from the batch result ledger (tp_batch_case)
 * joined to the case title; a removed case keeps its id with a null title so
 * the ledger never silently drops a risk line. Each raw ledger value rides
 * next to its plain-word label: numeric failure categories map through the
 * defects-domain single source, free-text categories keep their recorded
 * wording, and the flaky flag reads a two-word stability label — the
 * conversation answer quotes the labels, never the wire keys.
 *
 * @param caseId         case id from the result ledger
 * @param title          case title at read time (null when the case was removed)
 * @param errorType      failure category written by the executor (null when absent)
 * @param errorTypeLabel failure category in plain words (the unclassified wording when absent)
 * @param flaky          J1 flaky tag on the ledger row
 * @param flakyLabel     the flaky tag as an on-screen word (unstable/stable in Chinese)
 * @param durationMs     execution duration recorded for this attempt (null when absent)
 */
public record CaseRiskLine(long caseId, String title, String errorType, String errorTypeLabel,
                           boolean flaky, String flakyLabel, Long durationMs) {
}
