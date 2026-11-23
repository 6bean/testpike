package com.sixbean.testpike.core.behavior.domain.component;

/**
 * Human-verdict channels that feed the behavior outcome ledger ( item 7
 * wiring). One enum entry per real decision door; each entry carries what the
 * ledger needs to classify and dedup a verdict: the source tag, the scope of
 * the judged material, the event-key namespace that keeps this table's row ids
 * unique inside the ledger (several channels share the source value, so bare
 * numeric ids from different tables could silently swallow each other without
 * the prefix), the fallback prompt-template domain when no producing AI task
 * can be pinned, and the tp_ai_task column used for the trace (NULL = no task
 * back-reference exists for this material family).
 */
public enum OutcomeChannel {

    /** Case review (single or batch) verdicts an AI-generated test case. */
    CASE_REVIEW("review", "task", "case-review", "用例生成", "case_id"),

    /** Requirement review verdicts a requirement row, typically produced by document extraction. */
    REQUIREMENT_REVIEW("review", "document", "requirement-review", "doc-extract", "requirement_id"),

    /** Judge override: a human re-verdicts the machine score of one judged record. */
    JUDGE_REVIEW("review", "task", "judge-review", "judge", null),

    /**
     * Document extraction confirm: the reviewer checked an extracted
     * requirement point and it landed — a one-click adoption verdict on the
     * AI extraction output (KEEP_AS_IS / FORCE_NEW are dedup-veto annotations
     * on the same door, not separate votes, so only created rows count;
     * unchecked items are non-actions and stay uncounted).
     */
    CHIP("chip", "document", "chip-confirm", "doc-extract", "requirement_id"),

    /**
     * Draft confirmation door: a human held a ticket and applied an AI write
     * proposal. tp_ai_chat_draft carries no task back-reference (live scan of
     * V302), so the producing template version stays NULL and the channel
     * default domain applies — same honest posture as JUDGE_REVIEW. Voided
     * drafts are lifecycle states, not human verdicts, and never record.
     */
    DRAFT("draft", "task", "draft-apply", "aichat-draft", null),

    /**
 * Derived adoption observation on an edit of an AI-generated case
 * : not a click but the before/after text pair of
 * the producing AI snapshot versus the human-touched content. Pure
 * statistics, zero interception — the edit itself is never gated by it.
 * The event key is "{caseId}:v{version}", so each edit event lands once
 * and a replayed observation of the same version dedups.
 */
    CASE_ADOPTION_EDIT("adoption", "task", "case-edit", "用例生成", "case_id"),

    /**
     * Deletion of an AI-generated case is the strongest rejection signal
     * (deprecated by definition, no threshold read): key "{caseId}" keeps it
     * distinct from the edit family while a re-delete of the same row stays
     * idempotent.
     */
    CASE_ADOPTION_DELETE("adoption", "task", "case-delete", "用例生成", "case_id");

    private final String source;
    private final String scope;
    private final String eventKeyPrefix;
    private final String defaultTemplateDomain;
    private final String traceColumn;

    OutcomeChannel(String source, String scope, String eventKeyPrefix,
                   String defaultTemplateDomain, String traceColumn) {
        this.source = source;
        this.scope = scope;
        this.eventKeyPrefix = eventKeyPrefix;
        this.defaultTemplateDomain = defaultTemplateDomain;
        this.traceColumn = traceColumn;
    }

    public String source() {
        return source;
    }

    public String scope() {
        return scope;
    }

    public String eventKeyPrefix() {
        return eventKeyPrefix;
    }

    public String defaultTemplateDomain() {
        return defaultTemplateDomain;
    }

    /** tp_ai_task column carrying the back-reference, or NULL when this family has none. */
    public String traceColumn() {
        return traceColumn;
    }
}
