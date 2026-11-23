package com.sixbean.testpike.core.appauto.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.common.pipeline.DenialGuidanceFrame;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.UiActionContract;
import com.sixbean.testpike.core.appauto.api.dto.AppautoRecordDraftInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoRecordDraftStepInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoRecordDraftView;
import com.sixbean.testpike.core.appauto.domain.component.AppautoConfigReader;
import com.sixbean.testpike.core.appauto.domain.service.AppautoRecordDraftService;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskInfo;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import com.sixbean.testpike.core.script.api.dto.ScriptInfo;
import com.sixbean.testpike.core.script.api.dto.ScriptSaveInput;
import com.sixbean.testpike.core.script.domain.service.ScriptService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;


@Service
public class AppautoRecordDraftServiceImpl implements AppautoRecordDraftService {

 
    static final int STATUS_PENDING = 1;
    static final int STATUS_CONFIRMED = 2;
    static final int STATUS_REJECTED = 3;

 
    private static final int GENERATED_BY_RECORDING = 3;

 
    private static final int TITLE_MAX_LEN = 200;

    private static final String SELECT_COLUMNS = """
            id, project_id, case_id, source_task_id, title, event_source,
            items::text AS items, status, script_version, created_at
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ExecTaskService execTaskService;
    private final ScriptService scriptService;
    private final AppautoConfigReader configReader;
    private final ObjectMapper objectMapper;

    public AppautoRecordDraftServiceImpl(NamedParameterJdbcTemplate jdbc,
                                         ExecTaskService execTaskService,
                                         ScriptService scriptService,
                                         AppautoConfigReader configReader,
                                         ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.execTaskService = execTaskService;
        this.scriptService = scriptService;
        this.configReader = configReader;
        this.objectMapper = objectMapper;
    }

    @Override
    public long record(AppautoRecordDraftInput input) {
        requireGateOpen();
        if (input == null || input.taskId() == null) {
            throw new ServiceException("录制草稿登记缺少来源任务号（归属由任务行权威反查）——请从「执行中心」任务行的录制入口重新发起登记",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String source = normalizeSource(input.eventSource());
        String title = normalizeTitle(input.title());
        List<AppautoRecordDraftStepInput> steps = input.steps();
        if (steps == null || steps.isEmpty()) {
            throw new ServiceException("录制草稿至少要有 1 个草稿步（空草稿无可预览内容）——请完成至少一次界面操作后再到「待审草稿」保存",
                    BizErrorCode.VALIDATION_ERROR);
        }
        ArrayNode items = objectMapper.createArrayNode();
        for (AppautoRecordDraftStepInput step : steps) {
            items.add(validateStep(step));
        }
        ExecTaskInfo task = execTaskService.get(input.taskId());
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("pid", task.projectId())
                .addValue("cid", task.caseId())
                .addValue("srcTask", task.id())
                .addValue("title", title)
                .addValue("source", source)
                .addValue("items", items.toString());
        Long id = jdbc.queryForObject("""
                INSERT INTO tp_appauto_record_draft
                    (project_id, case_id, source_task_id, title, event_source, items, status)
                VALUES (:pid, :cid, :srcTask, :title, :source, :items::jsonb, 1)
                RETURNING id
                """, params, Long.class);
        return id == null ? 0L : id;
    }

    @Override
    public List<AppautoRecordDraftView> list(long caseId, Integer status, int limit) {
        int cap = Math.min(Math.max(limit, 1), 500);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("cid", caseId)
                .addValue("cap", cap);
        String statusClause = "";
        if (status != null) {
            statusClause = " AND status = :status ";
            params.addValue("status", status);
        }
        return jdbc.queryForList("SELECT " + SELECT_COLUMNS + """
                FROM tp_appauto_record_draft
                WHERE case_id = :cid
                """ + statusClause + """
                ORDER BY created_at DESC, id DESC
                LIMIT :cap
                """, params).stream().map(AppautoRecordDraftServiceImpl::toView).toList();
    }

    @Override
    public AppautoRecordDraftView find(long draftId) {
        return require(draftId);
    }

    @Override
    @Audit(objectType = "appauto_record_draft", action = "confirm", objectId = "#draftId")
    public AppautoRecordDraftView confirm(Long operatorId, long draftId, List<Integer> checkedStepNos,
                                          Integer expectedScriptVersion) {
        requireGateOpen();
        AppautoRecordDraftView row = require(draftId);
        requirePending(row.status(), "入库");
        if (checkedStepNos == null || checkedStepNos.isEmpty()) {
            throw new ServiceException("请先在「待审草稿」勾选要入库的草稿步（勾选确认才可入库，"
                    + "不勾选=零落版）", BizErrorCode.VALIDATION_ERROR);
        }
        List<ObjectNode> picked = pickCheckedSteps(row.items(), checkedStepNos);

        ScriptInfo script = scriptService.getByCaseId(row.caseId());
        String schemaVersion = script == null ? "v2" : script.schemaVersion();
        String merged = script == null
                ? buildNewScript(picked)
                : appendSteps(script.actionJson(), picked);
        ScriptInfo saved = scriptService.save(new ScriptSaveInput(row.caseId(), schemaVersion,
                merged, GENERATED_BY_RECORDING, operatorId, expectedScriptVersion));

        MapSqlParameterSource casParams = new MapSqlParameterSource()
                .addValue("id", draftId)
                .addValue("ver", saved.version());
        int flipped = jdbc.update("""
                UPDATE tp_appauto_record_draft
                SET status = 2, script_version = :ver, updated_at = now()
                WHERE id = :id AND status = 1
                """, casParams);
        if (flipped == 0) {
            throw new ServiceException("该草稿已被处理，本次入库未生效（请刷新「待审草稿」列表查看最新状态）",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
        return require(draftId);
    }

    @Override
    @Audit(objectType = "appauto_record_draft", action = "reject", objectId = "#draftId")
    public void reject(Long operatorId, long draftId) {
        AppautoRecordDraftView row = require(draftId);
        requirePending(row.status(), "驳回");
        int flipped = jdbc.update("""
                UPDATE tp_appauto_record_draft SET status = 3, updated_at = now()
                WHERE id = :id AND status = 1
                """, new MapSqlParameterSource("id", draftId));
        if (flipped == 0) {
            throw new ServiceException("该草稿已被处理，本次驳回未生效（请刷新「待审草稿」列表查看最新状态）",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
    }


 
    private void requireGateOpen() {
        if (!configReader.recordToFlowEnabled()) {
            throw new ServiceException(DenialGuidanceFrame.switchClosed("录制转流程",
                    AppautoConfigReader.KEY_RECORD_TO_FLOW), BizErrorCode.FORBIDDEN);
        }
    }

    
    private String normalizeSource(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ServiceException("录制草稿缺少采集档位（native 引擎原生事件流 / "
                    + "scrcpy 安卓回落输入注入流）——请选择采集档位（正确写法：native 或 scrcpy）后重新保存", BizErrorCode.VALIDATION_ERROR);
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (!AppautoRecordDraftService.EVENT_SOURCES.contains(normalized)) {
            throw new ServiceException("采集档位「" + raw.trim() + "」不在值域——请改成「native」或「scrcpy」后重新保存（原值 native / scrcpy）"
                    + "——两种事件源皆不可用的引擎不产草稿（录制入口置灰，拒绝原因见引擎健康卡）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return normalized;
    }

    private String normalizeTitle(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > TITLE_MAX_LEN) {
            throw new ServiceException("草稿标题过长（" + trimmed.length() + " 字，上限 "
                    + TITLE_MAX_LEN + " 字）——请在「待审草稿」标题输入框改短后重新保存", BizErrorCode.VALIDATION_ERROR);
        }
        return trimmed;
    }

    
    private ObjectNode validateStep(AppautoRecordDraftStepInput step) {
        if (step == null) {
            throw new ServiceException("草稿步不能为空行——请在「待审草稿」删除该行或补齐动作后重新预览", BizErrorCode.VALIDATION_ERROR);
        }
        if (step.stepNo() == null || step.stepNo() < 1) {
            throw new ServiceException("草稿步缺少步序（步序须为 ≥1 的录制时间序，正确写法：1、2、3 连续编号）——请重新录制生成",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String violation = UiActionContract.appActionViolation(step.action());
        if (violation != null) {
            throw new ServiceException("草稿步 " + step.stepNo() + " 动作非法：" + violation + "——请改成动作词表内的 app. 前缀动作（示例：app.tap）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        JsonNode target = readJsonObject(step.target(), "草稿步 " + step.stepNo() + " 的定位策略");
        ObjectNode node = objectMapper.createObjectNode();
        node.put("stepNo", step.stepNo());
        node.put("action", step.action().trim());
        node.set("target", target);
        if (step.value() != null && !step.value().isBlank()) {
            node.put("value", step.value());
        }
        if (step.reason() != null && !step.reason().isBlank()) {
            node.put("reason", step.reason().trim());
        }
        return node;
    }

    
    private List<ObjectNode> pickCheckedSteps(String itemsJson, List<Integer> checkedStepNos) {
        JsonNode root = readJson(itemsJson, "草稿步数组");
        if (!root.isArray()) {
            throw new ServiceException("草稿步数组结构异常，无法勾选入库——请在「待审草稿」重新预览；仍异常请点「驳回该草稿」后重新录制",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Map<Integer, ObjectNode> byStepNo = new java.util.LinkedHashMap<>();
        for (JsonNode item : root) {
            if (item instanceof ObjectNode obj && obj.path("stepNo").isInt()) {
                byStepNo.put(obj.path("stepNo").asInt(), obj);
            }
        }
        List<ObjectNode> picked = new java.util.ArrayList<>();
        for (Integer stepNo : checkedStepNos) {
            ObjectNode item = stepNo == null ? null : byStepNo.get(stepNo);
            if (item == null) {
                throw new ServiceException("勾选的草稿步序 " + stepNo + " 不存在（草稿共 "
                        + byStepNo.size() + " 步）——请在「待审草稿」刷新预览后重新勾选",
                        BizErrorCode.VALIDATION_ERROR);
            }
            picked.add(item);
        }
        return picked;
    }

    
    private String buildNewScript(List<ObjectNode> picked) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("version", "v2");
        ArrayNode steps = root.putArray("steps");
        for (ObjectNode item : picked) {
            steps.add(toScriptStep(item));
        }
        return writeJson(root, "录制入库动作 JSON");
    }

    
    private String appendSteps(String actionJson, List<ObjectNode> picked) {
        JsonNode root = readJson(actionJson, "用例脚本动作 JSON");
        if (!(root instanceof ObjectNode obj)) {
            throw new ServiceException("当前脚本动作 JSON 结构异常，录制步无法尾追合并——"
                    + "请先在「待审草稿」人工核对脚本后再入库", BizErrorCode.VALIDATION_ERROR);
        }
        JsonNode steps = obj.path("steps");
        if (!steps.isArray()) {
            obj.putArray("steps");
            steps = obj.path("steps");
        }
        for (ObjectNode item : picked) {
            ((ArrayNode) steps).add(toScriptStep(item));
        }
        return writeJson(obj, "录制入库动作 JSON");
    }

    
    private ObjectNode toScriptStep(ObjectNode item) {
        ObjectNode step = objectMapper.createObjectNode();
        step.set("action", item.path("action").deepCopy());
        step.set("target", item.path("target").deepCopy());
        if (item.hasNonNull("value")) {
            step.set("value", item.path("value").deepCopy());
        }
        return step;
    }

    
    private AppautoRecordDraftView require(long draftId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT " + SELECT_COLUMNS + """
                FROM tp_appauto_record_draft WHERE id = :id
                """, new MapSqlParameterSource("id", draftId));
        if (rows.isEmpty()) {
            throw new ServiceException("录制草稿不存在（id=" + draftId + "）——请刷新「待审草稿」列表确认该行，或重新录制生成新草稿",
                    BizErrorCode.NOT_FOUND);
        }
        return toView(rows.get(0));
    }

    
    private static AppautoRecordDraftView toView(Map<String, Object> row) {
        return new AppautoRecordDraftView(
                ((Number) row.get("id")).longValue(),
                row.get("project_id") == null ? null : ((Number) row.get("project_id")).longValue(),
                row.get("case_id") == null ? null : ((Number) row.get("case_id")).longValue(),
                row.get("source_task_id") == null ? null
                        : ((Number) row.get("source_task_id")).longValue(),
                (String) row.get("title"),
                (String) row.get("event_source"),
                (String) row.get("items"),
                ((Number) row.get("status")).intValue(),
                row.get("script_version") == null ? null
                        : ((Number) row.get("script_version")).intValue(),
                row.get("created_at") == null ? null : String.valueOf(row.get("created_at")));
    }

    
    private void requirePending(int status, String words) {
        if (status != STATUS_PENDING) {
            String state = status == STATUS_CONFIRMED ? "已入库" : "已驳回";
            throw new ServiceException("该草稿" + state + "，不可重复" + words
                    + "（每份草稿只入库一次，请刷新「待审草稿」查看最新状态）", BizErrorCode.RESOURCE_CONFLICT);
        }
    }

    private JsonNode readJson(String text, String what) {
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            throw new ServiceException(what + "解析失败：" + e.getMessage() + "——请在「待审草稿」点「驳回该草稿」后重新录制",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    private JsonNode readJsonObject(String text, String what) {
        if (text == null || text.isBlank()) {
            throw new ServiceException(what + "不能为空（定位策略须为 JSON 对象）——请在「待审草稿」补齐该步定位策略后重新勾选入库",
                    BizErrorCode.VALIDATION_ERROR);
        }
        JsonNode node = readJson(text, what);
        if (!(node.isObject())) {
            throw new ServiceException(what + "必须是 JSON 对象形——请改成对象形（示例格式：{\"resourceId\":\"...\"}）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return node;
    }

    private String writeJson(JsonNode node, String what) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new ServiceException(what + "序列化失败：" + e.getMessage() + "——请在「待审草稿」点「驳回该草稿」后重新录制",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }
}
