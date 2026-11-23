package com.sixbean.testpike.core.ai.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.core.ai.api.dto.AiTaskCreateInput;
import com.sixbean.testpike.core.ai.api.dto.AiTaskInfo;
import com.sixbean.testpike.core.cost.domain.component.CostBreakGuard;

import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * AI generation task service implementation (document 01 a tp_ai_task).
 * <p> State machine: 1=pending → 2=running → 3=done / 4=failed / 5=cancelled; failed AND cancelled can be
* retried/restarted back to pending (retry_cnt+1, errors cleared); done and live rows are settled-only no-ops.
 * Error classification MODEL_TIMEOUT/FORMAT_ERROR/SERVICE_ERROR (document 02 9.3 displays three failure reason categories distinctly).
 *
 */
@Service
public class AiTaskServiceImpl implements AiTaskService {

    private static final int STATUS_PENDING = 1;
    private static final int STATUS_RUNNING = 2;
    private static final int STATUS_DONE = 3;
    private static final int STATUS_FAILED = 4;
    private static final int STATUS_CANCELLED = 5;

    private static final String COLUMNS = """
            id, project_id, operator_id, task_type, status, requirement_text, requirement_id,
            case_id, script_id, error_code, error_msg, retry_cnt, model_name,
            to_char(created_at, 'YYYY-MM-DD HH24:MI:SS'), to_char(updated_at, 'YYYY-MM-DD HH24:MI:SS'),
            template_version_id
            """;

    /** Registered job kind of the script-generation pipeline inside
     *  tp_ai_task_event (the V460 closed set; mirror of the ai-service writer
     *  door — a re-triggered generation is a new task row, so the task id is
     *  both the business anchor and the run token of a fresh event face). */
    private static final String JOB_KIND_STEPS_GEN = "steps-gen";

    private final JdbcTemplate jdbcTemplate;
    private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate namedJdbcTemplate;
    private final CostBreakGuard costBreakGuard;
    private final com.sixbean.testpike.core.events.domain.component.TaskEventWindowReader
            taskEventWindowReader;

