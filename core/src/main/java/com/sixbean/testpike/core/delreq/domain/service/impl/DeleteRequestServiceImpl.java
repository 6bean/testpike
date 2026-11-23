package com.sixbean.testpike.core.delreq.domain.service.impl;

import com.sixbean.testpike.common.approval.ApprovalEvent;
import com.sixbean.testpike.common.approval.ApprovalStateMachine;
import com.sixbean.testpike.common.approval.ApprovalStatus;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.common.domain.component.PageQueryPolicy;
import com.sixbean.testpike.core.common.domain.component.ProjectOwners;
import com.sixbean.testpike.core.common.domain.component.SortColumnPolicy;
import com.sixbean.testpike.core.delreq.api.dto.DeleteRequestCreateInput;
import com.sixbean.testpike.core.delreq.api.dto.DeleteRequestInfo;
import com.sixbean.testpike.core.delreq.api.dto.DeleteRequestRejectInput;
import com.sixbean.testpike.core.delreq.domain.component.DeleteRequestApprovalNotifier;
import com.sixbean.testpike.core.delreq.domain.service.DeleteRequestService;
import com.sixbean.testpike.core.delreq.infrastructure.dataobject.DeleteRequestRow;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.notification.domain.component.AdminAlertRecipients;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Service
public class DeleteRequestServiceImpl implements DeleteRequestService {

    
    private static final String OBJECT_TYPE = "delete_request";
    
    private static final String ENTITY_LABEL = "删除申请";
 
    private static final int REASON_MAX = 500;
    
    private static final int TARGET_CASE = 1;

    private static final String SELECT_COLUMNS = """
            SELECT id, target_type, target_id, project_id, target_summary, reason, reject_reason,
                   applicant_id, status, approver_id, handled_at, created_at
            FROM tp_delete_request
            """;

    private final NamedParameterJdbcTemplate named;
    private final AuditLogService auditLogService;
    private final AdminAlertRecipients adminAlertRecipients;
    private final ProjectOwners projectOwners;
    private final CaseService caseService;
    private final DeleteRequestApprovalNotifier notifier;

    public DeleteRequestServiceImpl(NamedParameterJdbcTemplate named, AuditLogService auditLogService,
                                    AdminAlertRecipients adminAlertRecipients, ProjectOwners projectOwners,
                                    CaseService caseService, DeleteRequestApprovalNotifier notifier) {
        this.named = named;
        this.auditLogService = auditLogService;
        this.adminAlertRecipients = adminAlertRecipients;
        this.projectOwners = projectOwners;
        this.caseService = caseService;
        this.notifier = notifier;
    }

