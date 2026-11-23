package com.sixbean.testpike.core.plans.api.dto;

import java.util.List;
import java.util.Map;

/**
 * One assembled regression-rehearsal draft .
 * <p>The dialog analysis door produces this view when the operator asks for a
 * rehearsal: it names the source the draft was built from (a regression suite
 * or an existing plan template of the current project segment), and carries a
 * ready-to-use {@code planCreatePayload} in exactly the shape the PLAN_WRITE
 * draft card and the plan-create chain accept — the row is only written after
 * a human confirms the card, never by this read.
 *
 * @param sourceKind SUITE = built from a regression suite, PLAN = copied
 * from an existing plan template
 * @param sourceId id of that source row inside the segment
 * @param sourceName source name shown so the answer names its origin
 * @param caseCount cases the draft pins (null for a rule-based plan
 * template — the plan chain pulls the real set)
 * @param planName proposed plan name (rehearsal prefix + source + date)
 * @param envIds execution environments copied from the source
 * @param envIdsNote honest note when the source saved no environments
 * (the human fills them before confirming)
 * @param planCreatePayload payload in the plan-create shape, projectId never
 * included (the landing chain carries the segment)
 * @param guidance what happens after the card is confirmed, in plain
 * words for the conversation
 */
public record RehearsalDraftView(String sourceKind, long sourceId, String sourceName,
                                 Integer caseCount, String planName, List<Long> envIds,
                                 String envIdsNote, Map<String, Object> planCreatePayload,
                                 String guidance) {
}
