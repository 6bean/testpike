package com.sixbean.testpike.aiservice.mock.infrastructure.remote;

/**
 * Read model of one AI drafting job pulled over the core internal door
 * (consumer-side mirror of core {@code MockAiJobInfo}; unknown wire fields —
 * the timestamps — are ignored by Jackson, ProjectBrief-vs-ProjectInfo
 * precedent: the consumer never needs them and a lean record keeps the
 * dependency direction ai-service → wire contract, not core classes).
 *
 * @param id                 tp_mock_ai_job row id
 * @param spaceId            owning mock space
 * @param jobType            application point (nl-stub | edge-scenarios | spec-sync
 *                           | llm-fallback | record-summarize — the M10 pair rides
 *                           the queue but is triaged by the consumer, never drafted)
 * @param status             queued | running | preview | consumed | failed | cancelled
 * @param inputJson          drafting input as stored (prompt + references, JSON text)
 * @param budgetSnapshotJson creation-time cost account pin (JSON text, may be null)
 * @param resultPreviewRef   artifact reference once a preview bundle exists
 * @param errorMessage       plain-word failure reason when status=failed
 */
public record MockAiJobInfoRemote(Long id, Long spaceId, String jobType, String status,
                                  String inputJson, String budgetSnapshotJson,
                                  String resultPreviewRef, String errorMessage) {
}
