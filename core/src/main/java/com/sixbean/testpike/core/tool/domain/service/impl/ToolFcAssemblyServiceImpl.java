package com.sixbean.testpike.core.tool.domain.service.impl;

import com.sixbean.testpike.common.approval.ApprovalStatus;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.tool.api.dto.ToolFcAssemblyItem;
import com.sixbean.testpike.core.tool.domain.component.ToolAccessGate;
import com.sixbean.testpike.core.tool.domain.service.ToolFcAssemblyService;
import com.sixbean.testpike.core.tool.infrastructure.dataobject.ToolFcAssemblyRow;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;


@Service
public class ToolFcAssemblyServiceImpl implements ToolFcAssemblyService {

    
    private static final int PUBLISHED_CODE = ApprovalStatus.PUBLISHED.code();

    private final NamedParameterJdbcTemplate named;
    private final ToolAccessGate accessGate;

    public ToolFcAssemblyServiceImpl(NamedParameterJdbcTemplate named,
                                     ToolAccessGate accessGate) {
        this.named = named;
        this.accessGate = accessGate;
    }

    @Override
    public List<ToolFcAssemblyItem> getAssemblyItems(Long projectId) {
        return queryPublishedEnabled(projectId);
    }

    @Override
    public List<ToolFcAssemblyItem> getAssemblyItems(Long projectId, Long actorId) {
        if (actorId == null) {
            // Legacy seam: system/anonymous caller keeps the old published∩enabled
            // verdict untouched (stage 3 identity injection tightens this).
            return queryPublishedEnabled(projectId);
        }
        accessGate.requireProjectVisible(projectId, actorId);
        List<ToolFcAssemblyItem> items = queryPublishedEnabled(projectId);
        if (accessGate.execSwitchOn()) {
            return items;
        }
        return items.stream()
                .filter(i -> !ToolAccessGate.isExecClass(i.execKind()))
                .toList();
    }

    @Override
    public List<com.sixbean.testpike.core.tool.api.dto.ToolDraftKindItem>
            getDraftKinds(Long projectId, Long actorId) {
        // Same visibility verdict as the FC assembly (member gate + R34
        // project ∪ system-level union) — one read shape, zero fork: the
        // emitter must never see a tool the FC loop itself could not offer.
        if (actorId != null) {
            accessGate.requireProjectVisible(projectId, actorId);
        }
        return named.query("""
                SELECT t.name AS toolName, t.exec_spec ->> 'draftObjectType' AS objectType
                FROM tp_tool t
                LEFT JOIN tp_project_tool pt ON pt.tool_id = t.id AND pt.project_id = :projectId
                WHERE t.status = :published
                  AND ((t.project_id IS NULL AND COALESCE(pt.enabled, 1) = 1)
                       OR (t.project_id = :projectId AND pt.enabled = 1))
                  AND t.exec_spec ->> 'draftObjectType' IS NOT NULL
                ORDER BY t.id ASC
                """,
                new MapSqlParameterSource()
                        .addValue("projectId", projectId)
                        .addValue("published", PUBLISHED_CODE),
                (rs, rowNum) -> new com.sixbean.testpike.core.tool.api.dto.ToolDraftKindItem(
                        rs.getString("toolName"), rs.getString("objectType")));
    }

    /** published∩enabled read, shared by both overloads (single point, no duplication).
     * <p>AR batch: system-level tools (project_id IS NULL) pass through by default —
     * no binding row required. A project may opt one out with an explicit
     * enabled=0 override row (auditable account); project-private tools keep
     * the existing rule (bound with enabled=1 only, other projects' private
     * rows never leak — H1 isolation untouched). */
    private List<ToolFcAssemblyItem> queryPublishedEnabled(Long projectId) {
        Integer n = named.queryForObject("SELECT count(*) FROM tp_project WHERE id = :id",
                new MapSqlParameterSource("id", projectId), Integer.class);
        if (n == null || n == 0) {
            throw new ServiceException("项目不存在（id=" + projectId + "）——请到「项目管理」页确认所选项目；"
                    + "已归档的可由系统管理员点「归档项目」按钮后「还原」，再重新发起操作",
                    BizErrorCode.NOT_FOUND);
        }
        List<ToolFcAssemblyRow> rows = named.query("""
                SELECT t.id AS tool_id, t.name, t.type, t.exec_kind,
                       t.input_schema::text AS input_schema_json, t.timeout_ms,
                       t.exec_spec ->> 'function' AS function_name
                FROM tp_tool t
                LEFT JOIN tp_project_tool pt ON pt.tool_id = t.id AND pt.project_id = :projectId
                WHERE t.status = :published
                  AND ((t.project_id IS NULL AND COALESCE(pt.enabled, 1) = 1)
                       OR (t.project_id = :projectId AND pt.enabled = 1))
                ORDER BY t.id ASC
                """, new MapSqlParameterSource()
                .addValue("projectId", projectId)
                .addValue("published", PUBLISHED_CODE),
                new BeanPropertyRowMapper<>(ToolFcAssemblyRow.class));
        return rows.stream().map(ToolFcAssemblyRow::toItem).toList();
    }
}
