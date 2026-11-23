package com.sixbean.testpike.core.linkage.api.dto;

/**
 * One urge-scan round over the downstream-impact ledger (AN batch ,
 * internal scheduler result — no new external endpoint, same posture as the
 * Q4 ReviewRemindResult).
 *
 * @param scanned pending ledger rows this round read (older than the earliest enabled stage)
 * @param urged urge notifications actually delivered (LINKAGE_URGE, executor level)
 * @param escalated escalation notifications actually delivered (LINKAGE_ESCALATE, project owners)
 * @param deduped notifications skipped because the calendar-day dedup key already existed
 */
public record LinkageUrgeResult(int scanned, int urged, int escalated, int deduped) {
}
