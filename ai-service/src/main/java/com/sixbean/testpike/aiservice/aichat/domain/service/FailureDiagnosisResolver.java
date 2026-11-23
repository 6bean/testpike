package com.sixbean.testpike.aiservice.aichat.domain.service;

import com.sixbean.testpike.aiservice.aichat.domain.component.ExecutionLabels;
import com.sixbean.testpike.aiservice.aichat.domain.component.FailureDiagnosis;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.repair.infrastructure.remote.RepairTaskView;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.stereotype.Service;

/**
 * Fetch-and-verify of the failure-badge execution pointer (AO batch
 * knife 1): the execution id a badge deep link folded into the run message is
 * re-read through the core internal door, project-slice checked against the
 * session's project, and rendered as one diagnosis block for the system
 * prompt (error detail + the action script that failed + the AI failure class).
 * <p>Posture notes:
 * <ul>
 * <li>segment check is the H1 red line: a pointer at another project's task
 * would leak that project's error detail and script into this project's
 * conversation, so the run is refused with the id named (never silently
 * dropped — the user clicked THIS badge expecting THIS failure);</li>
 * <li>an unreadable pointer (vanished row, door down) degrades honestly:
 * the turn still runs with a state frame saying the diagnosis could not be
 * read, because "why did it fail" can still be answered from @-mention
 * chips or the user's own description, and blocking the whole dialog on a
 * stale badge would be worse than the fabrication the frame prevents;</li>
 * <li>the diagnosis is fetched at ask time, not baked into the session
 * context — a re-run of the same task answers with the fresh failure,
 * never the old one (A2 real-time posture);</li>
 * <li>both material fields are length-bounded: a stack dump or a huge
 * action JSON must not eat the prompt budget (the per-round guard seam
 * belongs to the AP batch, same note as {@link RefObjectResolver}).</li>
 * </ul>
 */
@Service
public class FailureDiagnosisResolver {

    /** Prompt-weight bound of the failure-detail excerpt. */
    private static final int ERROR_MAX_CHARS = 1_500;
    /** Prompt-weight bound of the failed action script. */
    private static final int SCRIPT_MAX_CHARS = 2_000;

    private final CoreGateway coreGateway;

    public FailureDiagnosisResolver(CoreGateway coreGateway) {
        this.coreGateway = coreGateway;
    }

    /**
     * Resolve one execution pointer; null id = {@link FailureDiagnosis#NONE}.
     */
    public FailureDiagnosis resolve(long sessionProjectId, Long executionId) {
        if (executionId == null) {
            return FailureDiagnosis.NONE;
        }
        RepairTaskView task;
        try {
            task = coreGateway.getRepairTaskView(executionId);
        } catch (ServiceException unreadable) {
            return FailureDiagnosis.degraded("本次提问关联的执行记录 #" + executionId
                    + " 读不到（" + unreadable.getMessage() + "），回答将基于问题本身。");
        } catch (RuntimeException doorDown) {
            return FailureDiagnosis.degraded("本次提问关联的执行记录 #" + executionId
                    + " 暂时读不到（核心服务未响应），回答将基于问题本身。");
        }
        if (task.projectId() != sessionProjectId) {
            return FailureDiagnosis.refused("提问关联的执行记录 #" + executionId
                    + " 不属于当前项目，跨项目诊断已拒绝。");
        }
        return FailureDiagnosis.resolved(executionId, task.caseId(), task.errorDetail(),
                blockOf(executionId, task));
    }

    private String blockOf(long executionId, RepairTaskView task) {
        StringBuilder block = new StringBuilder(512)
                .append("[E] 执行任务 #").append(executionId)
                .append("（用例 #").append(task.caseId())
                .append("，状态=").append(ExecutionLabels.statusLabel(task.status()));
        if (task.aiFailureClass() != null) {
            block.append("，AI归因=").append(ExecutionLabels.classLabel(task.aiFailureClass()));
        }
        block.append("）\n");
        if (task.errorDetail() != null && !task.errorDetail().isBlank()) {
            block.append("失败详情：").append(bound(task.errorDetail(), ERROR_MAX_CHARS)).append('\n');
        }
        if (task.actionJson() != null && !task.actionJson().isBlank()) {
            block.append("被执行的动作脚本：").append(bound(task.actionJson(), SCRIPT_MAX_CHARS));
        }
        return block.toString();
    }

    private static String bound(String raw, int max) {
        String trimmed = raw.strip();
        return trimmed.length() <= max ? trimmed
                : trimmed.substring(0, max) + "…（已截断）";
    }
}
