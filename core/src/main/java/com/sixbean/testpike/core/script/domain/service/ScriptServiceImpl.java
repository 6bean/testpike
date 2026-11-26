package com.sixbean.testpike.core.script.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.cases.api.dto.CaseVersionFieldChange;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.script.api.dto.Flowchart;
import com.sixbean.testpike.core.script.api.dto.ScriptInfo;
import com.sixbean.testpike.core.script.api.dto.ScriptSaveInput;
import com.sixbean.testpike.core.script.api.dto.ScriptVersionDiff;
import com.sixbean.testpike.core.script.api.dto.ScriptVersionInfo;
import com.sixbean.testpike.core.script.domain.component.ActionJsonDiffCalculator;
import com.sixbean.testpike.core.script.domain.component.ManualEditValidator;
import com.sixbean.testpike.core.script.domain.component.SaveRowState;
import com.sixbean.testpike.core.script.domain.component.ScriptEnvelopeSupport;
import com.sixbean.testpike.core.script.domain.component.ScriptStaticValidator;
import com.sixbean.testpike.core.script.domain.component.FlowchartMermaidAssembler;
import com.sixbean.testpike.core.script.domain.component.ScriptInvalidationSupport;
import com.sixbean.testpike.core.script.domain.component.ScriptVersionSupport;
import com.sixbean.testpike.core.webelement.domain.component.WebElementGeneratedCandidateLedger;
import com.sixbean.testpike.common.util.ScriptSourceContract;

import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.util.List;
import java.sql.SQLException;


@Service
public class ScriptServiceImpl implements ScriptService {

    private static final String COLUMNS = """
            id, case_id, schema_version, action_json, status, generated_by, last_trial_result,
            version, to_char(created_at, 'YYYY-MM-DD HH24:MI:SS'), to_char(updated_at, 'YYYY-MM-DD HH24:MI:SS')
            """;

    private final JdbcTemplate jdbcTemplate;
    private final CaseService caseService;
    private final ScriptVersionSupport scriptVersionSupport;
    private final ScriptInvalidationSupport scriptInvalidationSupport;
    private final ScriptStaticValidator scriptStaticValidator;
    private final ManualEditValidator manualEditValidator;
    private final ScriptEnvelopeSupport scriptEnvelopeSupport;
    private final AuditLogService auditLogService;
    private final WebElementGeneratedCandidateLedger generatedCandidateLedger;
    private final com.sixbean.testpike.core.cases.domain.component.CaseStepScriptAnchorBackfill stepAnchorBackfill;

    public ScriptServiceImpl(JdbcTemplate jdbcTemplate, CaseService caseService,
                             ScriptVersionSupport scriptVersionSupport,
                             ScriptInvalidationSupport scriptInvalidationSupport,
                             ScriptStaticValidator scriptStaticValidator,
                             ManualEditValidator manualEditValidator,
                             ScriptEnvelopeSupport scriptEnvelopeSupport,
                             AuditLogService auditLogService,
                             WebElementGeneratedCandidateLedger generatedCandidateLedger,
                             com.sixbean.testpike.core.cases.domain.component.CaseStepScriptAnchorBackfill stepAnchorBackfill) {
        this.jdbcTemplate = jdbcTemplate;
        this.caseService = caseService;
        this.scriptVersionSupport = scriptVersionSupport;
        this.scriptInvalidationSupport = scriptInvalidationSupport;
        this.scriptStaticValidator = scriptStaticValidator;
        this.manualEditValidator = manualEditValidator;
        this.scriptEnvelopeSupport = scriptEnvelopeSupport;
        this.auditLogService = auditLogService;
        this.generatedCandidateLedger = generatedCandidateLedger;
        this.stepAnchorBackfill = stepAnchorBackfill;
    }

