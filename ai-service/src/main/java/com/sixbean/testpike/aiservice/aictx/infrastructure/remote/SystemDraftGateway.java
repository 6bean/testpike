package com.sixbean.testpike.aiservice.aictx.infrastructure.remote;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ai-service → core system-draft posting seam (AP batch ,
 * POST /internal/ai-chat/drafts/system).
 * <p>Distilled project-memory entries are proposals, not writes: they are
 * filed as pending ledger drafts and only become assembly-injectable after
 * a human walks them through the shared confirmation door. The HTTP transport
 * and the operator-header posture are reused from {@link CoreGateway} — no
 * second Rest client, no wire-format copy (R40 single source).
 */
@Component
public class SystemDraftGateway {

    private final CoreGateway coreGateway;

    public SystemDraftGateway(CoreGateway coreGateway) {
        this.coreGateway = coreGateway;
    }

    /**
     * File one distilled entry awaiting confirmation.
     *
     * @param projectId owning project (the ledger anchor the door guards)
     * @param content   distilled convention text
     * @param sourceRef traceability anchors JSON ([{type,id}]) for the card
     * @param targetId  the awaiting memory row just created — the anchor the
     *                  ledger panel uses to route back to this open card
     * @return the draft ledger row id
     */
    public Long postSystemDraft(long projectId, String content, String sourceRef, Long targetId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("objectType", "ctx_memory");
        body.put("content", content);
        body.put("sourceRef", sourceRef);
        body.put("targetId", targetId);
        return coreGateway.call(HttpMethod.POST, "/internal/ai-chat/drafts/system", body,
                new ParameterizedTypeReference<
                        com.sixbean.testpike.common.clientobject.Response<Long>>() {
                }, 0L);
    }
}
