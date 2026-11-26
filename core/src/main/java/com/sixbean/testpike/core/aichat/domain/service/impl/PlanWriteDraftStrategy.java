package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.domain.component.DraftObjectType;
import com.sixbean.testpike.core.aichat.domain.component.DraftPayloadReads;
import com.sixbean.testpike.core.aichat.domain.component.DraftLandContext;
import com.sixbean.testpike.core.aichat.domain.service.ChatSessionService;
import com.sixbean.testpike.core.aichat.domain.service.DraftWriteStrategy;
import com.sixbean.testpike.core.plans.api.dto.CaseScopeRule;
import com.sixbean.testpike.core.plans.api.dto.EntryCriteria;
import com.sixbean.testpike.core.plans.api.dto.ExitCriteria;
import com.sixbean.testpike.core.plans.api.dto.PlanCreateInput;
import com.sixbean.testpike.core.plans.api.dto.PlanInfo;
import com.sixbean.testpike.core.plans.domain.service.PlanService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Plan-landing strategy behind the generic draft door (tool-surface clearing
 * batch stage 4a, fifth cleared domain): one confirmed dialog proposal
 * creates an execution plan through the SAME PlanService.create chain the
 * plan wizard uses — name/env validation, the environment-ownership check,
 * the at-least-one-scope-dimension refusal (an unbounded scope would pull
 * every case in the project), the automatic scope-rule case pull and audit
 * all come free, never a parallel write path (red line: no second plan save).
 * <p>The plan keeps its human lifecycle after landing: the row is created in
 * draft state exactly like the wizard's create — assigning executors and
 * publishing (which locks the scope) stay deliberate human clicks on /plans;
 * confirming a dialog card is never a publish.
 * <p>Attribution guard (H1): the owning project comes from the SESSION, never
 * from the payload — a model-forged projectId or ownerId is simply not
 * consumed; the plan is created by (and owned by default to) the human who
 * confirmed the card. Environments named in the payload are re-checked by
 * PlanService against that project, so a cross-project env id cannot smuggle
 * a plan into someone else's setting.
 * <p>Only the kind's own anchors are validated here; every business rule
 * (scope dimension value ranges, exit-rate bounds, env membership) stays
 * single-sourced in PlanServiceImpl — a payload it rejects surfaces in its
 * own human-readable wording, never reworded into a second spectrum.
 */
@Service
public class PlanWriteDraftStrategy implements DraftWriteStrategy {

    private final PlanService planService;
    private final ChatSessionService sessionService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PlanWriteDraftStrategy(PlanService planService,
                                  ChatSessionService sessionService,
                                  JdbcTemplate jdbcTemplate) {
        this.planService = planService;
        this.sessionService = sessionService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String objectType() {
        return DraftObjectType.PLAN_WRITE.wireName;
    }

    @Override
    public Integer land(Map<String, Object> draftRow, long operatorId) {
        DraftLandContext ctx = DraftLandContext.open(draftRow, sessionService, objectMapper,
                "AI 计划草案内容无法解析，确认写入已拒绝（请重新让 AI 生成提案）");
        JsonNode body = ctx.body();
        long draftId = ctx.draftId(), sessionId = ctx.sessionId(), projectId = ctx.projectId();
        String name = DraftPayloadReads.text(body, "name");
        List<Long> envIds = readLongList(body.get("envIds"));
        JsonNode scope = body.get("caseScope");
        if (name == null || name.isBlank() || envIds.isEmpty() || scope == null || scope.isNull()) {
            throw new ServiceException(
                    "AI 计划草案缺少计划名 name、环境集合 envIds 或范围规则 caseScope，无法创建计划——请在「AI 对话」补全三项后重新生成提案；也可在「执行计划」页新建计划",
                    BizErrorCode.VALIDATION_ERROR);
        }
        // CaseScopeRule.of is the shared value-range door (phases 1~4,
        // priorities 0~2); the empty-rule refusal itself lives in
        // PlanService.create so both entries word it identically.
        PlanInfo created = planService.create(operatorId, new PlanCreateInput(
                projectId, name.trim(), DraftPayloadReads.text(body, "targetVersion"), null,
                envIds, readScopeRule(scope), readEntryCriteria(body.get("entryCriteria")),
                readExitCriteria(body.get("exitCriteria")),
                DraftPayloadReads.text(body, "planStart"), DraftPayloadReads.text(body, "planEnd"), DraftPayloadReads.integer(body, "lockMode")));
        jdbcTemplate.update("UPDATE tp_ai_chat_draft SET target_id = ?, updated_at = now() "
                + "WHERE id = ?", created.id(), draftId);
        // A create has no separate applied version for this kind (null = none).
        return null;
    }

    private CaseScopeRule readScopeRule(JsonNode scope) {
        return CaseScopeRule.of(readIntList(scope.get("phases")), readIntList(scope.get("priorities")),
                scope.path("keyword").asText(null), readLongList(scope.get("tagIds")),
                readLongList(scope.get("explicitCaseIds")));
    }

    /** Absent entry criteria = the wizard default (smoke must pass). */
    private EntryCriteria readEntryCriteria(JsonNode node) {
        if (node == null || node.isNull() || !node.has("requireSmokePass")) {
            return null;
        }
        return EntryCriteria.of(node.get("requireSmokePass").asBoolean());
    }

    /**
     * Exit criteria: a half-specified evaluation gate (rubric named without a
     * threshold) must not silently loosen the gate, so the shared
     * withEvalDoor validation is reused as-is — a body it refuses surfaces
     * its own wording. No eval fields at all = the plain of() form.
     */
    private ExitCriteria readExitCriteria(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        Double minPassRate = node.hasNonNull("minPassRate") ? node.get("minPassRate").asDouble() : null;
        Boolean noOpenDefects = node.hasNonNull("noOpenDefects") ? node.get("noOpenDefects").asBoolean() : null;
        String rubricName = DraftPayloadReads.text(node, "evalRubricName");
        if (rubricName == null || rubricName.isBlank()) {
            return ExitCriteria.of(minPassRate, noOpenDefects);
        }
        Double evalMin = node.hasNonNull("evalMinPassRate") ? node.get("evalMinPassRate").asDouble() : null;
        return ExitCriteria.withEvalGate(minPassRate, noOpenDefects, rubricName,
                DraftPayloadReads.text(node, "evalRubricDomain"), evalMin);
    }

    private static List<Long> readLongList(JsonNode node) {
        List<Long> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> {
                if (item.isNumber()) {
                    out.add(item.asLong());
                }
            });
        }
        return out;
    }

    private static List<Integer> readIntList(JsonNode node) {
        List<Integer> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> {
                if (item.isNumber()) {
                    out.add(item.asInt());
                }
            });
        }
        return out;
    }

}
