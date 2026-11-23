package com.sixbean.testpike.aiservice.webelement.api.dto;

import java.util.List;

/**
 * Naming request for one surveyed page (WE batch segment P3, ai-service face).
 * <p>{@code projectId} owns the cost bucket and the call-ledger row; the
 * candidates carry the deduped anchor identities the core relay collected.
 * <p>{@code sessionId} (R62 segment b2) anchors one naming-batch frame onto the
 * survey process stream so the tail drawer can show the naming outcome per
 * batch; a null session means no survey stream to report onto (the naming
 * still runs, only the frame is skipped).
 */
public record SurveyNamingRequest(long projectId, String pageUrl,
                                  List<SurveyCandidateDescriptor> candidates,
                                  Long sessionId) {
}
