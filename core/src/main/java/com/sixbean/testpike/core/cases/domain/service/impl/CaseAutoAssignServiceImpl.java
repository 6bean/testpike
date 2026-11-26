package com.sixbean.testpike.core.cases.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.cases.api.dto.AutoAssignAssigneeRow;
import com.sixbean.testpike.core.cases.api.dto.AutoAssignRequest;
import com.sixbean.testpike.core.cases.api.dto.AutoAssignResultView;
import com.sixbean.testpike.core.cases.domain.component.AutoAssignCandidate;
import com.sixbean.testpike.core.cases.domain.component.AutoAssignSupport;
import com.sixbean.testpike.core.cases.domain.component.AutoAssignTxWriter;
import com.sixbean.testpike.core.cases.domain.service.CaseAutoAssignService;
import com.sixbean.testpike.core.events.api.dto.CaseEventMessage;
import com.sixbean.testpike.core.events.api.dto.CaseEventType;
import com.sixbean.testpike.core.events.domain.service.CaseEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Service
public class CaseAutoAssignServiceImpl implements CaseAutoAssignService {

    
    static final int CHUNK_SIZE = 500;
    
    private static final String UNASSIGNED_REVIEW_SQL =
            "tc.deleted = 0 AND tc.status IN (1, 2) AND tc.assignee_id IS NULL";

    private final AutoAssignSupport autoAssignSupport;
    private final AutoAssignTxWriter txWriter;
    private final CaseEventPublisher caseEventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public CaseAutoAssignServiceImpl(AutoAssignSupport autoAssignSupport,
                                     AutoAssignTxWriter txWriter,
                                     CaseEventPublisher caseEventPublisher,
                                     TransactionTemplate transactionTemplate,
                                     org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.autoAssignSupport = autoAssignSupport;
        this.txWriter = txWriter;
        this.caseEventPublisher = caseEventPublisher;
        this.transactionTemplate = transactionTemplate;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public AutoAssignResultView autoAssign(Long operatorId, AutoAssignRequest request) {
        long projectId = request.projectId();
        requireProject(projectId);
        List<AutoAssignCandidate> candidates = autoAssignSupport.candidates(projectId);
        if (candidates.isEmpty()) {
            throw new ServiceException("项目内暂无可承接的测试成员——请先在「项目管理」→「成员管理」添加成员后重试", BizErrorCode.VALIDATION_ERROR);
        }
        List<Map<String, Object>> scope = resolveScope(projectId, request.caseIds());
        if (scope.isEmpty()) {
            return new AutoAssignResultView(0, List.of());
        }
        List<AutoAssignCandidate> plan = autoAssignSupport.dispatch(candidates, scope.size());
        if (request.preview()) {
            return summarize(plan, scope.size());
        }
        List<long[]> pairs = new ArrayList<>(scope.size());
        for (int i = 0; i < scope.size(); i++) {
            pairs.add(new long[]{(Long) scope.get(i).get("id"), plan.get(i).userId()});
        }
        List<Long> winners = assignInChunks(pairs);
        if (winners.isEmpty()) {
            return new AutoAssignResultView(0, List.of());
        }
        publishAssignedEvent(operatorId, projectId, winners);
        return summarizeWinners(pairs, winners);
    }

    
    private List<Map<String, Object>> resolveScope(long projectId, List<Long> caseIds) {
        if (caseIds == null || caseIds.isEmpty()) {
            return jdbcTemplate.queryForList(
                    "SELECT tc.id FROM tp_test_case tc"
                            + " WHERE tc.project_id = ? AND " + UNASSIGNED_REVIEW_SQL
                            + " ORDER BY tc.priority, tc.created_at, tc.id", projectId);
        }
        List<Long> ids = caseIds.stream().distinct().toList();
        String placeholders = String.join(",", ids.stream().map(x -> "?").toList());
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, project_id, status, assignee_id, priority, created_at FROM tp_test_case"
                        + " WHERE id IN (" + placeholders + ")", ids.toArray());
        if (rows.size() != ids.size()) {
            throw new ServiceException("存在不存在的用例——请在「用例管理」列表刷新后重新勾选在列用例", BizErrorCode.VALIDATION_ERROR);
        }
        for (Map<String, Object> row : rows) {
            if (((Number) row.get("project_id")).longValue() != projectId) {
                throw new ServiceException("用例不属于该项目（跨项目禁止）——请在顶栏切换到用例所属项目后重试（项目入口见「项目管理」）", BizErrorCode.VALIDATION_ERROR);
            }
            int status = ((Number) row.get("status")).intValue();
            if (status != 1 && status != 2) {
                throw new ServiceException("用例 %s 状态不允许分配：仅待评审/评审中用例可分配——请在「用例管理」将用例退回评审状态后重试"
                        .formatted(row.get("id")), BizErrorCode.VALIDATION_ERROR);
            }
        }
        rows.sort(Comparator
                .comparingInt((Map<String, Object> r) -> ((Number) r.get("priority")).intValue())
                .thenComparing(r -> (java.util.Date) r.get("created_at"))
                .thenComparing(r -> ((Number) r.get("id")).longValue()));
        return rows;
    }

    
    private List<Long> assignInChunks(List<long[]> pairs) {
        List<Long> winners = new ArrayList<>(pairs.size());
        for (int from = 0; from < pairs.size(); from += CHUNK_SIZE) {
            List<long[]> chunk = pairs.subList(from, Math.min(from + CHUNK_SIZE, pairs.size()));
            winners.addAll(transactionTemplate.execute(status -> txWriter.assignChunk(chunk)));
        }
        return winners;
    }

    
    private AutoAssignResultView summarizeWinners(List<long[]> pairs, List<Long> winners) {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (long[] pair : pairs) {
            if (winners.contains(pair[0])) {
                counts.merge(pair[1], 1, Integer::sum);
            }
        }
        return toView(counts, winners.size());
    }

    
    private AutoAssignResultView summarize(List<AutoAssignCandidate> plan, int total) {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (AutoAssignCandidate c : plan) {
            counts.merge(c.userId(), 1, Integer::sum);
        }
        return toView(counts, total);
    }

