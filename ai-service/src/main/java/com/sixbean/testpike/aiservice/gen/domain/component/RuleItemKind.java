package com.sixbean.testpike.aiservice.gen.domain.component;

/**
 * Shape of a mechanically extracted rule item .
 *
 * <p>{@link #TABLE_ROW} items are column-structured and eligible for
 * per-item semantic reconciliation; {@link #PROSE_CANDIDATE} items come from
 * free-text sentences and are booked for human adjudication only — the
 */
public enum RuleItemKind {
    TABLE_ROW,
    PROSE_CANDIDATE
}
