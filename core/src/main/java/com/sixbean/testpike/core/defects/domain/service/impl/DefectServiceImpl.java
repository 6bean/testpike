package com.sixbean.testpike.core.defects.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.defects.api.dto.DefectInfo;
import com.sixbean.testpike.core.defects.api.dto.DefectLinkInput;
import com.sixbean.testpike.core.defects.api.dto.DefectStatusInput;
import com.sixbean.testpike.core.defects.domain.service.DefectService;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskCreateInput;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.notification.domain.service.NotificationService;
import com.sixbean.testpike.core.script.api.dto.ScriptInfo;
import com.sixbean.testpike.core.script.domain.service.ScriptService;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;


@Service
public class DefectServiceImpl implements DefectService {

    private static final Set<String> DEFECT_STATUSES = Set.of("open", "in_progress", "closed");

    private static final String COLUMNS = """
            id, case_id, defect_key, defect_status, status, version, created_by,
            to_char(created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private final NotificationService notificationService;
    private final CaseService caseService;
    private final ScriptService scriptService;
    private final ExecTaskService execTaskService;

    public DefectServiceImpl(JdbcTemplate jdbcTemplate, NotificationService notificationService,
                             CaseService caseService, ScriptService scriptService,
                             ExecTaskService execTaskService) {
        this.jdbcTemplate = jdbcTemplate;
        this.notificationService = notificationService;
        this.caseService = caseService;
        this.scriptService = scriptService;
        this.execTaskService = execTaskService;
    }

    @Override
    @Transactional
    @Audit(objectType = "case_defect", action = "defect_link", objectId = "#input.caseId", after = "#result")
    public DefectInfo link(long operatorId, DefectLinkInput input) {
        String key = input.defectKey() == null ? "" : input.defectKey().trim();
        if (key.isEmpty()) {
            throw new ServiceException("缺陷 key 不能为空", BizErrorCode.VALIDATION_ERROR);
        }
        if (key.length() > 50) {
            throw new ServiceException("缺陷 key 长度不能超过 50 个字符", BizErrorCode.VALIDATION_ERROR);
        }
        Long caseId = jdbcTemplate.query(
                "SELECT id FROM tp_test_case WHERE id = ? AND deleted = 0",
                rs -> rs.next() ? rs.getLong(1) : null, input.caseId());
        if (caseId == null) {
            throw new ServiceException("用例不存在", BizErrorCode.VALIDATION_ERROR);
        }
        String status = normalizeStatus(input.defectStatus(), "open");
        Long existing = jdbcTemplate.query(
                "SELECT id FROM tp_case_defect WHERE case_id = ? AND defect_key = ?",
                rs -> rs.next() ? rs.getLong(1) : null, input.caseId(), key);
        if (existing != null) {
            return getById(existing);
        }
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO tp_case_defect (case_id, defect_key, defect_status, status, version, created_by)
                VALUES (?, ?, ?, 1, 1, ?) RETURNING id
                """, Long.class, input.caseId(), key, status, operatorId);
        return getById(id);
    }

    @Override
    @Transactional
    @Audit(objectType = "case_defect", action = "defect_status_change", objectId = "#id", after = "#result")
    public DefectInfo updateStatus(long operatorId, long id, DefectStatusInput input) {
        String status = normalizeStatus(input.defectStatus(), null);
        DefectInfo current = getById(id);
        if (current == null) {
            throw new ServiceException("缺陷关联记录不存在", BizErrorCode.NOT_FOUND);
        }
        int rows = jdbcTemplate.update("""
                UPDATE tp_case_defect
                SET defect_status = ?,
                    status = CASE WHEN ? = 'closed' THEN 2 ELSE status END,
                    version = version + 1,
                    updated_at = now()
                WHERE id = ? AND version = ?
                """, status, status, id, input.version());
        if (rows == 0) {
            throw new ServiceException("该缺陷关联记录已被他人修改（版本号已更新）；请在「测试用例」打开该用例的缺陷列表，刷新到最新记录后重新提交本次修改", BizErrorCode.VERSION_CONFLICT);
        }
        DefectInfo updated = getById(id);
        if ("closed".equals(status)) {
            sendDefectClosedNotify(updated);
        }
        return updated;
    }

 
    private void sendDefectClosedNotify(DefectInfo defect) {
        Long userId = jdbcTemplate.query(
                "SELECT COALESCE(assignee_id, created_by) FROM tp_test_case WHERE id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, defect.caseId());
        if (userId == null || userId <= 0) {
            return;
        }
        notificationService.create(new NotificationCreateInput(userId, null, "DEFECT_CLOSED",
                "缺陷已关闭，请重跑关联用例",
                "关联缺陷 " + defect.defectKey() + " 已关闭，请前往用例详情点击「重跑用例」验证："
                        + "重跑通过恢复「已通过」，仍失败保持「执行失败」并通知项目负责人。",
                "DEFECT_CLOSED:" + defect.caseId() + ":" + defect.defectKey()));
    }

    @Override
    public List<DefectInfo> listByCase(long operatorId, long caseId) {
        return jdbcTemplate.query("""
                SELECT %s FROM tp_case_defect WHERE case_id = ? ORDER BY created_at DESC
                """.formatted(COLUMNS),
                (rs, i) -> mapRow(rs), caseId);
    }

    @Override
    public DefectInfo get(long id) {
        DefectInfo info = getById(id);
        if (info == null) {
            throw new ServiceException("缺陷关联记录不存在", BizErrorCode.NOT_FOUND);
        }
        return info;
    }

    @Override
    @Transactional
    @Audit(objectType = "case_defect", action = "defect_rerun", objectId = "#caseId")
    public long rerunHint(long operatorId, long caseId) {
        CaseInfo caseInfo;
        try {
            caseInfo = caseService.get(caseId);
        } catch (ServiceException e) {
            throw new ServiceException("用例不存在", BizErrorCode.VALIDATION_ERROR);
        }
        ScriptInfo script = scriptService.getByCaseId(caseId);
        if (script == null || script.actionJson() == null || script.actionJson().isBlank()) {
            throw new ServiceException("该用例还没有可执行的脚本，请先生成脚本再重跑",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (script.status() == 2) {
            throw new ServiceException("脚本处于「待更新」状态（用例已修改），请重新生成后再重跑",
                    BizErrorCode.STATE_MACHINE_REJECT);
        }
        Long envId = jdbcTemplate.query("""
                SELECT id FROM tp_project_env WHERE project_id = ?
                ORDER BY (env_type = 1) DESC, env_type, id LIMIT 1
                """, rs -> rs.next() ? rs.getLong(1) : null, caseInfo.projectId());
        if (envId == null) {
            throw new ServiceException("项目暂无可用执行环境，请先在环境配置中添加（开发/测试/预发）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        execTaskService.create(new ExecTaskCreateInput(caseInfo.projectId(), caseId,
                script.schemaVersion(), script.actionJson(), envId));
        jdbcTemplate.update("""
                UPDATE tp_test_case SET status = 6, version = version + 1, updated_at = now()
                WHERE id = ? AND status IN (7, 8) AND deleted = 0
                """, caseId);
        return caseInfo.id();
    }

    private DefectInfo getById(long id) {
        return jdbcTemplate.query("SELECT %s FROM tp_case_defect WHERE id = ?".formatted(COLUMNS),
                rs -> rs.next() ? mapRow(rs) : null, id);
    }

    private DefectInfo mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DefectInfo(rs.getLong("id"), rs.getLong("case_id"), rs.getString("defect_key"),
                rs.getString("defect_status"), rs.getInt("status"), rs.getInt("version"),
                rs.getLong("created_by"), rs.getString("created_at"));
    }

 
    private String normalizeStatus(String raw, String defaultVal) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) {
            if (defaultVal == null) {
                throw new ServiceException("缺陷状态不能为空", BizErrorCode.VALIDATION_ERROR);
            }
            return defaultVal;
        }
        if (!DEFECT_STATUSES.contains(s)) {
            throw new ServiceException("缺陷状态仅支持 open/in_progress/closed，实际: " + s,
                    BizErrorCode.VALIDATION_ERROR);
        }
        return s;
    }
}
