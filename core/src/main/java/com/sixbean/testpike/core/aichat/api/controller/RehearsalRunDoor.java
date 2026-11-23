package com.sixbean.testpike.core.aichat.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.api.dto.RehearsalRunReceipt;
import com.sixbean.testpike.core.batch.api.dto.BatchStartResult;
import com.sixbean.testpike.core.batch.domain.service.BatchService;
import com.sixbean.testpike.core.plans.domain.component.RehearsalExecGate;
import com.sixbean.testpike.core.tool.domain.component.ToolAccessGate;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Internal door that starts a confirmed regression-rehearsal batch through the
 * dialog tool : the ai-service HTTP tool posts
 * {"batchId": N} and the server re-checks, in this exact order, the operator
 * identity, the batch existence, the project visibility segment and the
 * three-facet launch gate BEFORE the core start chain runs.
 * <p>Ordering is the whole contract: a gate refusal must land with zero batch
 * side effect, so the door never duplicates the start logic — the
 * draft-state-only rule and everything else stay the single source inside
 * {@link BatchService#start} (its 409 propagates untouched).
 * <p>Identity posture equals the analysis door: a missing or non-positive
 * X-Operator-Id is refused fail-closed before any statement, because the
 * visibility re-check is keyed on that identity. The batch id arrives in the
 * body only — the HTTP tool shape has no path templates, and the segment facts
 * are read from the ledger row, never trusted from the model.
 */
@RestController
@RequestMapping("/internal/ai/chat")
public class RehearsalRunDoor {

    private final JdbcTemplate jdbcTemplate;
    private final ToolAccessGate accessGate;
    private final RehearsalExecGate execGate;
    private final BatchService batchService;

    public RehearsalRunDoor(JdbcTemplate jdbcTemplate, ToolAccessGate accessGate,
                            RehearsalExecGate execGate, BatchService batchService) {
        this.jdbcTemplate = jdbcTemplate;
        this.accessGate = accessGate;
        this.execGate = execGate;
        this.batchService = batchService;
    }

    /** Start one draft batch after the four pre-checks; refusal = zero side effect. */
    @PostMapping("/rehearsal-runs")
    public Response<RehearsalRunReceipt> run(
            @RequestHeader(value = "X-Operator-Id", required = false) Long operatorId,
            @RequestBody(required = false) Map<String, Object> body) {
        requireKnownOperator(operatorId);
        long batchId = readBatchId(body);

        Map<String, Object> row;
        try {
            row = jdbcTemplate.queryForMap(
                    "SELECT project_id, name FROM tp_exec_batch WHERE id = ?", batchId);
        } catch (EmptyResultDataAccessException e) {
            // queryForMap on a missing id: the subclass covers the empty face;
            // a real connection failure propagates as a 500, never mislabeled
            // as a missing batch.
            throw new ServiceException("批次 " + batchId + " 不存在或已被删除，未启动任何执行——"
                    + "请使用草案确认回执中给出的批次编号", BizErrorCode.VALIDATION_ERROR);
        }
        long projectId = ((Number) row.get("project_id")).longValue();

        accessGate.requireProjectVisible(projectId, operatorId);
        List<String> declarations = execGate.requireAllowed(projectId);
        BatchStartResult started = batchService.start(operatorId, batchId);
        return Response.builderSuccess(RehearsalRunReceipt.of(started, declarations));
    }

    /** Fail-closed identity rule of the door (same posture as the analysis door). */
    private static void requireKnownOperator(Long operatorId) {
        if (operatorId == null || operatorId <= 0) {
            throw new ServiceException("演练启动缺少操作人身份（X-Operator-Id），已拒绝执行——"
                    + "启动执行批次必须由登录用户的对话发起", BizErrorCode.FORBIDDEN);
        }
    }

    /** The batch id is a server-read ledger key: positive integer or refusal. */
    private static long readBatchId(Map<String, Object> body) {
        Object raw = body == null ? null : body.get("batchId");
        long batchId = raw instanceof Number number ? number.longValue() : -1L;
        if (batchId <= 0) {
            throw new ServiceException("演练启动请求缺少批次编号 batchId，已拒绝执行——"
                    + "批次编号来自草案确认回执", BizErrorCode.VALIDATION_ERROR);
        }
        return batchId;
    }
}
