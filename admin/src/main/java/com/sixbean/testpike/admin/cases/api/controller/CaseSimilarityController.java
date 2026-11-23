package com.sixbean.testpike.admin.cases.api.controller;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.cases.api.dto.SimilarityCheckRequest;
import com.sixbean.testpike.admin.cases.api.dto.SimilarityHintLevel;
import com.sixbean.testpike.admin.cases.api.dto.SimilarityHitView;
import com.sixbean.testpike.admin.cases.app.component.SimilarityHintGrader;
import com.sixbean.testpike.admin.knowledge.api.dto.KnowledgeSearchHit;
import com.sixbean.testpike.admin.knowledge.infrastructure.remote.client.KnowledgeAiClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/api/v1/cases")
public class CaseSimilarityController {

    private static final Logger log = LoggerFactory.getLogger(CaseSimilarityController.class);

    
    static final String SCENE_SIMILARITY = "SIMILARITY";

    private final KnowledgeAiClient aiClient;
    private final ProjectAccessGuard guard;
    private final SimilarityHintGrader grader;

    public CaseSimilarityController(KnowledgeAiClient aiClient, ProjectAccessGuard guard,
                                    SimilarityHintGrader grader) {
        this.aiClient = aiClient;
        this.guard = guard;
        this.grader = grader;
    }

    
    @RequirePermission("perm:case:similarity-hint")
    @PostMapping("/similarity-check")
    public Response<List<SimilarityHitView>> check(@RequestBody SimilarityCheckRequest request) {
        if (request == null || request.projectId() == null) {
            throw new ServiceException("缺少项目 ID（projectId）", BizErrorCode.VALIDATION_ERROR);
        }
        if (request.queryText() == null || request.queryText().isBlank()) {
            throw new ServiceException("缺少待检测文本（queryText）", BizErrorCode.VALIDATION_ERROR);
        }
        guard.assertCanView(UserContext.current(), request.projectId());

        List<KnowledgeSearchHit> hits;
        try {
            hits = aiClient.search(request.projectId(), request.queryText(), SCENE_SIMILARITY, null);
        } catch (RuntimeException e) {
            log.warn("A9 相似检测暂不可用（project={}）：{}", request.projectId(), e.getMessage());
            return Response.builderSuccess(List.of());
        }

        List<SimilarityHitView> views = hits.stream()
                .filter(h -> request.excludeCaseId() == null
                        || !request.excludeCaseId().equals(h.caseId()))
                .map(this::toView)
                .filter(v -> v.level() != SimilarityHintLevel.NONE)
                .toList();
        return Response.builderSuccess(views);
    }

    private SimilarityHitView toView(KnowledgeSearchHit hit) {
        return new SimilarityHitView(hit.caseId(), hit.title(), hit.similarity(),
                grader.grade(hit.similarity()), hit.content());
    }
}
