package com.sixbean.testpike.aiservice.knowledge.domain.component;

import com.sixbean.testpike.aiservice.doc.domain.component.ContextBudgetPlanner;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.knowledge.api.dto.KnowledgeSearchItem;
import com.sixbean.testpike.aiservice.knowledge.api.dto.KnowledgeSearchRequest;
import com.sixbean.testpike.aiservice.knowledge.domain.service.KnowledgeSearchService;
import com.sixbean.testpike.aiservice.llm.domain.component.BudgetedPromptSegment;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.SysConfigBooleans;
import com.sixbean.testpike.aiservice.llm.domain.component.TokenEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>Injection gate = {@link #injectionEnabled} : the truth
 * row {@value #KEY_TRUTH_SOURCE_ENABLED} ships factory ON (an absent/dirty row
 * keeps it on) OR the legacy int row {@value #KEY_SQL_GEN_ENABLED} is non-zero;
 * turning the truth row off restores the exact legacy piggyback. Action
 * availability stays under the legacy int gate alone — the
 * sql.assert/sql.query whitelist never drifts. Gate closed = empty segment,
 * zero retrieval (the AI keeps the manual-SQL-template instruction, the
 * injection surface is never broken). Every gate read is single-sourced in
 * this class — AiGenServiceImpl's whitelist path consumes the same method
 * (red line 6: no duplicated logic).
 * <p>Budget single rule (same one-deduction discipline as the A4 few-shot
 * path): min(ai.doc.token-budget, endpoint prompt_limit_tokens), applied
 * through the shared {@link BudgetedPromptSegment} with whole-block tail
 * dropping (R40 single source — this bridge's former copy of the drop loop
 * was retired into it by .
 *
 */
@Component
public class DdlDictBridge {

    private static final Logger log = LoggerFactory.getLogger(DdlDictBridge.class);

    
    public static final String KEY_SQL_GEN_ENABLED = "ai.sql-gen.enabled";

    /**
 * truth-source gate K8 key (V429 seed 'true', bool face —
 * NOT the int face of the legacy gate above). The table-structure segment
 * is assertion grounding, not SQL-action availability: with this row on
 * (an absent/dirty row keeps it on) the DDL dictionary reference is
 * retrieved and injected regardless of {@value #KEY_SQL_GEN_ENABLED}; the
 * legacy gate alone still decides whether sql.assert/sql.query enter the
 * stage2 whitelist. Turning this row off restores the exact legacy
 * piggyback (segment rides the sql-gen gate only).
 */
    public static final String KEY_TRUTH_SOURCE_ENABLED = "ai.gen.truth-source.ddl-dict";

    
    static final int MAX_ITEMS = 3;

    
    static final int CANDIDATE_TOP_K = 8;

    
    static final String SECTION_HEADER = "表结构参考（检索自项目 DDL 字典，写 SQL 以此为据、"
            + "禁凭记忆猜表名列名）：\n";

    private final KnowledgeSearchService searchService;
    private final CoreGateway coreGateway;
    private final TokenEstimator estimator;

    public DdlDictBridge(KnowledgeSearchService searchService, CoreGateway coreGateway,
                         TokenEstimator estimator) {
        this.searchService = searchService;
        this.coreGateway = coreGateway;
        this.estimator = estimator;
    }

    
    public String ddlSegment(Long taskId, long projectId, String caseText,
                             LlmEndpointConfig endpoint) {
        if (!injectionEnabled(coreGateway)) {
            return "";
        }
        if (caseText == null || caseText.isBlank()) {
            return "";
        }
        List<KnowledgeSearchItem> hits;
        try {
            hits = searchService.search(new KnowledgeSearchRequest(
                    projectId, caseText, KnowledgeSearchScene.BUSINESS.name(),
                    CANDIDATE_TOP_K, taskId));
        } catch (Exception e) {
            log.warn("B11 DDL 字典检索失败（本次零注入，不阻断生成）projectId={}: {}",
                    projectId, e.getMessage());
            return "";
        }
        List<KnowledgeSearchItem> ddls = new ArrayList<>();
        for (KnowledgeSearchItem hit : hits == null ? List.<KnowledgeSearchItem>of() : hits) {
            if (KnowledgeMetadataFields.SOURCE_TYPE_DDL_DICT.equals(hit.sourceType())) {
                ddls.add(hit);
                if (ddls.size() == MAX_ITEMS) {
                    break;
                }
            }
        }
        if (ddls.isEmpty()) {
            return "";
        }
        List<String> blocks = new ArrayList<>();
        for (KnowledgeSearchItem hit : ddls) {
            blocks.add("【" + (hit.title() == null ? "表" : hit.title()) + "】\n"
                    + (hit.content() == null ? "" : hit.content().strip()) + "\n");
        }
        int docBudget = ContextBudgetPlanner.tokenBudgetOf(
                coreGateway.getSysConfig(ContextBudgetPlanner.BUDGET_CONFIG_KEY));
        // (R40): the budgeted splice is the shared single source
        // (few-shot/contract bridges same component) — the former in-bridge copy
        // of the drop loop retired here; same rule, same honest empty-on-total-
        // overflow contract, warn tags carry the project handle.
        return BudgetedPromptSegment.compose(
                "DDL dictionary projectId=" + projectId, estimator, docBudget,
                endpoint == null ? null : endpoint.promptLimitTokens(),
                SECTION_HEADER, blocks);
    }

    
    public static boolean sqlGenEnabled(CoreGateway coreGateway) {
        String raw = coreGateway.getSysConfig(KEY_SQL_GEN_ENABLED);
        if (raw == null) {
            return false;
        }
        try {
            return Integer.parseInt(raw.trim()) != 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
 * Injection gate of the table-structure segment , single
 * source for bridge and service call sites — red line 6, no second rule):
 * truth-source row on (absent/dirty = factory ON) OR the legacy sql-gen
 * gate on (superset, the old behaviour never shrinks). The truth row does
 * NOT touch SQL-action availability — that whitelist stays under
 * {@link #sqlGenEnabled} alone.
 */
    public static boolean injectionEnabled(CoreGateway coreGateway) {
        return sqlGenEnabled(coreGateway)
                || SysConfigBooleans.parse(
                        coreGateway.getSysConfig(KEY_TRUTH_SOURCE_ENABLED), true);
    }
}
