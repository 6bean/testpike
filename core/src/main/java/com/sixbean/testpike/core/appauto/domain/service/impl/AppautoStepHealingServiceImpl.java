package com.sixbean.testpike.core.appauto.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.common.pipeline.DenialGuidanceFrame;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.UiActionContract;
import com.sixbean.testpike.core.appauto.api.dto.AppautoAiSampleInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoStepSuggestionInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoStepSuggestionView;
import com.sixbean.testpike.core.appauto.domain.component.AppautoConfigReader;
import com.sixbean.testpike.core.appauto.domain.service.AppautoAiSampleService;
import com.sixbean.testpike.core.appauto.domain.service.AppautoStepHealingService;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.webelement.api.dto.WebElementAnchorInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementView;
import com.sixbean.testpike.core.webelement.domain.component.WebElementLifecycle;
import com.sixbean.testpike.core.webelement.domain.service.WebElementService;
import com.sixbean.testpike.core.webelement.domain.service.impl.WebElementServiceImpl;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskInfo;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import com.sixbean.testpike.core.script.api.dto.ScriptInfo;
import com.sixbean.testpike.core.script.domain.service.ScriptService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;


@Service
public class AppautoStepHealingServiceImpl implements AppautoStepHealingService {

 
    static final int STATUS_PENDING = 1;
    static final int STATUS_ADOPTED = 2;
    static final int STATUS_REJECTED = 3;

    
    private static final int FAILURE_CLASS_UI_CHANGE = 2;

    // WE S2 object dimension. APP rows patch one step target inside a
    // case script; WEB rows replace a locator anchor on one ledger entry so
    // every referencing case heals together (design 2.3 / A3).
    static final String OBJECT_APP = "APP";
    static final String OBJECT_WEB = "WEB";

