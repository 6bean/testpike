package com.sixbean.testpike.core.notification.domain.component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;


public final class NotificationEventDict {

    private static final Map<String, NotificationEventFlags> EVENTS = buildEvents();

    private NotificationEventDict() {
    }

    private static Map<String, NotificationEventFlags> buildEvents() {
        Map<String, NotificationEventFlags> m = new LinkedHashMap<>();
        m.put("AI_GEN_DONE", new NotificationEventFlags(false, false));
        m.put("AI_GEN_FAILED", new NotificationEventFlags(false, false));
        m.put("SCRIPT_DONE", new NotificationEventFlags(false, false));
        m.put("SCRIPT_FAILED", new NotificationEventFlags(false, false));
        m.put("REVIEW_CHANGED", new NotificationEventFlags(false, false));
        m.put("EXEC_FAILED", new NotificationEventFlags(false, false));
        m.put("BATCH_FINISHED", new NotificationEventFlags(false, false));
        m.put("JOURNEY_SEGMENT_FAILED", new NotificationEventFlags(false, false));
        m.put("JOURNEY_RUN_FINISHED", new NotificationEventFlags(false, false));
        m.put("AI_REPAIRED", new NotificationEventFlags(false, false));
        m.put("TESTRAIL_FAILED", new NotificationEventFlags(false, false));
        m.put("FEEDBACK_RESOLVED", new NotificationEventFlags(false, false));
        m.put("DEFECT_CLOSED", new NotificationEventFlags(false, false));
        m.put("BACKLOG_WARNING", new NotificationEventFlags(false, false));
        m.put("MAINTENANCE", new NotificationEventFlags(false, false));
        m.put("JOB_FAILED", new NotificationEventFlags(false, false));
        m.put("EXECUTOR_OFFLINE", new NotificationEventFlags(false, false));
        m.put("EXECUTOR_ONLINE", new NotificationEventFlags(false, true));
        m.put("EXPORT_DONE", new NotificationEventFlags(false, false));
        m.put("REVIEW_REMIND", new NotificationEventFlags(false, false));
        m.put("REVIEW_TIMEOUT", new NotificationEventFlags(true, false));
        // downstream-impact urge stages (escalation forced like REVIEW_TIMEOUT)
        m.put("LINKAGE_URGE", new NotificationEventFlags(false, false));
        m.put("LINKAGE_ESCALATE", new NotificationEventFlags(true, false));
        m.put("DAILY_DIGEST", new NotificationEventFlags(false, true));
        m.put("ENV_UNHEALTHY", new NotificationEventFlags(true, false));
        m.put("ENV_RECOVERED", new NotificationEventFlags(false, false));
        m.put("LOGIN_LOCKED", new NotificationEventFlags(true, false));
        m.put("LOGIN_SESSION_REVOKE", new NotificationEventFlags(true, false));
        m.put("SKILL_APPROVAL_REQUEST", new NotificationEventFlags(false, false));
        m.put("SKILL_APPROVED", new NotificationEventFlags(false, false));
        m.put("SKILL_APPROVAL_REJECTED", new NotificationEventFlags(false, false));
        m.put("DELETE_APPROVAL_REQUEST", new NotificationEventFlags(false, false));
        m.put("DELETE_APPROVED", new NotificationEventFlags(false, false));
        m.put("DELETE_APPROVAL_REJECTED", new NotificationEventFlags(false, false));
        m.put("TOOL_APPROVAL_REQUEST", new NotificationEventFlags(false, false));
        m.put("TOOL_APPROVED", new NotificationEventFlags(false, false));
        m.put("TOOL_APPROVAL_REJECTED", new NotificationEventFlags(false, false));
        m.put("BATCH_FAILED", new NotificationEventFlags(false, false));
        m.put("COST_BUDGET_ALERT", new NotificationEventFlags(false, false));
        m.put("REPORT_DIGEST", new NotificationEventFlags(false, true));
        m.put("MENTION", new NotificationEventFlags(false, false));
        m.put("DEFECT_UNASSIGNED", new NotificationEventFlags(false, false));
        m.put("DOC_CHANGE_ANALYZED", new NotificationEventFlags(false, false));
 // W1c contract change-analysis queue: a landed version diff creates one review
 // row and notifies project owners union creator,
 // dedup_key=SPEC_CHANGE_ANALYZED:{analysisId}:{userId}; low-frequency, unsubscribable,
 // flags(false,false) same family as DOC_CHANGE_ANALYZED
        m.put("SPEC_CHANGE_ANALYZED", new NotificationEventFlags(false, false));
        m.put("CERT_EXPIRING", new NotificationEventFlags(false, false));
        m.put("SPEC_CHANGE_DETECTED", new NotificationEventFlags(false, false));
        m.put("MONITOR_DOWN", new NotificationEventFlags(true, false));
        m.put("MONITOR_RECOVERED", new NotificationEventFlags(false, false));
        m.put("MONITOR_FLAPPING", new NotificationEventFlags(false, false));
        m.put("MONITOR_LOST", new NotificationEventFlags(false, false));
        m.put("VERSION_UNSUPPORTED", new NotificationEventFlags(true, false));
        m.put("UPGRADE_STARTED", new NotificationEventFlags(false, false));
        m.put("CANARY_FAILED", new NotificationEventFlags(true, false));
        m.put("BATCH_DONE", new NotificationEventFlags(false, false));
        m.put("ROLLBACK_DONE", new NotificationEventFlags(false, false));
        // WE-S7 8.3: "about to break" drift warning from the element health
        // account. Low frequency, unsubscribable — same family as
        // CERT_EXPIRING; dedup_key = WEB_ELEMENT_DRIFT:{elementId}:{userId}:
        // {day} so one element can never re-ring the bell within a day.
        m.put("WEB_ELEMENT_DRIFT", new NotificationEventFlags(false, false));
        m.put("PERF_REPORT_CARD", new NotificationEventFlags(false, false));
        // GB-3 automatic perf SLA breach alert (07 event table): pushed after a run
        // reaches its terminal state when the pinned-SLA verdict fails. Recipients =
        // run operator ∪ project test owner ∪ creator, deduped.
        // dedup_key=PERF_SLA_BREACH:{runId}:{userId} — a separate spectrum from the
        // manual card prefix (sharing it would let the alert swallow the user card).
        // Default-on, unsubscribable flags(false,false); green runs and gate-less
        // verdicts send nothing (honest silence, never a fabricated red light).
        m.put("PERF_SLA_BREACH", new NotificationEventFlags(false, false));
        m.put("NET_PROXY_RESIDUAL", new NotificationEventFlags(false, false));
        m.put("DEVICE_OVERHEAT", new NotificationEventFlags(false, false));
        m.put("TAKEOVER_REQUEST", new NotificationEventFlags(false, false));
        m.put("TAKEOVER_FORCED", new NotificationEventFlags(false, false));
        m.put("RESERVATION_APPROVED", new NotificationEventFlags(false, false));
        m.put("RESERVATION_REJECTED", new NotificationEventFlags(false, false));
        m.put("RESERVATION_EXPIRED", new NotificationEventFlags(false, false));
        m.put("RELEASE_CANDIDATE", new NotificationEventFlags(false, false));
        // Mock space expiry deferral : the scheduled sweep wants
        // to retire an expired throwaway space but blocking references (open
        // recording boxes today, case bindings later) hold it open — one
        // plain-word notice per project owner per day so nobody loses a running
        // recording silently. Low frequency, unsubscribable: flags(false,false);
        // dedup_key=MOCK_SPACE_EXPIRY_DEFERRED:{spaceId}:{userId}:{dayWindow}.
        m.put("MOCK_SPACE_EXPIRY_DEFERRED", new NotificationEventFlags(false, false));
        // AO-7 proactive insights : after a batch finalizes, the
        // clustering result lands in tp_batch_insight and one reminder per
        // recipient points at the insight card. AI-driven reminder, on by
        // default (factory-default-on ruling), unsubscribable: flags(false,false);
        // dedup_key=AI_INSIGHT_CLUSTER:{batchId}:{userId}=one card per batch per
        // person, a replayed landing never stacks letters.
        m.put("AI_INSIGHT_CLUSTER", new NotificationEventFlags(false, false));
        // Shared asset pool change notices: a re-publish informs every consumer project
        // that pinned this chain, and a forced withdrawal warns the same audience while
        // the pinned scripts go pending. Recipients = consumer project owners union
        // creator; dedup_key=ASSET_UPDATED:{assetRowId}:{userId} /
        // ASSET_WITHDRAWN:{assetRowId}:{userId}; low-frequency, unsubscribable:
        // flags(false,false) same family as SPEC_CHANGE_DETECTED.
        m.put("ASSET_UPDATED", new NotificationEventFlags(false, false));
        m.put("ASSET_WITHDRAWN", new NotificationEventFlags(false, false));
        // Dead-letter ops backlog alert: pending rows over the hot-update threshold
        // (mq.dlq.backlog-alert-threshold), one notice per admin per hour window
        // (dedup=MQ_DLQ_BACKLOG:{hour-window}:{adminId}); the per-row escalation for
        // exhausted auto-replay rounds shares this event
        // (dedup=MQ_DLQ_AUTO_VOID:{ledger-id}:{hour-window}:{adminId}).
        // Alert-subscription event, fully implemented; unsubscribable (flags(false,false)).
        m.put("MQ_DLQ_BACKLOG", new NotificationEventFlags(false, false));
        return Collections.unmodifiableMap(m);
    }

    
    public static Map<String, NotificationEventFlags> all() {
        return EVENTS;
    }

    
    public static boolean isKnown(String eventType) {
        return eventType != null && EVENTS.containsKey(eventType);
    }

    
    public static boolean isForced(String eventType) {
        NotificationEventFlags flags = eventType == null ? null : EVENTS.get(eventType);
        return flags != null && flags.forced();
    }

    
    public static boolean isDefaultEnabled(String eventType) {
        NotificationEventFlags flags = eventType == null ? null : EVENTS.get(eventType);
        return flags == null || flags.defaultEnabled();
    }
}
