package com.sixbean.testpike.core.perf.domain.service.impl;

import com.sixbean.testpike.common.approval.ApprovalEvent;
import com.sixbean.testpike.common.approval.ApprovalStateMachine;
import com.sixbean.testpike.common.approval.ApprovalStatus;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.perf.api.dto.PerfApprovalInput;
import com.sixbean.testpike.core.perf.domain.component.PerfAccessGate;
import com.sixbean.testpike.core.perf.domain.component.PerfConfigReader;
import com.sixbean.testpike.core.perf.domain.service.PerfApprovalService;
import com.sixbean.testpike.core.perf.domain.service.PerfRunService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * <p>D8 auto-launch: when the hot-updatable switch is on and the ticket links a scenario,
 * the approve transition fires PerfRunService.launch — the single launch channel shared by
 * manual/CI/scheduled doors (gate enforcement lives inside launch, zero bypass here).
 * The approval is autocommitted before the launch attempt: a gate-refused launch never
 * rolls back a granted approval, instead the plain reason is written to auto_launch_note
 * (silent failure forbidden in both directions — success names the run id, refusal names
 * the gate reason).
 */
@Service
public class PerfApprovalServiceImpl implements PerfApprovalService {

    private static final String ENTITY_LABEL = "压测申请";
    /** Outcome note column is VARCHAR(500); gate reasons can be long — cap the tail
     *  so a never-truncated note never fails the write itself. */
    private static final int NOTE_MAX = 480;

    private final NamedParameterJdbcTemplate jdbc;
    private final PerfRunService perfRunService;
    private final PerfConfigReader perfConfigReader;

    public PerfApprovalServiceImpl(NamedParameterJdbcTemplate jdbc,
                                   PerfRunService perfRunService,
                                   PerfConfigReader perfConfigReader) {
        this.jdbc = jdbc;
        this.perfRunService = perfRunService;
        this.perfConfigReader = perfConfigReader;
    }

