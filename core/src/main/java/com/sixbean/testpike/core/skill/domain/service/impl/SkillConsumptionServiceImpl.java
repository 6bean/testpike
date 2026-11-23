package com.sixbean.testpike.core.skill.domain.service.impl;

import com.sixbean.testpike.common.approval.ApprovalStatus;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.skill.api.dto.SkillAssemblyItem;
import com.sixbean.testpike.core.skill.api.dto.SkillUsageInput;
import com.sixbean.testpike.core.skill.domain.component.ProjectSkillRole;
import com.sixbean.testpike.core.skill.domain.service.SkillConsumptionService;
import com.sixbean.testpike.core.skill.infrastructure.dataobject.SkillAssemblyRow;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;


@Service
public class SkillConsumptionServiceImpl implements SkillConsumptionService {

 
    private static final int STAGE_MIN = 1;
    private static final int STAGE_MAX = 4;
    
    private static final int PUBLISHED_CODE = ApprovalStatus.PUBLISHED.code();

    private final NamedParameterJdbcTemplate named;

    public SkillConsumptionServiceImpl(NamedParameterJdbcTemplate named) {
        this.named = named;
    }

    @Override
    public List<SkillAssemblyItem> getAssemblyItems(Long projectId) {
        Integer n = named.queryForObject("SELECT count(*) FROM tp_project WHERE id = :id",
                new MapSqlParameterSource("id", projectId), Integer.class);
        if (n == null || n == 0) {
            throw new ServiceException("项目不存在（id=" + projectId + "）："
                    + "请改用真实存在的 projectId（可在「项目管理」页查看项目清单）",
                    BizErrorCode.NOT_FOUND);
        }
        // AR batch: published system-level skills (project_id IS NULL) assemble with
        // zero binding rows; defaults mirror the column semantics (role=general,
        // priority=0, enabled=1). An explicit enabled=0 override row opts one out
        // for that project only. Project-private skills keep requiring an enabled
        // binding row; other projects' private rows never leak (H1 untouched).
        // distilled project memory (tp_ai_ctx_memory, inject_status=1)
        // rides this SAME assembly channel as kind=1 rows — one retrieval/budget/
        // assembly pipeline (R40 single source), never a second injection chain.
        // Read order: skills first (kind ASC), then priority DESC; inside memory the
        // newest id leads (the K8 memory-max-items cap on the ai-service side trims
        // the tail of THAT ordering, so recency wins over stale entries).
        List<SkillAssemblyRow> rows = named.query("""
                SELECT skill_id, name, role, priority, enabled, version, body, kind FROM (
                    SELECT s.id AS skill_id, s.name,
                           COALESCE(ps.role, :defaultRole) AS role,
                           COALESCE(ps.priority, 0) AS priority,
                           COALESCE(ps.enabled, 1) AS enabled,
                           s.current_version AS version, s.body, 0 AS kind
                    FROM tp_skill s
                    LEFT JOIN tp_project_skill ps ON ps.skill_id = s.id AND ps.project_id = :projectId
                    WHERE s.status = :published AND s.deleted = 0
                      AND ((s.project_id IS NULL AND COALESCE(ps.enabled, 1) = 1)
                           OR (s.project_id = :projectId AND ps.enabled = 1))
                    UNION ALL
                    SELECT m.id AS skill_id, '项目记忆' AS name, 3 AS role, 0 AS priority,
                           1 AS enabled, 0 AS version, m.content AS body, 1 AS kind
                    FROM tp_ai_ctx_memory m
                    WHERE m.project_id = :projectId AND m.inject_status = 1 AND m.deleted = 0
                ) merged
                ORDER BY kind ASC, priority DESC,
                         CASE WHEN kind = 1 THEN -skill_id ELSE skill_id END ASC
                """, new MapSqlParameterSource()
                .addValue("projectId", projectId)
                .addValue("published", PUBLISHED_CODE)
                .addValue("defaultRole", ProjectSkillRole.DEFAULT.code()),
                new BeanPropertyRowMapper<>(SkillAssemblyRow.class));
        return rows.stream().map(SkillAssemblyRow::toItem).toList();
    }

    @Override
    public int recordUsages(List<SkillUsageInput> usages) {
        if (usages == null || usages.isEmpty()) {
            return 0;
        }
        for (SkillUsageInput u : usages) {
            validateShape(u);
        }
        int written = 0;
        for (SkillUsageInput u : usages) {
            written += named.update("""
                    INSERT INTO tp_skill_usage (task_id, skill_id, skill_version, stage, dropped)
                    SELECT :taskId, :skillId, :version, :stage, :dropped
                    WHERE EXISTS (SELECT 1 FROM tp_skill WHERE id = :skillId AND deleted = 0)
                    """, new MapSqlParameterSource()
                    .addValue("taskId", u.taskId())
                    .addValue("skillId", u.skillId())
                    .addValue("version", u.version())
                    .addValue("stage", u.stage())
                    .addValue("dropped", u.dropped()));
        }
        return written;
    }

    
    private void validateShape(SkillUsageInput u) {
        if (u == null || u.skillId() == null || u.skillId() <= 0) {
            throw new ServiceException("SKILL 留痕缺少 skillId：正确写法 skillId=SKILL 的数字 id，"
                    + "可在「SKILL 管理」页或「SKILL 市场」页列表获取",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (u.stage() == null || u.stage() < STAGE_MIN || u.stage() > STAGE_MAX) {
            throw new ServiceException("SKILL 留痕阶段非法（stage=" + u.stage()
                    + "）：格式：stage 请取 " + STAGE_MIN + "=F2提取、" + (STAGE_MIN + 1) + "=用例、"
                    + (STAGE_MIN + 2) + "=脚本、" + STAGE_MAX + "=修复循环",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (u.dropped() == null || (u.dropped() != 0 && u.dropped() != 1)) {
            throw new ServiceException("SKILL 留痕丢弃标记只能是 0（已注入）或 1（超预算丢弃），当前 "
                    + u.dropped() + "：请按正确写法传 dropped=0 或 dropped=1",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }
}
