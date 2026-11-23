package com.sixbean.testpike.core.deadletter.domain.component;

/**
 * <p>Single source for the tp_mq_dead_letter.status vocabulary: the ops page filters
 * on it now, the replay/void actions and the auto-replay loop write it
 * later — one enum, no loose strings in any consumer (same discipline as
 * {@link DeadLetterFailureClass}).
 */
public enum DeadLetterLedgerStatus {

    /** Booked from a DLQ, awaiting an operator or the auto loop. Counts as backlog. */
    PENDING("pending"),

    /** Replayed back to its original exchange . */
    REPLAYED("replayed"),

    /** Voided by an operator with a recorded reason (never physically deleted). */
    VOIDED("voided");

    private final String dbValue;

    DeadLetterLedgerStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    /** The literal stored in tp_mq_dead_letter.status. */
    public String dbValue() {
        return dbValue;
    }

    /**
     * Resolve a filter/parameter to the enum, failing closed with a human-readable
     * message: a typo'd filter must not silently return "no rows" (an operator would
     * read that as an empty DLQ).
     */
    public static DeadLetterLedgerStatus fromFilter(String value) {
        for (DeadLetterLedgerStatus status : values()) {
            if (status.dbValue.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new com.sixbean.testpike.common.exception.ValidationException(
                "死信处理状态仅支持 pending/replayed/voided，当前请求：" + value);
    }
}