    @Override
    @Audit(objectType = "perf_approval", action = "apply", objectId = "#result")
    public long apply(long projectId, long applicantId, PerfApprovalInput input) {
        if (input.envId() == null) {
            throw new ServiceException("请选择压测目标环境；请在「压测工作台」场景向导选择环境，没有可选项时先到项目详情「环境配置」新建环境", BizErrorCode.VALIDATION_ERROR);
        }
        if (input.requestedConcurrency() == null || input.requestedConcurrency() <= 0) {
            throw new ServiceException("申请压力档位（RPS）必须大于 0；请在「压测工作台」场景向导的超档审批申请里改为正整数（示例：50）", BizErrorCode.VALIDATION_ERROR);
        }
        if (input.isolationMode() == null || !PerfAccessGate.ISOLATION_MODES.contains(input.isolationMode())) {
            throw new ServiceException("隔离模式必选且限三型（" + String.join("/", PerfAccessGate.ISOLATION_MODES)
                    + "）；请在「压测工作台」场景向导的「隔离模式」中三选一后重新提交", BizErrorCode.VALIDATION_ERROR);
        }
        if (input.reason() == null || input.reason().isBlank()) {
            throw new ServiceException("申请理由必填（写清验收窗口/目标系统），便于审批人判断；请填写后在「压测工作台」场景向导的超档审批申请重新提交",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Long envOwn = jdbc.queryForObject("SELECT count(*) FROM tp_project_env WHERE id = :e AND project_id = :p",
                new MapSqlParameterSource("e", input.envId()).addValue("p", projectId), Long.class);
        if (envOwn == null || envOwn == 0) {
            throw new ServiceException("目标环境不存在或不属于该项目；请在项目详情「环境配置」确认该环境仍在本项目下，或改选其它环境后重新提交", BizErrorCode.NOT_FOUND);
        }
        // D8 link is validated at the write point (fail-closed): an unverifiable ticket must
        // never reach the approve door, where the auto-launch would hit a missing/foreign
        // scenario. Missing and foreign share one wording (existence never leaks).
        if (input.scenarioId() != null) {
            Long scOwn = jdbc.queryForObject(
                    "SELECT count(*) FROM tp_perf_scenario WHERE id = :s AND project_id = :p",
                    new MapSqlParameterSource("s", input.scenarioId()).addValue("p", projectId), Long.class);
            if (scOwn == null || scOwn == 0) {
                throw new ServiceException("关联的压测场景不存在或不属于该项目；请在「压测工作台」重新选择本项目的场景，或先在本项目新建场景后再发起申请", BizErrorCode.NOT_FOUND);
            }
        }
        return jdbc.queryForObject("INSERT INTO tp_perf_approval "
                        + "(project_id, env_id, requested_concurrency, isolation_mode, reason, applicant_id, "
                        + "scenario_id) "
                        + "VALUES (:p, :e, :c, :m, :r, :a, :sc) RETURNING id",
                new MapSqlParameterSource("p", projectId).addValue("e", input.envId())
                        .addValue("c", input.requestedConcurrency()).addValue("m", input.isolationMode())
                        .addValue("r", input.reason().trim()).addValue("a", applicantId)
                        .addValue("sc", input.scenarioId()),
                Long.class);
    }

    @Override
    @Audit(objectType = "perf_approval", action = "approve", objectId = "#id")
    public void approve(long id, long approverId) {
        Map<String, Object> ticket = requireTicket(id);
        int current = ((Number) ticket.get("status")).intValue();
        if (current == ApprovalStatus.PUBLISHED.code()) {
            return; // idempotent short-circuit: a repeat approve never re-stamps the
                    // effective time nor re-fires the D8 auto-launch (one launch per ticket)
        }
        int next = ApprovalStateMachine.transition(current, ApprovalEvent.APPROVE, ENTITY_LABEL);
        jdbc.update("UPDATE tp_perf_approval SET status = :s, approver_id = :a, handled_at = now(), "
                        + "reject_reason = NULL, updated_at = now() WHERE id = :id AND status = :cur",
                new MapSqlParameterSource("s", next).addValue("a", approverId).addValue("id", id)
                        .addValue("cur", current));
        // D8: the approval is already committed (this method deliberately holds no
        // transaction — launch runs in its own, so a gate refusal there can never roll
        // back a granted approval). Fire through the single launch channel only when
        // both the hot-updatable switch and the ticket's scenario link are present.
        Object scenarioId = ticket.get("scenario_id");
        if (scenarioId == null || !perfConfigReader.approvalAutoLaunchEnabled()) {
            return;
        }
        long linkedScenario = ((Number) scenarioId).longValue();
        long projectId = ((Number) ticket.get("project_id")).longValue();
        String note;
        try {
            long runId = perfRunService.launch(projectId, linkedScenario, approverId);
            note = "已自动发起运行 #" + runId;
        } catch (ServiceException refused) {
            note = "未自动发起：" + refused.getMessage();
        } catch (RuntimeException unexpected) {
            note = "未自动发起：发起异常 " + unexpected.getClass().getSimpleName();
        }
        jdbc.update("UPDATE tp_perf_approval SET auto_launch_note = :n, updated_at = now() WHERE id = :id",
                new MapSqlParameterSource("n", note.length() > NOTE_MAX ? note.substring(0, NOTE_MAX) : note)
                        .addValue("id", id));
    }

    @Override
    @Audit(objectType = "perf_approval", action = "reject", objectId = "#id")
    public void reject(long id, long approverId, String rejectReason) {
        if (rejectReason == null || rejectReason.isBlank()) {
            throw new ServiceException("拒绝必须填写理由（让申请人知道差什么），已保持不变；请在该审批单的「拒绝」操作中填入理由后再次提交",
                    BizErrorCode.VALIDATION_ERROR);
        }
        int current = ((Number) requireTicket(id).get("status")).intValue();
        int next = ApprovalStateMachine.transition(current, ApprovalEvent.REJECT, ENTITY_LABEL);
        jdbc.update("UPDATE tp_perf_approval SET status = :s, approver_id = :a, handled_at = now(), "
                        + "reject_reason = :rr, updated_at = now() WHERE id = :id AND status = :cur",
                new MapSqlParameterSource("s", next).addValue("a", approverId).addValue("rr", rejectReason.trim())
                        .addValue("id", id).addValue("cur", current));
    }

    @Override
    public List<Map<String, Object>> list(long projectId) {
        return jdbc.queryForList("SELECT id, env_id, requested_concurrency, isolation_mode, reason, "
                + "reject_reason, status, handled_at, created_at, scenario_id, auto_launch_note "
                + "FROM tp_perf_approval "
                + "WHERE project_id = :p ORDER BY id DESC", new MapSqlParameterSource("p", projectId));
    }

    /** Full ticket row (status + D8 link legs) read once per approve/reject transition —
     *  the single existence guard: missing ticket = NOT_FOUND in plain words. */
    private Map<String, Object> requireTicket(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, project_id, status, scenario_id FROM tp_perf_approval WHERE id = :id",
                new MapSqlParameterSource("id", id));
        if (rows.isEmpty()) {
            throw new ServiceException("审批单不存在；请刷新「压测工作台」场景向导的审批列表后重新选择，列表没有该行=已被删除", BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }
}