    @Override
    @Transactional
    public DeleteRequestInfo create(Long applicantId, DeleteRequestCreateInput input) {
        if (applicantId == null) {
            throw new ServiceException("登录状态已失效，请在「登录 testPike」页面重新登录后再提交删除申请",
                    BizErrorCode.UNAUTHORIZED);
        }
        if (input == null) {
            throw new ServiceException("删除申请内容不能为空，正确写法：提交时携带目标用例（targetId）"
                    + "与申请理由（reason）", BizErrorCode.VALIDATION_ERROR);
        }
        Integer targetType = input.targetType();
        if (targetType == null || targetType < 1 || targetType > 3) {
            throw new ServiceException("删除目标类型非法（1=用例 2=脚本 3=SKILL），"
                    + "请改成正确写法：targetType=1（用例）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String reason = validateReason(input.reason(), "申请理由");
        if (input.projectId() == null || input.targetId() == null) {
            throw new ServiceException("项目 ID 与目标对象 ID 不能为空，请补齐后重新提交；"
                    + "正确写法：同时携带 projectId 与 targetId", BizErrorCode.VALIDATION_ERROR);
        }
        if (targetType != TARGET_CASE) {
            throw new ServiceException("删除审批当前仅支持用例（脚本/SKILL 执行面随后续任务接入），"
                    + "本次未生成审批单；请改为用例目标后重新提交，或到「测试用例」页确认目标类型",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String summary = resolveCaseSummary(input.projectId(), input.targetId());

        List<Long> pendingIds = named.queryForList("""
                SELECT id FROM tp_delete_request
                WHERE target_type = :type AND target_id = :targetId AND status = :pending
                ORDER BY id LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("type", targetType).addValue("targetId", input.targetId())
                .addValue("pending", ApprovalStatus.PENDING_APPROVAL.code()), Long.class);
        if (!pendingIds.isEmpty()) {
            throw new ServiceException("该对象已有待审批的删除申请（单号 " + pendingIds.get(0)
                    + "），请前往「删除审批」页处理已有申请后再重新提交", BizErrorCode.RESOURCE_CONFLICT);
        }

        Long id = named.queryForObject("""
                INSERT INTO tp_delete_request (target_type, target_id, project_id, target_summary,
                        reason, applicant_id, status)
                VALUES (:type, :targetId, :projectId, :summary, :reason, :applicant, :pending)
                RETURNING id
                """, new MapSqlParameterSource()
                .addValue("type", targetType)
                .addValue("targetId", input.targetId())
                .addValue("projectId", input.projectId())
                .addValue("summary", summary)
                .addValue("reason", reason)
                .addValue("applicant", applicantId)
                .addValue("pending", ApprovalStatus.PENDING_APPROVAL.code()), Long.class);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("id", id);
        after.put("targetType", targetType);
        after.put("targetId", input.targetId());
        after.put("targetSummary", summary);
        after.put("reason", reason);
        after.put("status", ApprovalStatus.PENDING_APPROVAL.code());
        auditLogService.record(new AuditLogInput(applicantId, input.projectId(), OBJECT_TYPE,
                String.valueOf(id), "create", Map.of(), after));

        DeleteRequestInfo info = get(id);
        notifier.notifySubmitted(info);
        return info;
    }

    @Override
    @Transactional
    public DeleteRequestInfo approve(Long operatorId, long id) {
        DeleteRequestRow row = findRow(id);
        requireHandler(operatorId, row);
        if (row.getStatus() == ApprovalStatus.PUBLISHED.code()) {
            return row.toInfo();
        }
        int target = ApprovalStateMachine.transition(row.getStatus(), ApprovalEvent.APPROVE,
                ENTITY_LABEL);
        int updated = named.update("""
                UPDATE tp_delete_request SET status = :target, approver_id = :operator,
                        handled_at = now(), reject_reason = NULL, updated_at = now()
                WHERE id = :id AND status = :expected
                """, new MapSqlParameterSource()
                .addValue("target", target)
                .addValue("operator", operatorId)
                .addValue("id", id)
                .addValue("expected", row.getStatus()));
        if (updated == 0) {
            throw new ServiceException("删除申请已被他人处理，请在「删除审批」页点击「查询」刷新后查看最新状态",
                    BizErrorCode.VERSION_CONFLICT);
        }
        if (row.getTargetType() == TARGET_CASE) {
            caseService.delete(operatorId, row.getTargetId());
        }
        recordTransitionAudit(operatorId, row, "approve", target);
        DeleteRequestInfo after = get(id);
        notifier.notifyApproved(operatorId, after);
        return after;
    }

    @Override
    @Transactional
    public DeleteRequestInfo reject(Long operatorId, long id, DeleteRequestRejectInput input) {
        DeleteRequestRow row = findRow(id);
        requireHandler(operatorId, row);
        String reason = validateReason(input == null ? null : input.reason(), "拒绝原因");
        int target = ApprovalStateMachine.transition(row.getStatus(), ApprovalEvent.REJECT,
                ENTITY_LABEL);
        int updated = named.update("""
                UPDATE tp_delete_request SET status = :target, approver_id = :operator,
                        handled_at = now(), reject_reason = :reason, updated_at = now()
                WHERE id = :id AND status = :expected
                """, new MapSqlParameterSource()
                .addValue("target", target)
                .addValue("operator", operatorId)
                .addValue("reason", reason)
                .addValue("id", id)
                .addValue("expected", row.getStatus()));
        if (updated == 0) {
            throw new ServiceException("删除申请已被他人处理，请在「删除审批」页点击「查询」刷新后查看最新状态",
                    BizErrorCode.VERSION_CONFLICT);
        }
        recordTransitionAudit(operatorId, row, "reject", target);
        DeleteRequestInfo after = get(id);
        notifier.notifyRejected(operatorId, after);
        return after;
    }

    @Override
    public DeleteRequestInfo get(long id) {
        return findRow(id).toInfo();
    }

    @Override
    public PageResult<DeleteRequestInfo> list(long projectId, Integer status, int page, int size) {
        return list(projectId, status, page, size, null, null);
    }

    @Override
    public PageResult<DeleteRequestInfo> list(long projectId, Integer status, int page, int size,
                                              String sortBy, String sortDir) {
        int safePage = Math.max(page, 1);
        int safeSize = PageQueryPolicy.enforceSize(size);
        MapSqlParameterSource params = new MapSqlParameterSource("projectId", projectId)
                .addValue("limit", safeSize)
                .addValue("offset", (int) PageQueryPolicy.clampOffset(safePage, safeSize,
                        PageQueryPolicy.MAX_VIRTUAL_SIZE));
        String filter = status == null ? "" : " AND status = :status";
        if (status != null) {
            params.addValue("status", status);
        }
        Long total = named.queryForObject("SELECT COUNT(*) FROM tp_delete_request"
                + " WHERE project_id = :projectId" + filter, params, Long.class);
        List<DeleteRequestInfo> rows = named.query(SELECT_COLUMNS
                        + " WHERE project_id = :projectId" + filter
                        + orderClause(sortBy, sortDir) + " LIMIT :limit OFFSET :offset",
                params, new BeanPropertyRowMapper<>(DeleteRequestRow.class))
                .stream().map(DeleteRequestRow::toInfo).toList();
        return new PageResult<>(rows, total == null ? 0 : total);
    }

    /**
 * Sort whitelist for the delete-approval ledger .
 * Values are the physical columns of tp_delete_request — the ledger query
 * is a single-table read, so no table qualifiers are needed here. The set
 * mirrors the columns the page renders and ranks meaningfully (target /
 * status / handled time / created time plus the id itself); reason and
 * reject_reason stay out: they are free text a reviewer reads, not a
 * ranking dimension, and project_id is constant inside every project
 * window, so exposing it would widen the contract without a consumer.
 */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "id", "id",
            "target_summary", "target_summary",
            "status", "status",
            "created_at", "created_at",
            "handled_at", "handled_at");

    /**
     * ORDER BY builder for the ledger window: a null/blank key keeps the
     * historical id DESC default; a whitelisted key orders by that column
     * with the id DESC tie-break pinned (deterministic paging under equal
     * values, notably NULL handled_at on pending rows); an off-list key is
     * refused fail-closed before any read.
     */
    private String orderClause(String sortBy, String sortDir) {
        String column = SortColumnPolicy.require(sortBy, SORT_COLUMNS, "删除申请");
        if (column == null) {
            return " ORDER BY id DESC";
        }
        return " ORDER BY " + column
                + ("asc".equalsIgnoreCase(sortDir) ? " ASC" : " DESC")
                + ", id DESC";
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

 
    private String resolveCaseSummary(long projectId, long caseId) {
        List<Map<String, Object>> hits = named.queryForList(
                "SELECT case_no, title, project_id, deleted FROM tp_test_case WHERE id = :id",
                new MapSqlParameterSource("id", caseId));
        if (hits.isEmpty()) {
            throw new ServiceException("删除目标用例不存在（id=" + caseId
                    + "），请到「测试用例」页确认用例编号后再提交删除申请", BizErrorCode.NOT_FOUND);
        }
        Map<String, Object> hit = hits.get(0);
        int deleted = ((Number) hit.get("deleted")).intValue();
        if (deleted == 1) {
            throw new ServiceException("删除目标用例已在回收站（id=" + caseId
                    + "），无需重复申请；请在「测试用例」页打开「回收站」还原该用例后再操作",
                    BizErrorCode.NOT_FOUND);
        }
        long ownerProject = ((Number) hit.get("project_id")).longValue();
        if (ownerProject != projectId) {
            throw new ServiceException("删除目标用例不属于该项目（用例在项目 " + ownerProject
                    + "，申请项目 " + projectId + "），禁止跨项目灌单；"
                    + "请在顶栏切换到用例所属项目后再到「删除审批」页提交", BizErrorCode.VALIDATION_ERROR);
        }
        String summary = hit.get("case_no") + "「" + hit.get("title") + "」";
        if (summary.length() > 200) {
            summary = summary.substring(0, 197) + "...";
        }
        return summary;
    }

    
    private void requireHandler(Long operatorId, DeleteRequestRow row) {
        if (operatorId == null) {
            throw new ServiceException("登录状态已失效，请在「登录 testPike」页面重新登录后再处理删除申请",
                    BizErrorCode.UNAUTHORIZED);
        }
        boolean admin = adminAlertRecipients.adminUserIds().contains(operatorId);
        boolean projectOwner = projectOwners.resolve(row.getProjectId()).contains(operatorId);
        if (!admin && !projectOwner) {
            throw new ServiceException("只有系统管理员或本项目负责人可审批删除申请；"
                    + "请在「删除审批」页由系统管理员或项目负责人账号点击「批准」或「拒绝」",
                    BizErrorCode.FORBIDDEN);
        }
    }

    private void recordTransitionAudit(Long operatorId, DeleteRequestRow before,
                                       String action, int targetStatus) {
        Map<String, Object> beforeMap = new LinkedHashMap<>();
        beforeMap.put("id", before.getId());
        beforeMap.put("status", before.getStatus());
        beforeMap.put("statusLabel", DeleteRequestRow.statusLabel(before.getStatus()));
        Map<String, Object> afterMap = new LinkedHashMap<>();
        afterMap.put("status", targetStatus);
        afterMap.put("statusLabel", DeleteRequestRow.statusLabel(targetStatus));
        afterMap.put("approverId", operatorId);
        auditLogService.record(new AuditLogInput(operatorId, before.getProjectId(), OBJECT_TYPE,
                String.valueOf(before.getId()), action, beforeMap, afterMap));
    }

    private DeleteRequestRow findRow(long id) {
        return named.query(SELECT_COLUMNS + " WHERE id = :id",
                        new MapSqlParameterSource("id", id),
                        new BeanPropertyRowMapper<>(DeleteRequestRow.class))
                .stream().findFirst()
                .orElseThrow(() -> new ServiceException("删除申请不存在（id=" + id
                        + "），请到「删除审批」页点击「查询」刷新列表核对单号",
                        BizErrorCode.NOT_FOUND));
    }

    private static String validateReason(String raw, String label) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            throw new ServiceException(label + "不能为空（供审批人判断），"
                    + "请填写后再提交；正确写法：用一句话写明删除原因", BizErrorCode.VALIDATION_ERROR);
        }
        if (trimmed.length() > REASON_MAX) {
            throw new ServiceException(label + "最长 " + REASON_MAX + " 字（当前 "
                    + trimmed.length() + " 字），请精简后再提交；正确写法：控制在 " + REASON_MAX + " 字以内",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return trimmed;
    }
}