    private static final String SELECT_COLUMNS = """
            id, project_id, case_id, source_task_id, step_no, failure_class, anchor,
            source, proposed_action, proposed_target, reason, x, y, status,
            adopted_script_version, created_at, object_type, element_id, adopted_element_revision
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ExecTaskService execTaskService;
    private final ScriptService scriptService;
    private final AppautoAiSampleService aiSampleService;
    private final AppautoConfigReader configReader;
    private final ObjectMapper objectMapper;
    private final WebElementService webElementService;

    public AppautoStepHealingServiceImpl(NamedParameterJdbcTemplate jdbc,
                                         ExecTaskService execTaskService,
                                         ScriptService scriptService,
                                         AppautoAiSampleService aiSampleService,
                                         AppautoConfigReader configReader,
                                         ObjectMapper objectMapper,
                                         WebElementService webElementService) {
        this.jdbc = jdbc;
        this.execTaskService = execTaskService;
        this.scriptService = scriptService;
        this.aiSampleService = aiSampleService;
        this.configReader = configReader;
        this.objectMapper = objectMapper;
        this.webElementService = webElementService;
    }

    @Override
    public long record(AppautoStepSuggestionInput input) {
        String objectType = normalizeType(input == null ? null : input.objectType());
        validate(input, objectType);
        ExecTaskInfo task = execTaskService.get(input.taskId());
        // Web rows re-point ONE ledger entry, so the entry must exist inside the
        // same project as the failing task (ownership is never taken on trust).
        if (OBJECT_WEB.equals(objectType)) {
            requireLedgerInProject(input.elementId(), task.projectId());
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("pid", task.projectId())
                .addValue("cid", task.caseId())
                .addValue("srcTask", task.id())
                .addValue("stepNo", input.stepNo())
                .addValue("failureClass", input.failureClass())
                .addValue("anchor", input.anchor().trim())
                .addValue("source", normalize(input.source()))
                .addValue("action", input.proposedAction() == null
                        ? null : input.proposedAction().trim())
                .addValue("target", input.proposedTarget().trim())
                .addValue("reason", input.reason())
                .addValue("x", input.x())
                .addValue("y", input.y())
                .addValue("objType", objectType)
                .addValue("elemId", OBJECT_WEB.equals(objectType) ? input.elementId() : null);
        Long id = jdbc.queryForObject("""
                INSERT INTO tp_appauto_step_suggestion
                    (project_id, case_id, source_task_id, step_no, failure_class, anchor,
                     source, proposed_action, proposed_target, reason, x, y, status,
                     object_type, element_id)
                VALUES (:pid, :cid, :srcTask, :stepNo, :failureClass, :anchor,
                        :source, :action, :target, :reason, :x, :y, 1, :objType, :elemId)
                RETURNING id
                """, params, Long.class);
        return id == null ? 0L : id;
    }

    @Override
    public List<AppautoStepSuggestionView> list(long projectId, long caseId, Integer status,
                                                int limit) {
        int cap = Math.min(Math.max(limit, 1), 500);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("pid", projectId)
                .addValue("cid", caseId)
                .addValue("cap", cap);
        String statusClause = "";
        if (status != null) {
            statusClause = " AND status = :status ";
            params.addValue("status", status);
        }
        return jdbc.queryForList("SELECT " + SELECT_COLUMNS + """
                FROM tp_appauto_step_suggestion
                WHERE project_id = :pid AND case_id = :cid
                """ + statusClause + """
                ORDER BY created_at DESC, id DESC
                LIMIT :cap
                """, params).stream().map(AppautoStepHealingServiceImpl::toView).toList();
    }

    @Override
    @Audit(objectType = "appauto_step_suggestion", action = "adopt", objectId = "#suggestionId")
    public AppautoStepSuggestionView adopt(Long operatorId, long suggestionId,
                                           Integer expectedScriptVersion) {
        if (!configReader.stepHealingEnabled()) {
            throw new ServiceException(DenialGuidanceFrame.switchClosed("步骤自愈采纳",
                    AppautoConfigReader.KEY_STEP_HEALING), BizErrorCode.FORBIDDEN);
        }
        AppautoStepSuggestionView row = require(suggestionId);
        requirePending(row.status(), "采纳");
        if (OBJECT_WEB.equals(row.objectType())) {
            return adoptWeb(row, expectedScriptVersion);
        }
        ScriptInfo script = scriptService.getByCaseId(row.caseId());
        if (script == null) {
            throw new ServiceException("该用例当前没有脚本，无法按建议单落版（请先在「自愈建议单」核对用例后重新生成脚本）",
                    BizErrorCode.NOT_FOUND);
        }
        if (expectedScriptVersion == null || expectedScriptVersion != script.version()) {
            throw new ServiceException("脚本已被他人修改，请刷新「自愈建议单」后重试（当前版本 " + script.version() + "）",
                    BizErrorCode.VERSION_CONFLICT);
        }
        String merged = mergeStep(script.actionJson(), row);
        ScriptInfo saved = scriptService.saveRepaired(row.caseId(), script.schemaVersion(), merged);

        MapSqlParameterSource casParams = new MapSqlParameterSource()
                .addValue("id", suggestionId)
                .addValue("ver", saved.version());
        int flipped = jdbc.update("""
                UPDATE tp_appauto_step_suggestion
                SET status = 2, adopted_script_version = :ver, updated_at = now()
                WHERE id = :id AND status = 1
                """, casParams);
        if (flipped == 0) {
            throw new ServiceException("该建议单已被处理，本次采纳未生效（请刷新「自愈建议单」列表查看最新状态）",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
        backfillSample(row, saved);
        return require(suggestionId);
    }

    @Override
    @Audit(objectType = "appauto_step_suggestion", action = "reject", objectId = "#suggestionId")
    public void reject(Long operatorId, long suggestionId) {
        AppautoStepSuggestionView row = require(suggestionId);
        requirePending(row.status(), "驳回");
        int flipped = jdbc.update("""
                UPDATE tp_appauto_step_suggestion SET status = 3, updated_at = now()
                WHERE id = :id AND status = 1
                """, new MapSqlParameterSource("id", suggestionId));
        if (flipped == 0) {
            throw new ServiceException("该建议单已被处理，本次驳回未生效（请刷新「自愈建议单」列表查看最新状态）",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
    }

    @Override
    public AppautoStepSuggestionView find(long suggestionId) {
        return require(suggestionId);
    }

    
    private AppautoStepSuggestionView require(long suggestionId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT " + SELECT_COLUMNS + """
                FROM tp_appauto_step_suggestion WHERE id = :id
                """, new MapSqlParameterSource("id", suggestionId));
        if (rows.isEmpty()) {
            throw new ServiceException("自愈建议单不存在（id=" + suggestionId + "）——请在「自愈建议单」列表刷新后确认该行",
                    BizErrorCode.NOT_FOUND);
        }
        return toView(rows.get(0));
    }

    
    private static AppautoStepSuggestionView toView(Map<String, Object> row) {
        return new AppautoStepSuggestionView(
                ((Number) row.get("id")).longValue(),
                row.get("project_id") == null ? null : ((Number) row.get("project_id")).longValue(),
                row.get("case_id") == null ? null : ((Number) row.get("case_id")).longValue(),
                row.get("source_task_id") == null ? null
                        : ((Number) row.get("source_task_id")).longValue(),
                ((Number) row.get("step_no")).intValue(),
                ((Number) row.get("failure_class")).intValue(),
                (String) row.get("anchor"),
                (String) row.get("source"),
                (String) row.get("proposed_action"),
                (String) row.get("proposed_target"),
                (String) row.get("reason"),
                row.get("x") == null ? null : ((Number) row.get("x")).intValue(),
                row.get("y") == null ? null : ((Number) row.get("y")).intValue(),
                ((Number) row.get("status")).intValue(),
                row.get("adopted_script_version") == null ? null
                        : ((Number) row.get("adopted_script_version")).intValue(),
                row.get("created_at") == null ? null : String.valueOf(row.get("created_at")),
                row.get("object_type") == null ? "APP" : (String) row.get("object_type"),
                row.get("element_id") == null ? null : ((Number) row.get("element_id")).longValue(),
                row.get("adopted_element_revision") == null ? null
                        : ((Number) row.get("adopted_element_revision")).intValue());
    }

    
    private void requirePending(int status, String words) {
        if (status != STATUS_PENDING) {
            String state = status == STATUS_ADOPTED ? "已采纳" : "已驳回";
            throw new ServiceException("该建议单" + state + "，不可重复" + words
                    + "（每条建议单只落一次，请刷新「自愈建议单」查看最新状态）", BizErrorCode.RESOURCE_CONFLICT);
        }
    }

    
    private String mergeStep(String actionJson, AppautoStepSuggestionView row) {
        JsonNode root = readJson(actionJson, "用例脚本动作 JSON");
        JsonNode steps = root.path("steps");
        if (!steps.isArray() || row.stepNo() < 1 || row.stepNo() > steps.size()) {
            throw new ServiceException("建议单步序 " + row.stepNo() + " 与当前脚本步数不符——请刷新「自愈建议单」重新开单"
                    + "（脚本共 " + (steps.isArray() ? steps.size() : 0) + " 步）——"
                    + "脚本可能已被修改，请驳回本单并按最新版本重新出单",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (!(steps.get(row.stepNo() - 1) instanceof ObjectNode step)) {
            throw new ServiceException("建议单目标步结构异常，无法合入候选定位——请在「自愈建议单」点「驳回」后重新执行采集",
                    BizErrorCode.VALIDATION_ERROR);
        }
        step.set("action", objectMapper.getNodeFactory().textNode(row.proposedAction()));
        step.set("target", readJson(row.proposedTarget(), "候选定位策略"));
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new ServiceException("候选修复合入后动作 JSON 序列化失败：" + e.getMessage() + "——请在「自愈建议单」点「驳回」后重新采集",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

 
    private void backfillSample(AppautoStepSuggestionView row, ScriptInfo saved) {
        if (row.x() == null || row.y() == null) {
            return;
        }
        aiSampleService.recordSample(new AppautoAiSampleInput(row.projectId(), row.caseId(),
                null, row.stepNo(), row.anchor(), row.source(), "confirmed", row.x(), row.y(),
                row.sourceTaskId()));
    }

    private JsonNode readJson(String json, String what) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isObject()) {
                throw new ServiceException(what + "不是 JSON 对象，无法解析——请改成 JSON 对象形（示例格式：{\"resourceId\":\"...\"}）",
                        BizErrorCode.VALIDATION_ERROR);
            }
            return node;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException(what + "解析失败：" + e.getMessage() + "——请在「自愈建议单」点「驳回」后重新采集",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private void validate(AppautoStepSuggestionInput input, String objectType) {
        if (input == null) {
            throw new ServiceException("自愈建议单入参为空，无法建单——请从「执行中心」的失败任务发起自愈采集生成建议单", BizErrorCode.VALIDATION_ERROR);
        }
        if (!OBJECT_APP.equals(objectType) && !OBJECT_WEB.equals(objectType)) {
            throw new ServiceException("自愈建议单对象维度非法：" + objectType + "——请改成 APP 或 WEB（正确写法：APP / WEB）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.taskId() == null) {
            throw new ServiceException("自愈建议单缺少执行任务号，无法核对用例归属，已拒绝建单——请从「执行中心」失败任务发起自愈采集",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.stepNo() == null || input.stepNo() < 1) {
            throw new ServiceException("自愈建议单步序非法（须 ≥1，动作 JSON 数组序即步序，正确写法：1、2、3）——请按失败步骤序号提交",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.failureClass() == null) {
            throw new ServiceException("自愈建议单缺少失败归因——请选择归因（格式：1=产品缺陷 / 2=UI 变更嫌疑 / 3=环境）后提交",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.failureClass() != FAILURE_CLASS_UI_CHANGE) {
            throw new ServiceException("仅 UI 变更嫌疑（定位/脚本问题）的失败可进「自愈建议单」——"
                    + "当前归因=" + failureClassWords(input.failureClass())
                    + "，改用例会掩盖真实缺陷——请走缺陷登记流程处理", BizErrorCode.VALIDATION_ERROR);
        }
        if (input.anchor() == null || input.anchor().isBlank()) {
            throw new ServiceException("自愈建议单缺少失败步锚签名（同屏锚=读样回灌同源，缺锚=单不可用）——请在「自愈建议单」重新执行采集获取锚签名后再建单",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String source = normalize(input.source());
        if (!AppautoAiSampleServiceImpl.HIT_SOURCES.contains(source)) {
            throw new ServiceException("自愈建议单候选定位出处非法：" + input.source()
                    + "——请改成合法值之一（示例格式：vision / a11y / cache / manual / dom）", BizErrorCode.VALIDATION_ERROR);
        }
        if (OBJECT_WEB.equals(objectType)) {
            validateWebAnchor(input);
        } else if (input.proposedAction() == null || input.proposedAction().isBlank()
                || !UiActionContract.isBaselineVerb(input.proposedAction().trim())) {
            throw new ServiceException("候选动作 " + input.proposedAction()
                    + " 不是 app.* IR 基线动词——请改成动作词表内的 app. 前缀动作（示例：app.tap）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.proposedTarget() == null || input.proposedTarget().isBlank()) {
            throw new ServiceException("自愈建议单缺少候选定位策略（人批采纳即以此替换该步 target）——请在「自愈建议单」查看失败步详情重新采集",
                    BizErrorCode.VALIDATION_ERROR);
        }
        readJson(input.proposedTarget(), "候选定位策略");
    }

    private String failureClassWords(Integer failureClass) {
        return switch (failureClass) {
            case 1 -> "产品缺陷";
            case 3 -> "环境问题";
            default -> "未知（合法值 1/2/3）";
        };
    }

    /** Normalize the optional object dimension; legacy callers keep APP shape. */
    private String normalizeType(String objectType) {
        return objectType == null || objectType.isBlank()
                ? OBJECT_APP : objectType.trim().toUpperCase();
    }

    /** Ledger entry must exist (404 via the ledger door) and live in the task's project. */
    private void requireLedgerInProject(Long elementId, long taskProjectId) {
        WebElementView element = webElementService.findById(elementId);
        if (element.projectId() == null || element.projectId() != taskProjectId) {
            throw new ServiceException("元素修复建议单的账本条目与失败任务不属于同一项目，已拒绝建单（跨项目重锚越权）——请从同一项目发起（正确写法：先在顶栏切换到条目所属项目）",
                    BizErrorCode.FORBIDDEN);
        }
    }

    /** Web-shape record gate: a ledger target, an in-domain anchor, and NO action edit
     * (red line 1 — an element repair may only re-point the locator). */
    private void validateWebAnchor(AppautoStepSuggestionInput input) {
        if (input.elementId() == null) {
            throw new ServiceException("元素修复建议单缺少目标账本条目号（重锚只改「图像元素库（APP）」这一条，全引用用例同时受益）——请在该页选中条目后发起修复",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.proposedAction() != null && !input.proposedAction().isBlank()) {
            throw new ServiceException("元素修复建议单只允许等价重锚，不得顺带改动步骤动作——如需改动作请编辑该步骤（正确写法：保持动作不变仅换定位，收到「"
                    + input.proposedAction().trim() + "」；动作变更请走脚本修复通道）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        JsonNode target = readJson(input.proposedTarget(), "候选定位锚");
        String type = target.path("type").asText("").trim().toLowerCase();
        if (!WebElementServiceImpl.ANCHOR_TYPES.contains(type)) {
            throw new ServiceException("候选锚类型「" + (type.isEmpty() ? "空" : type)
                    + "」不在「页面元素库」支持的锚类型范围内——请改成支持的类型（示例见「页面元素库」录入表单）", BizErrorCode.VALIDATION_ERROR);
        }
        if (target.path("value").asText("").isBlank()) {
            throw new ServiceException("候选锚表达式不能为空（类型 " + type + "）——请在「图像元素库（APP）」补录该条目的定位表达式",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    /**
     * Web adoption (four doors shared with APP, no fork): switch gate + pending gate
     * already passed upstream; the ledger optimistic lock is the revision token, the
     * approved anchor lands FIRST (runtime tries top-down) with equivalent old copies
     * de-duplicated; a candidate/stale entry revives to confirmed; the adopted ledger
     * revision backfills the board row as the audit trail.
     */
    private AppautoStepSuggestionView adoptWeb(AppautoStepSuggestionView row,
                                               Integer expectedRevision) {
        WebElementView element = webElementService.findById(row.elementId());
        WebElementAnchorInput proposed = parseAnchor(row.proposedTarget());
        List<WebElementAnchorInput> merged = new ArrayList<>();
        merged.add(proposed);
        for (WebElementAnchorInput anchor : element.anchors()) {
            boolean sameAnchor = anchor.type() != null
                    && anchor.type().equalsIgnoreCase(proposed.type())
                    && Objects.equals(anchor.value(), proposed.value());
            if (!sameAnchor) {
                merged.add(anchor);
            }
        }
        // Optimistic-lock comparison single point = the ledger revision token (shared
        // 40901 wording). A null expectation is refused as stale: fail-closed, same
        // posture the APP branch keeps for a missing script version.
        WebElementView updated = webElementService.updateAnchors(element.id(), merged,
                expectedRevision == null ? -1 : expectedRevision);
        if (element.state() == WebElementLifecycle.CANDIDATE
                || element.state() == WebElementLifecycle.STALE) {
            updated = webElementService.changeState(element.id(),
                    WebElementLifecycle.CONFIRMED, updated.revision());
        }
        int flipped = jdbc.update("""
                UPDATE tp_appauto_step_suggestion
                SET status = 2, adopted_element_revision = :rev, updated_at = now()
                WHERE id = :id AND status = 1
                """, new MapSqlParameterSource().addValue("id", row.id())
                        .addValue("rev", updated.revision()));
        if (flipped == 0) {
            throw new ServiceException("该建议单已被处理，本次采纳未生效（请刷新「自愈建议单」列表查看最新状态）",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
        return require(row.id());
    }

    /** Anchor JSON on the board row → ledger anchor input (ledger validates again). */
    private WebElementAnchorInput parseAnchor(String targetJson) {
        JsonNode target = readJson(targetJson, "候选定位锚");
        return new WebElementAnchorInput(
                target.path("type").asText("").trim().toLowerCase(),
                target.path("value").asText("").trim(),
                target.path("strength").isNumber() ? target.path("strength").asDouble() : null,
                target.path("note").isTextual() ? target.path("note").asText() : null);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }
}
