package com.sixbean.testpike.aiservice.knowledge.domain.component;

import com.sixbean.testpike.aiservice.doc.domain.component.ContextBudgetPlanner;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.knowledge.api.dto.KnowledgeSearchItem;
import com.sixbean.testpike.aiservice.knowledge.api.dto.KnowledgeSearchRequest;
import com.sixbean.testpike.aiservice.knowledge.domain.service.KnowledgeSearchService;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.PromptSizeGuard;
import com.sixbean.testpike.aiservice.llm.domain.component.TokenEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import com.sixbean.testpike.aiservice.llm.domain.component.SysConfigBooleans;


@Component
public class StyleFewshotBridge {

    private static final Logger log = LoggerFactory.getLogger(StyleFewshotBridge.class);

    
    public static final String KEY_ENABLED = "ai.kb.style-fewshot.enabled";

    
    static final int MAX_ITEMS = 5;

    
    static final int CANDIDATE_TOP_K = 10;

    
    static final String SECTION_HEADER = "优秀用例风格参考（按需参考，非强制遵循）：\n";

    /**
 * Revision-pool sub-header : negative samples
 * land under their own titled block so the model can tell "what humans
 * fixed" apart from the positive style references.
 */
    static final String REVISION_HEADER = "被修正样本（人工纠偏对照，按需参考，非强制遵循）：\n";

    private final KnowledgeSearchService searchService;
    private final CoreGateway coreGateway;
    private final TokenEstimator estimator;
    /** Nullable per the 3a trailing-arg convention: null = legacy, no pool. */
    private final RevisionSampleReader revisionReader;

    public StyleFewshotBridge(KnowledgeSearchService searchService, CoreGateway coreGateway,
                              TokenEstimator estimator, RevisionSampleReader revisionReader) {
        this.searchService = searchService;
        this.coreGateway = coreGateway;
        this.estimator = estimator;
        this.revisionReader = revisionReader;
    }

    
    public String fewshotSegment(Long taskId, long projectId, String requirementText,
                                 LlmEndpointConfig endpoint) {
        if (!enabled()) {
            return "";
        }
        if (requirementText == null || requirementText.isBlank()) {
            return "";
        }
        List<KnowledgeSearchItem> hits;
        try {
            hits = searchService.search(new KnowledgeSearchRequest(
                    projectId, requirementText, KnowledgeSearchScene.CASE_GEN.name(),
                    CANDIDATE_TOP_K, taskId));
        } catch (Exception e) {
            log.warn("A4 few-shot 检索失败（本次零注入，不阻断生成）projectId={}: {}",
                    projectId, e.getMessage());
            return "";
        }
        List<KnowledgeSearchItem> cases = new ArrayList<>();
        for (KnowledgeSearchItem hit : hits == null ? List.<KnowledgeSearchItem>of() : hits) {
            if (KnowledgeMetadataFields.SOURCE_TYPE_TEST_CASE.equals(hit.sourceType())) {
                cases.add(hit);
                if (cases.size() == MAX_ITEMS) {
                    break;
                }
            }
        }
        List<RevisionSample> pool;
        try {
            pool = revisionReader == null
                    ? List.of() : revisionReader.recent(projectId, MAX_ITEMS);
        } catch (RuntimeException e) {
            // fail-soft twin of the search path: pool trouble never eats the style part
            log.warn("A4 few-shot 修订池读取失败（本次零池样本，不阻断生成）projectId={}: {}",
                    projectId, e.getMessage());
            pool = List.of();
        }
        if (cases.isEmpty() && pool.isEmpty()) {
            return "";
        }
        int docBudget = ContextBudgetPlanner.tokenBudgetOf(
                coreGateway.getSysConfig(ContextBudgetPlanner.BUDGET_CONFIG_KEY));
        int budget = PromptSizeGuard.effectiveLimit(docBudget,
                endpoint == null ? null : endpoint.promptLimitTokens());
        StringBuilder segment = new StringBuilder();
        int injected = 0;
        int dropped = 0;
        boolean styleHeaderWritten = false;
        boolean revisionHeaderWritten = false;
        for (KnowledgeSearchItem hit : cases) {
            String block = "【" + (hit.title() == null ? "用例" : hit.title()) + "】\n"
                    + (hit.content() == null ? "" : hit.content().strip()) + "\n";
            String pending = (styleHeaderWritten ? "" : SECTION_HEADER) + block;
            int estIfAdded = estimator.countTokens(segment + pending);
            if (estIfAdded <= budget) {
                segment.append(pending);
                styleHeaderWritten = true;
                injected++;
            } else {
                dropped++;
            }
        }
        for (RevisionSample sample : pool) {
            String pending = (revisionHeaderWritten ? "" : revisionHeader(segment))
                    + renderRevision(sample);
            int estIfAdded = estimator.countTokens(segment + pending);
            if (estIfAdded <= budget) {
                segment.append(pending);
                revisionHeaderWritten = true;
                injected++;
            } else {
                dropped++;
            }
        }
        if (injected == 0) {
            log.warn("A4 few-shot 超预算全部省略 budget={}", budget);
            return "";
        }
        if (dropped > 0) {
            log.warn("A4 few-shot 超预算已省略 {} 项（保留高分 {} 项）projectId={}",
                    dropped, injected, projectId);
        }
        return segment.toString();
    }

    /** Revision sub-header, newline-separated from the style part when present. */
    private static String revisionHeader(StringBuilder segment) {
        return segment.length() == 0 ? REVISION_HEADER : "\n" + REVISION_HEADER;
    }

    /**
     * Renders one pool sample (BA ruling 7): the corrected family shows the
     * before/after pair; the delete family shows only the AI side plus a
     * deprecation note (its human side does not exist).
     */
    private static String renderRevision(RevisionSample sample) {
        String ai = sample.aiText() == null ? "" : sample.aiText().strip();
        if (sample.deleted()) {
            return "【被修正-弃用】AI 原产出：\n" + ai + "\n（人工已弃用该产出，避免同类写法）\n";
        }
        return "【被修正】AI 原产出：\n" + ai + "\n人工修改后：\n" + sample.humanText().strip() + "\n";
    }

    
    private boolean enabled() {
        return SysConfigBooleans.on(coreGateway.getSysConfig(KEY_ENABLED));
    }
}