    public AiTaskServiceImpl(JdbcTemplate jdbcTemplate,
                             org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate namedJdbcTemplate,
                             CostBreakGuard costBreakGuard,
                             com.sixbean.testpike.core.events.domain.component
                                     .TaskEventWindowReader taskEventWindowReader) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbcTemplate = namedJdbcTemplate;
        this.costBreakGuard = costBreakGuard;
        this.taskEventWindowReader = taskEventWindowReader;
    }

    @Override
    @Transactional
    public long create(AiTaskCreateInput input) {
        if (input.projectId() <= 0) {
            throw new ServiceException("未选择项目（当前传入 projectId=" + input.projectId()
                    + "）：请先在顶栏项目切换器选中目标项目，再回到「AI 生成」页提交",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.taskType() != 1 && input.taskType() != 2) {
            throw new ServiceException("任务类型不支持（当前传入 taskType=" + input.taskType()
                    + "）：合法取值只有 1=用例生成、2=脚本生成；用例生成在「AI 生成」页提交，"
                    + "脚本生成请从用例详情页「生成脚本」发起",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.taskType() == 1) {
            if (input.requirementText() == null || input.requirementText().isBlank()) {
                throw new ServiceException("生成需求未填写：请先在「AI 生成」页需求输入框写好需求描述，"
                        + "再点「开始生成」（单条）或「批量生成用例」（批量）提交",
                        BizErrorCode.VALIDATION_ERROR);
            }
            if (input.requirementText().length() > 5000) {
                throw new ServiceException("生成需求过长（当前 " + input.requirementText().length()
                        + " 字，上限 5000 字）：请拆成多条分批提交，"
                        + "或到「需求提取」页上传文档提取需求点后再批量生成",
                        BizErrorCode.VALIDATION_ERROR);
            }
        } else if (input.caseId() == null) {
            throw new ServiceException("脚本生成任务缺少用例（未传入 caseId）："
                    + "请打开目标用例详情页，点击「生成脚本」发起",
                    BizErrorCode.VALIDATION_ERROR);
        }
 // E3 budget preflight check (contract 02): while the circuit breaker is active, reject start with 40002 and do not persist to database; each item in the batch loop passes through this single point.
        costBreakGuard.assertGenerationAllowed(input.projectId());
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO tp_ai_task (project_id, operator_id, task_type, status,
                                        requirement_text, requirement_id, case_id, batch_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, input.projectId(), input.operatorId(), input.taskType(),
                STATUS_PENDING, input.requirementText(), input.requirementId(), input.caseId(),
                input.batchId());
        return id;
    }

    @Override
    public AiTaskInfo get(long taskId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM tp_ai_task WHERE id = ?",
                this::mapRow, taskId).stream().findFirst().orElse(null);
    }

    /**
 * Latest single-line generation task of one project .
 * batch_id IS NULL keeps batch children out of this channel — the batch
 * panel reads them through AiBatchService, and one task never shows in two
 * panels (same-information-two-channels rule).
 */
    @Override
    public AiTaskInfo findLatestSingle(long projectId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM tp_ai_task "
                        + "WHERE project_id = ? AND task_type = 1 AND batch_id IS NULL "
                        + "ORDER BY id DESC LIMIT 1",
                this::mapRow, projectId).stream().findFirst().orElse(null);
    }

    /**
 * Live generation markers of requirement points :
 * queued/running case-generation rows joined by requirement_id, project
 * segment enforced in SQL. Latest row per requirement wins when a point
 * somehow carries two live tasks (retry overlap window) — the annotation
 * only needs one entry to point the queue panel at.
 */
    @Override
    public java.util.List<com.sixbean.testpike.core.ai.api.dto.AiRequirementLiveInfo>
            liveByRequirements(long projectId, java.util.List<Long> requirementIds) {
        if (requirementIds == null || requirementIds.isEmpty()) {
            return java.util.List.of();
        }
        // DISTINCT ON keeps one live row per requirement (newest id), ORDER BY
        // inside the windowed pick; the outer list order is by requirement id.
        return namedJdbcTemplate.query("""
                        SELECT DISTINCT ON (requirement_id) requirement_id, id, batch_id, status
                        FROM tp_ai_task
                        WHERE project_id = :projectId AND task_type = 1 AND status IN (1, 2)
                          AND requirement_id IN (:ids)
                        ORDER BY requirement_id, id DESC
                        """,
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                        .addValue("projectId", projectId)
                        .addValue("ids", requirementIds.stream().distinct().toList()),
                (rs, rowNum) -> new com.sixbean.testpike.core.ai.api.dto.AiRequirementLiveInfo(
                        rs.getLong("requirement_id"), rs.getLong("id"),
                        (Long) rs.getObject("batch_id"), rs.getInt("status")));
    }

    /**
 * Steps-gen tail window of one generation task . The
 * task id is both anchor and run token: the task row is created per
 * trigger, so the ledger face of a re-run starts empty by construction and
 * an old round's steps never replay. The window door is the shared
 * component (same cursor contract as the extraction tail).
 */
    @Override
    public java.util.List<com.sixbean.testpike.core.events.api.dto.TaskEventRow>
            processEvents(long taskId, long cursor, Integer limit) {
        return taskEventWindowReader.since(JOB_KIND_STEPS_GEN,
                String.valueOf(taskId), String.valueOf(taskId), cursor, limit);
    }

    @Override
    @Transactional
    public void markRunning(long taskId) {
        int updated = jdbcTemplate.update(
                "UPDATE tp_ai_task SET status = ?, updated_at = now() WHERE id = ? AND status = ?",
                STATUS_RUNNING, taskId, STATUS_PENDING);
        if (updated == 0) {
            AiTaskInfo task = get(taskId);
            if (task == null) {
                throw taskMissing(taskId);
            }
            if (task.status() != STATUS_RUNNING) {
                throw new ServiceException("任务 #" + taskId + " 当前为「" + statusLabel(task.status())
                        + "」，无法开始生成：仅「排队中」的任务可开始；已结束的任务请在「AI 生成」页右侧"
                        + "「生成任务进度」面板点「重新生成」重新提交",
                        BizErrorCode.STATE_MACHINE_REJECT);
            }
        }
    }

    @Override
    @Transactional
    public void markDone(long taskId, Long caseId) {
        int updated = jdbcTemplate.update(
                "UPDATE tp_ai_task SET status = ?, case_id = COALESCE(?, case_id), updated_at = now() "
                        + "WHERE id = ? AND status = ?",
                STATUS_DONE, caseId, taskId, STATUS_RUNNING);
        if (updated == 0) {
            AiTaskInfo task = get(taskId);
            if (task == null) {
                throw taskMissing(taskId);
            }
            if (task.status() == STATUS_DONE) {
                return; // Idempotent: duplicate done does not cause an error (MQ redelivery scenario).
            }
            throw new ServiceException("任务 #" + taskId + " 当前为「" + statusLabel(task.status())
                    + "」，无法回报完成：仅「生成中」的任务可完成；任务若已被停止，"
                    + "请在「AI 生成」页右侧「生成任务进度」面板点「重新生成」重新发起",
                    BizErrorCode.STATE_MACHINE_REJECT);
        }
    }

    @Override
    @Transactional
    public void markFailed(long taskId, String errorCode, String errorMsg) {
        if (errorCode == null || errorCode.isBlank()) {
            throw new ServiceException("失败原因分类未填写（当前传入 errorCode=" + errorCode
                    + "）：正确写法为三选一，请按实际失败类型回报 "
                    + "MODEL_TIMEOUT（模型超时）/ FORMAT_ERROR（输出格式不合）/ SERVICE_ERROR（服务异常）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (errorMsg == null || errorMsg.length() > 500) {
            throw new ServiceException("失败原因需填写且不超过 500 字（当前长度 "
                    + (errorMsg == null ? 0 : errorMsg.length()) + "）：请提炼关键报错摘要后回报，"
                    + "原始报错全文可在「AI 生成」页右侧「生成任务进度」面板的任务详情中查看",
                    BizErrorCode.VALIDATION_ERROR);
        }
        int updated = jdbcTemplate.update(
                "UPDATE tp_ai_task SET status = ?, error_code = ?, error_msg = ?, updated_at = now() "
                        + "WHERE id = ? AND status = ?",
                STATUS_FAILED, errorCode, errorMsg, taskId, STATUS_RUNNING);
        if (updated == 0) {
            AiTaskInfo task = get(taskId);
            if (task == null) {
                throw taskMissing(taskId);
            }
            if (task.status() == STATUS_FAILED) {
                return; // Idempotent: duplicate failed does not cause an error (MQ redelivery scenario).
            }
            throw new ServiceException("任务 #" + taskId + " 当前为「" + statusLabel(task.status())
                    + "」，无法回报失败：仅「生成中」的任务可置失败；如需重新生成，"
                    + "可在「AI 生成」页右侧「生成任务进度」面板点「重新生成」重新提交",
                    BizErrorCode.STATE_MACHINE_REJECT);
        }
    }

    @Override
    @Transactional
    public void retry(long taskId) {
 // Retry = new execution enqueued, also passing through the circuit breaker preflight check (rejection = task remains failed and does not return to pending).
 // Restartable set (user report 2026-11-20: a stopped queue item had no door back): a FAILED row (its run errored) OR a
 // CANCELLED row (a human pressed stop before it settled) both re-enqueue to pending. A DONE result is never
 // re-run and a live (pending/running) row is left untouched — the settled-only guard keeps those idempotent no-ops.
        AiTaskInfo target = get(taskId);
        if (target != null) {
            costBreakGuard.assertGenerationAllowed(target.projectId());
        }
        int updated = jdbcTemplate.update("""
                UPDATE tp_ai_task SET status = ?, retry_cnt = retry_cnt + 1,
                       error_code = NULL, error_msg = NULL, updated_at = now()
                WHERE id = ? AND status IN (?, ?)
                """, STATUS_PENDING, taskId, STATUS_FAILED, STATUS_CANCELLED);
        if (updated == 0) {
            AiTaskInfo task = get(taskId);
            if (task == null) {
                throw taskMissing(taskId);
            }
            // Idempotent semantics: retrying a non-retryable task (pending/running/done) has no effect and does not cause an error (the frontend restart button is visible only for failed/cancelled).
        }
    }

    @Override
    @Transactional
    public void cancelTask(long taskId) {
        // Stop door: only a live row (queued or running) may settle as
        // cancelled. An already settled row is never rewritten — the user gets
        // an honest rejection instead of a fake "stopped" history.
        int updated = jdbcTemplate.update(
                "UPDATE tp_ai_task SET status = ?, updated_at = now() "
                        + "WHERE id = ? AND status IN (?, ?)",
                STATUS_CANCELLED, taskId, STATUS_PENDING, STATUS_RUNNING);
        if (updated == 0) {
            AiTaskInfo task = get(taskId);
            if (task == null) {
                throw taskMissing(taskId);
            }
            if (task.status() == STATUS_CANCELLED) {
                return; // Idempotent: a repeated stop keeps the first result.
            }
            throw new ServiceException("任务 #" + taskId + " 已是终态「" + statusLabel(task.status())
                    + "」，无需停止：停止只适用于「排队中/生成中」的任务；已生成的结果不受影响，"
                    + "可在「AI 生成」页右侧「生成任务进度」面板查看",
                    BizErrorCode.STATE_MACHINE_REJECT);
        }
    }

    @Override
    @Transactional
    public void markModel(long taskId, String modelName) {
        if (modelName == null || modelName.isBlank()) {
            throw new ServiceException("模型标识未填写（modelName 传入为空）：模型名请取"
                    + "「大模型配置」页清单中该模型的「模型名称」字段字面值",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (modelName.length() > 100) {
            throw new ServiceException("模型标识过长（当前 " + modelName.length()
                    + " 字，上限 100 字）：请传「大模型配置」页「模型名称」字段的字面值，勿传描述文案",
                    BizErrorCode.VALIDATION_ERROR);
        }
        int updated = jdbcTemplate.update(
                "UPDATE tp_ai_task SET model_name = ?, updated_at = now() WHERE id = ?",
                modelName, taskId);
        if (updated == 0) {
            throw taskMissing(taskId);
        }
    }

    @Override
    @Transactional
    public void pinTemplate(long taskId, Long templateVersionId) {
 // D4/: pinning = overwrite a single column (retry round changes to pin a new row = bucket reselection result).
        // Task does not exist = 404 (same gate criteria as markModel).
        int updated = jdbcTemplate.update(
                "UPDATE tp_ai_task SET template_version_id = ?, updated_at = now() WHERE id = ?",
                templateVersionId, taskId);
        if (updated == 0) {
            throw taskMissing(taskId);
        }
    }

    /**
     * Shared missing-task rejection: names the task id and where to look for
     * live tasks, so the reader can recover without a second guess.
     */
    private static ServiceException taskMissing(long taskId) {
        return new ServiceException("AI 生成任务 #" + taskId + " 不存在（可能已被清理）："
                + "当前在跑与近期任务可在「AI 生成」页右侧「生成任务进度」面板查看",
                BizErrorCode.NOT_FOUND);
    }

    /** Plain-language task status label for user-visible messages (mirrors the on-screen status tags). */
    private static String statusLabel(int status) {
        return switch (status) {
            case STATUS_PENDING -> "排队中";
            case STATUS_RUNNING -> "生成中";
            case STATUS_DONE -> "已完成";
            case STATUS_FAILED -> "失败";
            case STATUS_CANCELLED -> "已停止";
            default -> "未知状态（" + status + "）";
        };
    }

    private AiTaskInfo mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new AiTaskInfo(
                rs.getLong("id"), rs.getLong("project_id"), rs.getLong("operator_id"),
                rs.getInt("task_type"), rs.getInt("status"),
                rs.getString("requirement_text"),
                (Long) rs.getObject("requirement_id"),
                (Long) rs.getObject("case_id"),
                (Long) rs.getObject("script_id"),
                rs.getString("error_code"), rs.getString("error_msg"),
                rs.getInt("retry_cnt"), rs.getString("model_name"),
                rs.getString(14), rs.getString(15),
                (Long) rs.getObject("template_version_id"));
    }
}