    private AutoAssignResultView toView(Map<Long, Integer> counts, int assignedCount) {
        List<AutoAssignAssigneeRow> rows = new ArrayList<>(counts.size());
        Map<Long, String> names = new LinkedHashMap<>();
        for (Map.Entry<Long, Integer> e : counts.entrySet()) {
            rows.add(new AutoAssignAssigneeRow(e.getKey(), null, e.getValue()));
        }
        if (!rows.isEmpty()) {
            List<Long> userIds = rows.stream().map(AutoAssignAssigneeRow::userId).toList();
            String placeholders = String.join(",", userIds.stream().map(x -> "?").toList());
            jdbcTemplate.queryForList(
                    "SELECT id, COALESCE(display_name, username) AS name FROM tp_user WHERE id IN ("
                            + placeholders + ")", userIds.toArray())
                    .forEach(r -> names.put(((Number) r.get("id")).longValue(), (String) r.get("name")));
            rows.replaceAll(r -> new AutoAssignAssigneeRow(r.userId(), names.get(r.userId()), r.count()));
        }
        rows.sort(Comparator.comparingInt(AutoAssignAssigneeRow::count).reversed()
                .thenComparingLong(AutoAssignAssigneeRow::userId));
        return new AutoAssignResultView(assignedCount, List.copyOf(rows));
    }

    
    private void publishAssignedEvent(Long operatorId, long projectId, List<Long> caseIds) {
        caseEventPublisher.publish(new CaseEventMessage(
                CaseEventType.BATCH_ASSIGNED.name(), null, projectId, operatorId,
                "BATCH_ASSIGN", null, null, caseIds, caseIds.size(),
                System.currentTimeMillis(), null));
    }

    private void requireProject(long projectId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_project WHERE id = ?", Integer.class, projectId);
        if (count == null || count == 0) {
            throw new ServiceException("项目不存在或已被删除——可在「项目管理」页确认项目在列后重试", BizErrorCode.NOT_FOUND);
        }
    }
}