    @Override
    @Transactional
    public ScriptInfo save(ScriptSaveInput input) {
        boolean manual = input.generatedBy() == ManualEditValidator.GENERATED_BY_MANUAL;
        return saveInternal(input, manual ? ScriptVersionSupport.REASON_MANUAL_EDIT
                : ScriptVersionSupport.REASON_SAVE);
    }

    
    @Override
    @Transactional
    public ScriptInfo saveRepaired(long caseId, String schemaVersion, String actionJson) {
        return saveInternal(new ScriptSaveInput(caseId, schemaVersion, actionJson, 1),
                ScriptVersionSupport.REASON_AI_REPAIR);
    }

    
    private ScriptInfo saveInternal(ScriptSaveInput input, String snapshotReason) {
        if (input.caseId() <= 0) {
            throw new ServiceException("用例不能为空，请在「用例详情」页进入脚本编辑后再保存", BizErrorCode.VALIDATION_ERROR);
        }
        var caseInfo = caseService.get(input.caseId());
        if (input.schemaVersion() == null || input.schemaVersion().isBlank()) {
            throw new ServiceException("schema 版本不能为空，请勿清空该字段（格式：schemaVersion=v2）", BizErrorCode.VALIDATION_ERROR);
        }
        if (input.actionJson() == null || input.actionJson().isBlank()) {
            throw new ServiceException("动作 JSON 不能为空，请填写后保存（格式：{\"actions\": [...]}）", BizErrorCode.VALIDATION_ERROR);
        }
        if (input.generatedBy() < 1 || input.generatedBy() > 4) {
            throw new ServiceException("脚本来源仅支持 1=AI阶段2 2=codegen 3=录制 4=手工编辑，请按上述取值改成正确写法后重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
        boolean manual = input.generatedBy() == ManualEditValidator.GENERATED_BY_MANUAL;
        String effectiveActionJson = manual
                ? scriptEnvelopeSupport.ensureEnvelope(input.actionJson(), caseInfo)
                : input.actionJson();
        scriptStaticValidator.check(caseInfo.projectId(), input.generatedBy(), effectiveActionJson,
                input.operatorId());
        SaveRowState old = readVersionAndDigest(input.caseId());
        if (input.expectedVersion() != null) {
            if (old == null || old.version() != input.expectedVersion()) {
                throw new ServiceException("脚本已被他人修改，请到「脚本编辑」页刷新最新版本后重试（当前版本 "
                        + (old == null ? "无（脚本尚未生成）" : String.valueOf(old.version())) + "）",
                        BizErrorCode.VERSION_CONFLICT);
            }
        }
        if (old == null) {
            jdbcTemplate.update("""
                    INSERT INTO tp_script (case_id, schema_version, action_json, status, generated_by, version)
                    VALUES (?, ?, ?::jsonb, 1, ?, 1)
                    """, input.caseId(), input.schemaVersion(), effectiveActionJson, input.generatedBy());
        } else {
            jdbcTemplate.update("""
                    UPDATE tp_script SET schema_version = ?, action_json = ?::jsonb, generated_by = ?,
                           version = version + 1, updated_at = now()
                    WHERE case_id = ?
                    """, input.schemaVersion(), effectiveActionJson, input.generatedBy(), input.caseId());
        }
        scriptVersionSupport.write(input.caseId(), snapshotReason, input.operatorId());
        if (manual) {
            recordManualEditAudit(input.caseId(), old, readVersionAndDigest(input.caseId()));
        }
        // WE batch S5 piece D: invented inline anchors of an AI stage-2 script
        // land in the page element library as candidates (fail-soft inside the
        // recorder; other save doors never touch the ledger).
        if (input.generatedBy() == ScriptSourceContract.GENERATED_BY_AI) {
            generatedCandidateLedger.recordGenerated(caseInfo.projectId(), effectiveActionJson);
        }
        // chain ②: the step ledger absorbs the {{ELEM:name}} references the
        // saved action document actually carries (per-step page-element evidence
        // for the review UI, report A). Fail-soft inside the component — like the
        // candidate ledger above, evidence backfill never blocks a save door;
        // every write chain rides this single point (R40, no per-chain copy).
        stepAnchorBackfill.backfill(input.caseId(), effectiveActionJson);
        return getByCaseId(input.caseId());
    }

    
    private void recordManualEditAudit(long caseId, SaveRowState before, SaveRowState after) {
        auditLogService.record(new AuditLogInput(
                com.sixbean.testpike.core.audit.domain.component.OperatorContext.currentOperatorId(),
                null, "script", String.valueOf(caseId), "script_manual_edit",
                java.util.Map.of("digest", before == null ? "none" : before.digest(),
                        "version", before == null ? 0 : before.version()),
                java.util.Map.of("digest", after == null ? "none" : after.digest(),
                        "version", after == null ? 0 : after.version())));
    }

    
    private SaveRowState readVersionAndDigest(long caseId) {
        return jdbcTemplate.query("""
                        SELECT version, 'sha256:' || encode(sha256(convert_to(action_json::text, 'UTF8')), 'hex') AS digest
                        FROM tp_script WHERE case_id = ?
                        """,
                (rs, n) -> new SaveRowState(rs.getInt("version"), rs.getString("digest")),
                caseId).stream().findFirst().orElse(null);
    }

    @Override
    public ScriptInfo getByCaseId(long caseId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM tp_script WHERE case_id = ?",
                this::mapRow, caseId).stream().findFirst().orElse(null);
    }

    @Override
    @Transactional
    public void markPending(long caseId) {
        scriptInvalidationSupport.markPending(caseId);
    }

    
    @Override
    public List<ScriptVersionInfo> listVersions(long caseId) {
        caseService.get(caseId);
        return jdbcTemplate.query("""
                SELECT id, case_id, script_id, case_version, version, schema_version,
                       reason, created_at, actions_json::text AS actions
                FROM tp_script_version
                WHERE case_id = ?
                ORDER BY version DESC, id DESC
                """, (rs, n) -> new ScriptVersionInfo(
                rs.getLong("id"), rs.getLong("case_id"), rs.getLong("script_id"),
                rs.getInt("case_version"), rs.getInt("version"), rs.getString("schema_version"),
                rs.getString("reason"), rs.getObject("created_at", java.time.LocalDateTime.class),
                !ScriptVersionSupport.isJsonObject(rs.getString("actions"))), caseId);
    }

    
    @Override
    public ScriptVersionDiff diffVersion(
            long caseId, int version, Integer baseVersion) {
        caseService.get(caseId);
        String toActions = actionsJsonOf(caseId, version, "目标版本");
        int fromVersion;
        String fromActions;
        if (baseVersion != null) {
            fromVersion = baseVersion;
            fromActions = actionsJsonOf(caseId, fromVersion, "基准版本");
        } else {
            Integer prev = jdbcTemplate.queryForObject(
                    "SELECT max(version) FROM tp_script_version WHERE case_id = ? AND version < ?",
                    Integer.class, caseId, version);
            if (prev == null) {
                return new ScriptVersionDiff(
                        caseId, version, version, false, null, List.of());
            }
            fromVersion = prev;
            fromActions = actionsJsonOf(caseId, fromVersion, "基准版本");
        }
        if (!ScriptVersionSupport.isJsonObject(fromActions) || !ScriptVersionSupport.isJsonObject(toActions)) {
            return new ScriptVersionDiff(
                    caseId, fromVersion, version, true,
                    "脚本版本 v%d 与 v%d 的快照存在损坏（非 JSON 对象），无法字段级比较（请联系管理员）"
                            .formatted(fromVersion, version), List.of());
        }
        List<CaseVersionFieldChange> changes;
        try {
            changes = ActionJsonDiffCalculator.diff(fromActions, toActions);
        } catch (IllegalArgumentException e) {
            return new ScriptVersionDiff(
                    caseId, fromVersion, version, true,
                    "脚本版本快照已损坏，无法进行字段级比较：" + e.getMessage(), List.of());
        }
        return new ScriptVersionDiff(
                caseId, fromVersion, version, false, null, changes);
    }

    
    private String actionsJsonOf(long caseId, int version, String roleLabel) {
        List<String> rows = jdbcTemplate.query(
                "SELECT actions_json::text FROM tp_script_version WHERE case_id = ? AND version = ?",
                (rs, n) -> rs.getString(1), caseId, version);
        if (rows.isEmpty()) {
            throw new ServiceException("%s脚本快照 v%d 不存在，请从版本历史中选择现存版本（示例：v1）".formatted(roleLabel, version),
                    BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    
    @Override
    public Flowchart flowchart(long caseId) {
        caseService.get(caseId);
        ScriptInfo script = getByCaseId(caseId);
        try {
            return FlowchartMermaidAssembler.assemble(script == null ? null : script.actionJson());
        } catch (IllegalArgumentException e) {
            throw new ServiceException(e.getMessage(), BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    @Override
    public Flowchart previewFlowchart(long caseId, String actionJson) {
        var caseInfo = caseService.get(caseId);
        if (actionJson == null || actionJson.isBlank()) {
            throw new ServiceException("动作 JSON 不能为空，请填写后保存（格式：{\"actions\": [...]}）", BizErrorCode.VALIDATION_ERROR);
        }
        String effectiveActionJson = scriptEnvelopeSupport.ensureEnvelope(actionJson, caseInfo);
        scriptStaticValidator.check(caseInfo.projectId(), ManualEditValidator.GENERATED_BY_MANUAL,
                effectiveActionJson, com.sixbean.testpike.core.audit.domain.component.OperatorContext.currentOperatorId());
        try {
            return FlowchartMermaidAssembler.assemble(effectiveActionJson);
        } catch (IllegalArgumentException e) {
            throw new ServiceException(e.getMessage(), BizErrorCode.VALIDATION_ERROR);
        }
    }

    private ScriptInfo mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ScriptInfo(
                rs.getLong("id"), rs.getLong("case_id"), rs.getString("schema_version"),
                rs.getString("action_json"), rs.getInt("status"), rs.getInt("generated_by"),
                (Integer) rs.getObject("last_trial_result"), rs.getInt("version"),
                rs.getString(9), rs.getString(10));
    }
}
