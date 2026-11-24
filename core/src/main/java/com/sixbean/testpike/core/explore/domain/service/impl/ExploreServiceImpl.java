package com.sixbean.testpike.core.explore.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.clientobject.ExploreRunStatsUpdate;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.common.domain.component.PageQueryPolicy;
import com.sixbean.testpike.core.common.domain.component.SortColumnPolicy;
import com.sixbean.testpike.core.config.domain.component.SystemConfigReader;
import com.sixbean.testpike.core.cost.domain.component.CostBreakGuard;
import com.sixbean.testpike.common.storage.StorageGrant;
import com.sixbean.testpike.core.explore.api.dto.ExploreCancelResult;
import com.sixbean.testpike.core.explore.api.dto.ExploreCreateInput;
import com.sixbean.testpike.core.explore.api.dto.ExploreEstimate;
import com.sixbean.testpike.core.explore.api.dto.ExploreEvidenceGrantView;
import com.sixbean.testpike.core.explore.api.dto.ExploreSessionView;
import com.sixbean.testpike.core.explore.api.dto.ExploreStepInput;
import com.sixbean.testpike.core.explore.api.dto.ExploreStepView;
import com.sixbean.testpike.core.webelement.domain.component.ExploreAnchorReconcile;
import com.sixbean.testpike.core.webelement.domain.component.ExploreReanchorProposal;
import com.sixbean.testpike.core.webelement.domain.component.ExploreVerifiedAnchorUpsert;
import com.sixbean.testpike.core.explore.domain.component.AppautoExploreAccessGate;
import com.sixbean.testpike.core.explore.domain.component.AppautoExploreEnvelopeEnricher;
import com.sixbean.testpike.core.explore.domain.service.ExploreService;
import com.sixbean.testpike.core.executor.domain.component.ArtifactStorage;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import com.sixbean.testpike.core.mcp.domain.component.McpEndpointPolicy;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.webelement.domain.component.SurveyEnvelopeTiers;
import com.sixbean.testpike.core.webelement.domain.component.PageWriteProfileLedger;
import com.sixbean.testpike.core.webelement.domain.component.SiteSurveyRunLedger;
import com.sixbean.testpike.core.webelement.domain.component.WebElementSurveyCollector;
import com.sixbean.testpike.core.webelement.domain.component.NamingOutcome;
import com.sixbean.testpike.core.webelement.domain.component.WebElementSurveyNaming;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


@Service
public class ExploreServiceImpl implements ExploreService {

 
    static final String KEY_MAX_STEPS = "ai.explore.max-steps";
    static final String KEY_MAX_PAGES = "ai.explore.max-pages";
    static final String KEY_BUDGET_CONFIRM = "ai.explore.budget-confirm";
    static final String KEY_SNAPSHOT_STORE = "ai.explore.snapshot-store";
    static final String KEY_APPAUTO_MAX_STEPS = "ai.explore.appauto.max-steps";
    static final String KEY_APPAUTO_MAX_TOKENS = "ai.explore.appauto.max-tokens";
    static final String KEY_APPAUTO_MAX_DURATION = "ai.explore.appauto.max-duration-seconds";

    
    static final int NO_PROGRESS_LIMIT = 3;

    
    private static final Set<Integer> VALID_STATUSES = Set.of(
            ExploreSessionView.STATUS_RUNNING, ExploreSessionView.STATUS_DONE,
            ExploreSessionView.STATUS_BUDGET_STOP, ExploreSessionView.STATUS_PARTIAL);

    private static final RowMapper<ExploreSessionView> SESSION_ROW =
            new BeanPropertyRowMapper<>(ExploreSessionView.class);

    private static final RowMapper<ExploreStepView> STEP_ROW =
            new BeanPropertyRowMapper<>(ExploreStepView.class);

    
    private static final int MAX_OFFSET = 100_000;

 
    private static final int TO_CASES_MAX = 50;

    private final NamedParameterJdbcTemplate named;
    private final SystemConfigReader configReader;
    private final ProjectService projectService;
    private final McpEndpointPolicy endpointPolicy;
    private final ArtifactStorage artifactStorage;
    private final CostBreakGuard costBreakGuard;
    private final ExecTaskService execTaskService;
    private final CaseService caseService;
    private final AppautoExploreAccessGate appautoAccessGate;
    private final AppautoExploreEnvelopeEnricher envelopeEnricher;
    private final ExploreAnchorReconcile anchorReconcile;
    private final ExploreVerifiedAnchorUpsert verifiedAnchorUpsert;
    private final ExploreReanchorProposal reanchorProposal;
    private final WebElementSurveyCollector surveyCollector;
    private final SurveyEnvelopeTiers surveyEnvelopeTiers;
    private final WebElementSurveyNaming surveyNaming;
    private final SiteSurveyRunLedger siteSurveyRunLedger;
    private final PageWriteProfileLedger pageWriteProfileLedger;
    // Terminal reclaim of the survey process-event face (R62 segment a pairing:
    // the survey door creates the stream, session deletion purges it).
    private final com.sixbean.testpike.core.events.domain.component.AiTaskEventTrimSweeper eventTrimSweeper;
    // R62 segment b: the survey process stream door (same TaskEventWriter the
    // trigger endpoint writes through — no second INSERT path).
    private final com.sixbean.testpike.core.events.domain.component.TaskEventWriter taskEvents;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ExploreServiceImpl.class);

 
    private static final int GOAL_MAX_CHARS = 2000;
    private static final double ESTIMATE_TOKENS_PER_STEP = 1200;
    private static final double ESTIMATE_TOKENS_PER_CHAR = 2.0;
    private static final long ESTIMATE_PRICE_CENTS_PER_1K_TOKENS = 1L;

    public ExploreServiceImpl(NamedParameterJdbcTemplate named,
                              SystemConfigReader configReader,
                              ProjectService projectService,
                              McpEndpointPolicy endpointPolicy,
                              ArtifactStorage artifactStorage,
                              CostBreakGuard costBreakGuard,
                              ExecTaskService execTaskService,
                              CaseService caseService,
                              AppautoExploreAccessGate appautoAccessGate,
                              AppautoExploreEnvelopeEnricher envelopeEnricher,
                              ExploreAnchorReconcile anchorReconcile,
                              ExploreVerifiedAnchorUpsert verifiedAnchorUpsert,
                              ExploreReanchorProposal reanchorProposal,
                              WebElementSurveyCollector surveyCollector,
                              SurveyEnvelopeTiers surveyEnvelopeTiers,
                              WebElementSurveyNaming surveyNaming,
                              SiteSurveyRunLedger siteSurveyRunLedger,
                              PageWriteProfileLedger pageWriteProfileLedger,
                              com.sixbean.testpike.core.events.domain.component.AiTaskEventTrimSweeper eventTrimSweeper,
                              com.sixbean.testpike.core.events.domain.component.TaskEventWriter taskEvents) {
        this.named = named;
        this.configReader = configReader;
        this.projectService = projectService;
        this.endpointPolicy = endpointPolicy;
        this.artifactStorage = artifactStorage;
        this.costBreakGuard = costBreakGuard;
        this.execTaskService = execTaskService;
        this.caseService = caseService;
        this.appautoAccessGate = appautoAccessGate;
        this.envelopeEnricher = envelopeEnricher;
        this.anchorReconcile = anchorReconcile;
        this.verifiedAnchorUpsert = verifiedAnchorUpsert;
        this.reanchorProposal = reanchorProposal;
        this.surveyCollector = surveyCollector;
        this.surveyEnvelopeTiers = surveyEnvelopeTiers;
        this.surveyNaming = surveyNaming;
        this.siteSurveyRunLedger = siteSurveyRunLedger;
        this.pageWriteProfileLedger = pageWriteProfileLedger;
        this.eventTrimSweeper = eventTrimSweeper;
        this.taskEvents = taskEvents;
    }

    @Override
    public long create(long operatorId, long projectId, ExploreCreateInput input) {
        costBreakGuard.assertGenerationAllowed(projectId);
        validateInput(projectId, input);
        boolean mobile = AppautoExploreAccessGate.isAppStartUrl(input.startUrl());
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("projectId", projectId)
                .addValue("goalText", input.goalText().strip())
                .addValue("startUrl", input.startUrl().strip())
                .addValue("snapshot", budgetSnapshotJson(mobile))
                .addValue("operatorId", operatorId)
                .addValue("envId", mobile ? input.envId() : null)
                // cred_ref carries the vault credential key for the mobile
                // {{CRED:}} placeholder face only; the web login-state handle
                // rides its own column — the two namespaces never merge.
                .addValue("credRef", input.credRef() != null
                        && !input.credRef().isBlank() ? input.credRef().strip() : null)
                .addValue("loginStateName", input.loginStateName() != null
                        && !input.loginStateName().isBlank() ? input.loginStateName().strip() : null);
        Long id = named.queryForObject("""
                INSERT INTO tp_explore_session (project_id, goal_text, start_url, status,
                                                budget_snapshot, created_by, env_id, cred_ref,
                                                login_state_name)
                VALUES (:projectId, :goalText, :startUrl, 1,
                        CAST(:snapshot AS JSONB), :operatorId, :envId, :credRef, :loginStateName)
                RETURNING id
                """, params, Long.class);
        return id == null ? 0L : id;
    }

    @Override
    public ExploreEstimate estimate(long projectId, ExploreCreateInput input) {
        validateInput(projectId, input);
        int maxSteps = configReader.getInt(KEY_MAX_STEPS, 30);
        long tokens = Math.round(maxSteps * ESTIMATE_TOKENS_PER_STEP
                + input.goalText().strip().length() * ESTIMATE_TOKENS_PER_CHAR);
        long cents = (long) Math.ceil(tokens / 1000.0 * ESTIMATE_PRICE_CENTS_PER_1K_TOKENS);
        return new ExploreEstimate(tokens, cents, maxSteps);
    }

    @Override
    public ExploreSessionView get(long sessionId) {
        ExploreSessionView view = loadSession(sessionId);
        view.setBudgetSnapshot(readSnapshotText(sessionId));
        return view;
    }

 
    @Override
    public PageResult<ExploreSessionView> list(long projectId, int page, int size) {
        return list(projectId, page, size, null, null);
    }

    /**
     * Columns the session list may order by — physical tp_explore_session
     * columns only; values are never user input (the whitelist map output is
     * the sole thing that reaches the ORDER BY clause).
     */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "id", "id",
            "goal_text", "goal_text",
            "start_url", "start_url",
            "status", "status",
            "steps_used", "steps_used",
            "cost_cents", "cost_cents");

    /**
     * ORDER BY builder for the session window: a null/blank key keeps the
     * historical id DESC default; a whitelisted key orders by that column with
     * a constant id DESC tie-break so paging stays deterministic under equal
     * values; an off-list key is refused fail-closed by the shared gate.
     */
    private String orderClause(String sortBy, String sortDir) {
        String column = SortColumnPolicy.require(sortBy, SORT_COLUMNS, "探索会话");
        if (column == null) {
            return " ORDER BY id DESC";
        }
        return " ORDER BY " + column
                + ("asc".equalsIgnoreCase(sortDir) ? " ASC" : " DESC")
                + ", id DESC";
    }

    @Override
    public PageResult<ExploreSessionView> list(long projectId, int page, int size,
                                               String sortBy, String sortDir) {
        int safePage = Math.max(page, 1);
        int safeSize = PageQueryPolicy.enforceSize(size);
        long offset = PageQueryPolicy.clampOffset(safePage, safeSize, MAX_OFFSET);
        Long total = named.queryForObject(
                "SELECT count(*) FROM tp_explore_session WHERE project_id = :projectId",
                new MapSqlParameterSource("projectId", projectId), Long.class);
        List<ExploreSessionView> rows = named.query("""
                SELECT id, project_id, goal_text, start_url, status, steps_used,
                       pages_visited, cost_cents, report_md, created_by
                  FROM tp_explore_session
                 WHERE project_id = :projectId
                """ + orderClause(sortBy, sortDir) + """
                 LIMIT :limit OFFSET :offset
                """, new MapSqlParameterSource()
                .addValue("projectId", projectId)
                .addValue("limit", safeSize)
                .addValue("offset", offset), SESSION_ROW);
        return new PageResult<>(rows, total == null ? 0L : total);
    }

    
    @Override
    public List<ExploreStepView> steps(long sessionId) {
        loadSession(sessionId);
        return named.query("""
                SELECT seq, step_action ::text AS step_action, step_result ::text AS step_result,
                       screenshot_key, dom_snapshot_key, llm_think_md
                  FROM tp_explore_step
                 WHERE session_id = :sessionId
                 ORDER BY seq ASC
                """, new MapSqlParameterSource("sessionId", sessionId), STEP_ROW);
    }

    
    @Override
    public String screenshotUrl(long sessionId, int seq) {
        loadSession(sessionId);
        List<String> keys = named.query("""
                SELECT screenshot_key FROM tp_explore_step
                 WHERE session_id = :sessionId AND seq = :seq
                """, new MapSqlParameterSource("sessionId", sessionId).addValue("seq", seq),
                (rs, n) -> rs.getString(1));
        String key = keys.stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
        if (key == null || key.isBlank()) {
            throw new ServiceException("步骤 " + seq + " 暂无截图产物：该步执行时未产生截图。可在「探索详情」页按「跑通」列核对这一步，仅对显示「跑通」的步骤点击「取证」查看",
                    BizErrorCode.NOT_FOUND);
        }
        return artifactStorage.grantViewUrl(key);
    }


    
    private void validateInput(long projectId, ExploreCreateInput input) {
        if (!projectService.exists(projectId)) {
            throw new ServiceException("项目不存在（项目 id=" + projectId
                    + "）：请到「项目管理」页确认项目未被删除，重新选择项目后再点击「新建探索」",
                    BizErrorCode.NOT_FOUND);
        }
        if (input == null || input.goalText() == null || input.goalText().isBlank()) {
            throw new ServiceException("探索目标不能为空：请在「新建自主探索」弹窗的「探索目标」输入框填写要探索的流程，再点击「确认发起」",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.goalText().strip().length() > GOAL_MAX_CHARS) {
            throw new ServiceException("探索目标不能超过 " + GOAL_MAX_CHARS + " 字，实际="
                    + input.goalText().strip().length() + " 字：请在「探索目标」输入框精简描述后再提交",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.startUrl() == null || input.startUrl().isBlank()) {
            throw new ServiceException("起始 URL 不能为空：请在「新建自主探索」弹窗填写完整网址（正确写法：https:// 开头的页面地址）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (AppautoExploreAccessGate.isAppStartUrl(input.startUrl())) {
            appautoAccessGate.assertSessionAllowed(projectId, input.startUrl(), input.envId());
            return;
        }
        endpointPolicy.validate(input.startUrl(), "起始 URL", "探索 ");
    }

    @Override
    @Transactional
    public List<Long> toCases(Long operatorId, long sessionId, List<Integer> seqs) {
        ExploreSessionView session = loadSession(sessionId);
        if (seqs == null || seqs.isEmpty()) {
            throw new ServiceException("勾选步骤不能为空：请先在「探索详情」页的「步骤时间线」勾选要转正的步骤，再点击「转正式用例」",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (seqs.size() > TO_CASES_MAX) {
            throw new ServiceException("勾选步骤过多（" + seqs.size() + " 步，单次最多 " + TO_CASES_MAX
                    + " 步），请在「步骤时间线」分批勾选，再逐批点击「转正式用例」", BizErrorCode.VALIDATION_ERROR);
        }
        List<Integer> unique = new ArrayList<>(new LinkedHashSet<>(seqs));
        Map<Integer, ExploreStepView> bySeq = new LinkedHashMap<>();
        for (ExploreStepView step : stepsOf(sessionId)) {
            bySeq.put(step.getSeq(), step);
        }
        List<Integer> missing = unique.stream().filter(s -> !bySeq.containsKey(s)).toList();
        if (!missing.isEmpty()) {
            throw new ServiceException("步骤 " + joinSeqs(missing) + " 在该会话中不存在，无法转用例：请刷新「探索详情」页，按「步骤时间线」里实际存在的步序重新勾选",
                    BizErrorCode.VALIDATION_ERROR);
        }
        List<Integer> notPassed = unique.stream()
                .filter(s -> !stepPassed(bySeq.get(s).getStepResult())).toList();
        if (!notPassed.isEmpty()) {
            throw new ServiceException("步骤 " + joinSeqs(notPassed)
                    + " 未真跑通（无执行成功证据），无法转用例：请在「步骤时间线」的「跑通」列确认状态，只勾选显示「跑通」的步骤",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String sourceRef = "explore:" + sessionId;
        String precondition = "自主探索会话 #" + sessionId + "（起始 " + session.getStartUrl() + "）";
        List<Long> caseIds = new ArrayList<>();
        for (Integer seq : unique) {
            ExploreStepView step = bySeq.get(seq);
            String action = actionNameOf(step.getStepAction());
            String observation = observationOf(step.getStepResult());
            String stepDesc = trim("第 " + seq + " 步 [" + action + "]：" + observation, 480);
            CaseCreateInput input = new CaseCreateInput(
                    session.getProjectId() == null ? 0L : session.getProjectId(),
                    trim("探索转用例：" + session.getGoalText(), 300),
                    precondition,
                    trim("该步执行成功（观测）：" + observation, 2000),
                    null, null, null,
                    1, null, 5, sourceRef,
                    List.of(new CaseStepInput(1, stepDesc, "assert".equals(action) ? 1 : 0)));
            caseIds.add(caseService.create(operatorId, input).id());
        }
        return caseIds;
    }

    @Override
    @Transactional
    public ExploreCancelResult cancel(long sessionId) {
        ExploreSessionView session = loadSession(sessionId);
        if (session.getStatus() != null && session.getStatus() == ExploreSessionView.STATUS_RUNNING) {
            named.update("UPDATE tp_explore_session SET status = 5, updated_at = now() WHERE id = :id",
                    new MapSqlParameterSource("id", sessionId));
            appendSurveyStoppedFrame(session);
            // : a soft-cancelled site session closes its run as cancelled.
            siteSurveyRunLedger.finishBySession(sessionId, SiteSurveyRunLedger.STATUS_CANCELLED);
            return new ExploreCancelResult(sessionId, ExploreCancelResult.ACTION_CANCELLED);
        }
        named.update("DELETE FROM tp_explore_step WHERE session_id = :id",
                new MapSqlParameterSource("id", sessionId));
        named.update("DELETE FROM tp_explore_session WHERE id = :id",
                new MapSqlParameterSource("id", sessionId));
        // R62 segment a: the survey process stream (kind=web-element-survey,
        // ref=session id) is reclaimed with its owning row; the per-element
        // audit stream (element-ledger) is addressed by element row id and
        // must never be purged through this door.
        eventTrimSweeper.purgeJobEvents(
                com.sixbean.testpike.core.events.domain.component.AiTaskEventTrimSweeper
                        .KIND_WEB_ELEMENT_SURVEY, String.valueOf(sessionId));
        // : whole-site sessions journal under the dedicated site kind —
        // the reclaim pairing covers that stream too (R62/R57 create-delete).
        eventTrimSweeper.purgeJobEvents(
                com.sixbean.testpike.core.events.domain.component.AiTaskEventTrimSweeper
                        .KIND_WEB_ELEMENT_SURVEY_SITE, String.valueOf(sessionId));
        // R62 A-face: the plain exploration loop writes the same session id
        // under kind=explore, so the reclaim pairing covers that stream too.
        eventTrimSweeper.purgeJobEvents(
                com.sixbean.testpike.core.events.domain.component.AiTaskEventTrimSweeper
                        .KIND_EXPLORE, String.valueOf(sessionId));
        return new ExploreCancelResult(sessionId, ExploreCancelResult.ACTION_DELETED);
    }

    
    private List<ExploreStepView> stepsOf(long sessionId) {
        return named.query("""
                SELECT seq, step_action ::text AS step_action, step_result ::text AS step_result,
                       screenshot_key, dom_snapshot_key, llm_think_md
                  FROM tp_explore_step
                 WHERE session_id = :sessionId
                 ORDER BY seq ASC
                """, new MapSqlParameterSource("sessionId", sessionId), STEP_ROW);
    }

    
    private boolean stepPassed(String stepResultJson) {
        JsonNode node = readJsonOrNull(stepResultJson);
        return node != null && node.path("ok").isBoolean() && node.path("ok").asBoolean();
    }

    
    private String observationOf(String stepResultJson) {
        JsonNode node = readJsonOrNull(stepResultJson);
        String obs = node == null ? null : node.path("observation").asText(null);
        return obs == null || obs.isBlank() ? "（无观测记录）" : obs;
    }

    
    private String actionNameOf(String stepActionJson) {
        JsonNode node = readJsonOrNull(stepActionJson);
        String name = node == null ? null : node.path("name").asText(null);
        return name == null || name.isBlank() ? "step" : name;
    }

    private JsonNode readJsonOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private String joinSeqs(List<Integer> seqs) {
        return seqs.stream().map(String::valueOf).reduce((a, b) -> a + "、" + b).orElse("");
    }

    /** On-screen status label for user-visible messages (mirrors the list status tags). */
    private static String statusLabel(Integer status) {
        if (status == null) {
            return "未知状态";
        }
        return switch (status) {
            case ExploreSessionView.STATUS_RUNNING -> "进行中";
            case ExploreSessionView.STATUS_DONE -> "已完成";
            case ExploreSessionView.STATUS_BUDGET_STOP -> "达到上限自动停止";
            case ExploreSessionView.STATUS_PARTIAL -> "部分完成";
            case ExploreSessionView.STATUS_CANCELLED -> "已取消";
            default -> "状态码" + status;
        };
    }

    private String trim(String text, int max) {
        if (text == null) {
            return null;
        }
        String t = text.strip();
        return t.length() <= max ? t : t.substring(0, max);
    }

    @Override
    public ExploreSessionView updateStatus(long sessionId, int status) {
        if (!VALID_STATUSES.contains(status)) {
            throw new ServiceException("探索会话状态只能是 1/2/3/4（1=进行中、2=已完成、3=达到上限自动停止、4=部分完成），实际="
                    + status + "：如需结束会话请在「探索详情」页点击「终止探索」，不要直接改状态值",
                    BizErrorCode.VALIDATION_ERROR);
        }
        ExploreSessionView current = loadSession(sessionId);
        if (current.isTerminal()) {
            throw new ServiceException("会话已是终态（" + statusLabel(current.getStatus())
                    + "），状态不可再变更：如需继续探索请在「智能探索」页点击「新建探索」另开会话",
                    BizErrorCode.STATE_MACHINE_REJECT);
        }
        named.update("UPDATE tp_explore_session SET status = :status, updated_at = now() WHERE id = :id",
                new MapSqlParameterSource("status", status).addValue("id", sessionId));
        ExploreSessionView fresh = get(sessionId);
        // R62 segment b: terminal frame on the survey stream (reason + counts).
        appendSurveyTerminalFrame(current, status, fresh);
        // : the run ledger closes with the session (no-op without a row).
        siteSurveyRunLedger.finishBySession(sessionId, status);
        return fresh;
    }

    @Override
    public ExploreSessionView updateStats(long sessionId, ExploreRunStatsUpdate stats) {
        ExploreSessionView current = loadSession(sessionId);
        if (current.isTerminal()) {
            throw new ServiceException("会话已是终态（" + statusLabel(current.getStatus())
                    + "），统计无法回写：请在执行侧停止对会话 #" + sessionId + " 提交统计，最终数据可在「探索详情」页查看",
                    BizErrorCode.STATE_MACHINE_REJECT);
        }
        named.update("""
                UPDATE tp_explore_session
                   SET pages_visited = COALESCE(:pagesVisited, pages_visited),
                       cost_cents = COALESCE(:costCents, cost_cents),
                       report_md = COALESCE(:reportMd, report_md),
                       updated_at = now()
                 WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("pagesVisited", stats == null ? null : stats.pagesVisited())
                .addValue("costCents", stats == null ? null : stats.costCents())
                .addValue("reportMd", stats == null ? null : stats.reportMd())
                .addValue("id", sessionId));
        return get(sessionId);
    }

    @Override
    public void recordStep(long sessionId, ExploreStepInput input) {
        ExploreSessionView session = loadSession(sessionId);
        if (session.isTerminal()) {
            throw new ServiceException("会话已是终态（" + statusLabel(session.getStatus())
                    + "），步进数据无法回写：请停止向会话 #" + sessionId + " 发送步骤，如需新一轮探索请点击「新建探索」",
                    BizErrorCode.STATE_MACHINE_REJECT);
        }
        if (input == null || input.seq() < 1) {
            throw new ServiceException("步序必须 ≥1，实际=" + (input == null ? "null" : input.seq())
                    + "（正确写法：从 1 开始的连续正整数）：请按「步骤时间线」当前最大步序加 1 回写",
                    BizErrorCode.VALIDATION_ERROR);
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("sessionId", sessionId)
                .addValue("seq", input.seq())
                .addValue("action", jsonOrNull(input.stepAction(), "stepAction"))
                .addValue("result", jsonOrNull(input.stepResult(), "stepResult"))
                .addValue("screenshotKey", input.screenshotKey())
                .addValue("domSnapshotKey", input.domSnapshotKey())
                .addValue("think", input.llmThinkMd());
        try {
            named.update("""
                    INSERT INTO tp_explore_step (session_id, seq, step_action, step_result,
                                                 screenshot_key, dom_snapshot_key, llm_think_md)
                    VALUES (:sessionId, :seq, CAST(:action AS JSONB), CAST(:result AS JSONB),
                            :screenshotKey, :domSnapshotKey, :think)
                    """, params);
        } catch (DuplicateKeyException dup) {
            throw new ServiceException("步序 " + input.seq() + " 已存在（同一步骤重复回写，未再写入）：请改用「步骤时间线」当前最大步序之后的编号回写",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
        named.update("""
                UPDATE tp_explore_session
                   SET steps_used = steps_used + 1, updated_at = now()
                 WHERE id = :id
                """, new MapSqlParameterSource("id", sessionId));
        reconcileCandidatePool(session, input);
        syncVerifiedAnchor(session, input);
        List<WebElementSurveyCollector.ItemOutcome> surveyOutcomes = syncSurveyCandidates(session, input);
        appendSurveyStepFrame(session, input, surveyOutcomes);
        // journal relay: a site session's page write-back also tallies the
        // run ledger (only collector actions that really wrote a row count;
        // the ledger itself no-ops for frames it doesn't own).
        if (WebElementSurveyCollector.isSiteSurveyGoal(session.getGoalText())) {
            int ledgerWrites = 0;
            for (WebElementSurveyCollector.ItemOutcome outcome : surveyOutcomes) {
                if (outcome.action() == WebElementSurveyCollector.Action.CREATED
                        || outcome.action() == WebElementSurveyCollector.Action.ANCHORS_EXTENDED) {
                    ledgerWrites++;
                }
            }
            siteSurveyRunLedger.notePageFrame(session, input.stepAction(), ledgerWrites);
            // the same frame also books the per-page write-risk
            // ledger (autosave pages / failing endpoints), keyed by project so
            // the fact outlives the run the kernel prompt reads it from.
            pageWriteProfileLedger.notePageFrame(session.getProjectId(), input.stepAction());
        }
    }

    /** True when this session owns the web-element-survey process stream. */
    private static boolean isSurveyStream(ExploreSessionView session) {
        return session != null && session.getProjectId() != null
                && WebElementSurveyCollector.isSurveyGoal(session.getGoalText());
    }

    /**
 * Process-stream kind of a survey session : whole-site
 * sessions ride the dedicated site kind, single-page sessions keep the
 * original one — one tail per session, never a mixed stream (mixed-use
 * negative is pinned by the relay contract test).
 */
    private static String surveyStreamKind(ExploreSessionView session) {
        return WebElementSurveyCollector.isSiteSurveyGoal(session.getGoalText())
                ? com.sixbean.testpike.core.events.domain.component
                        .AiTaskEventTrimSweeper.KIND_WEB_ELEMENT_SURVEY_SITE
                : com.sixbean.testpike.core.events.domain.component
                        .AiTaskEventTrimSweeper.KIND_WEB_ELEMENT_SURVEY;
    }

    /**
 * R62 segment b: one step-summary frame per executor write-back on the
 * survey stream (seq / page / candidate count / ledger deltas), so the
 * tail drawer shows live progress. Bookkeeping never fails the step it
 * observes: any write outage degrades to a WARN (same posture as the
 * ai-service exploration stream door).
 */
    private void appendSurveyStepFrame(ExploreSessionView session, ExploreStepInput input,
                                       List<WebElementSurveyCollector.ItemOutcome> outcomes) {
        if (!isSurveyStream(session)) {
            return;
        }
        try {
            String page = candidatePoolPageUrl(input.stepAction());
            int poolCount = poolItemCount(input.candidatePoolJson());
            String ingest;
            if (input.candidatePoolJson() == null || input.candidatePoolJson().isBlank()) {
                ingest = "本步未采集到元素";
            } else if (outcomes.isEmpty()) {
                ingest = "本步无变化";
            } else {
                // Outcome counts read through the enum's business label: the tail
                // drawer shows what happened to the elements, not a constant name.
                Map<String, Integer> byLabel = new LinkedHashMap<>();
                for (WebElementSurveyCollector.ItemOutcome outcome : outcomes) {
                    byLabel.merge(outcome.action().screenLabel(), 1, Integer::sum);
                }
                StringBuilder note = new StringBuilder();
                byLabel.forEach((label, count) -> note
                        .append(note.isEmpty() ? "" : "｜").append(label).append(' ').append(count));
                ingest = note.toString();
            }
            String counter = surveyPageCounter(session.getId());
            taskEvents.step(surveyStreamKind(session),
                    String.valueOf(session.getId()), String.valueOf(session.getId()),
                    "步骤 " + input.seq() + counter + "｜页面 "
                            + (page == null ? session.getStartUrl() : page)
                            + "｜候选 " + poolCount + "｜" + ingest,
                    null);
        } catch (Exception bookkeepingFailure) {
            log.warn("测绘步骤帧写入失败（不影响步进事实）sessionId={} seq={}: {}",
                    session.getId(), input.seq(), bookkeepingFailure.getMessage());
        }
    }

    /**
     * Page progress for one survey step frame. The crawl's page cap comes
     * from the dispatch snapshot, so the on-screen row can say which page of
     * at most how many is being worked on. Returns an empty string when the
     * cap is unreadable — the step fact still books, just without a
     * denominator (never fails the write-back over a display detail).
     */
    private String surveyPageCounter(long sessionId) {
        try {
            ExploreSessionView now = loadSession(sessionId);
            JsonNode snapshot = objectMapper.readTree(readSnapshotText(sessionId));
            int cap = snapshot.path("maxPages").asInt(0);
            if (cap <= 0) {
                return "";
            }
            Integer visitedBox = now.getPagesVisited();
            int visited = visitedBox == null || visitedBox < 1 ? 1 : Math.min(visitedBox, cap);
            return "｜第 " + visited + "/" + cap + " 页";
        } catch (Exception unreadable) {
            return "";
        }
    }

    /**
 * R62 segment b terminal frame: reason per end state + ledger counts of
 * the candidates this session registered (anchor evidence note carries
 * sessionId; state 0=candidate 1=confirmed per the ledger value domain).
 */
    private void appendSurveyTerminalFrame(ExploreSessionView session, int status,
                                           ExploreSessionView fresh) {
        if (!isSurveyStream(session)) {
            return;
        }
        String reason = switch (status) {
            case ExploreSessionView.STATUS_DONE -> "测绘完成";
            case ExploreSessionView.STATUS_BUDGET_STOP -> "达到采集步数上限自动暂停（非失败）";
            case ExploreSessionView.STATUS_PARTIAL -> "同页连续无进展提前结束";
            default -> null;
        };
        if (reason == null) {
            return;
        }
        try {
            taskEvents.step(surveyStreamKind(session),
                    String.valueOf(session.getId()), String.valueOf(session.getId()),
                    reason + "｜" + surveyLedgerCounts(session)
                            + "｜步 " + fresh.getStepsUsed() + " 页 " + fresh.getPagesVisited(),
                    null);
        } catch (Exception bookkeepingFailure) {
            log.warn("测绘终态帧写入失败 sessionId={}: {}",
                    session.getId(), bookkeepingFailure.getMessage());
        }
    }

    /** Soft-stop frame on the survey stream (operator cancel, running→5). */
    private void appendSurveyStoppedFrame(ExploreSessionView session) {
        if (!isSurveyStream(session)) {
            return;
        }
        try {
            taskEvents.step(surveyStreamKind(session),
                    String.valueOf(session.getId()), String.valueOf(session.getId()),
                    "测绘会话已停止（操作人取消）｜" + surveyLedgerCounts(session),
                    null);
        } catch (Exception bookkeepingFailure) {
            log.warn("测绘停止帧写入失败 sessionId={}: {}",
                    session.getId(), bookkeepingFailure.getMessage());
        }
    }

    /** Ledger counts this session registered (anchor evidence note sessionId). */
    private String surveyLedgerCounts(ExploreSessionView session) {
        Map<String, Object> row = named.queryForMap("""
                SELECT count(*) FILTER (WHERE state = 0) AS cand,
                       count(*) FILTER (WHERE state = 1) AS conf
                  FROM tp_web_element
                 WHERE project_id = :pid
                   AND (anchors::text LIKE :mid OR anchors::text LIKE :tail)
                """, new MapSqlParameterSource()
                .addValue("pid", session.getProjectId())
                .addValue("mid", "% sessionId=" + session.getId() + " %")
                .addValue("tail", "% sessionId=" + session.getId()));
        return "累计候选 " + ((Number) row.get("cand")).longValue()
                + " 条·已确认 " + ((Number) row.get("conf")).longValue() + " 条";
    }

    /** Candidate items carried by a pool payload (tiers included); malformed = 0. */
    private int poolItemCount(String poolJson) {
        if (poolJson == null || poolJson.isBlank()) {
            return 0;
        }
        try {
            JsonNode root = objectMapper.readTree(poolJson);
            int total = root.path("candidates").size();
            for (JsonNode tier : root.path("variants")) {
                total += tier.path("candidates").size();
            }
            return total;
        } catch (Exception malformed) {
            return 0;
        }
    }

    /**
 * WE-S3 (W2) wiring: a step whose action SUCCEEDED with a
 * ledger-dialect locator in its target has just execution-proved that
 * locator; sync it into the page element ledger through
 * ExploreVerifiedAnchorUpsert's single-source doors (promote candidate /
 * reopen stale / create-then-confirm / never resurrect retired). Mirrors
 * reconcileCandidatePool's failure posture: the journal row above already
 * succeeded, so business refusals (a concurrent heal racing the same
 * revision) are logged and skipped, never bounced back into the AI loop's
 * duplicate-seq replay hole.
 */
    private void syncVerifiedAnchor(ExploreSessionView session, ExploreStepInput input) {
        if (session.getProjectId() == null) {
            return;
        }
        ExploreVerifiedAnchorUpsert.StepVerification step =
                ExploreVerifiedAnchorUpsert.forStep(input.stepAction(), input.stepResult());
        if (step == null) {
            return;
        }
        try {
            List<String> outcomes = new ArrayList<>();
            for (ExploreVerifiedAnchorUpsert.Verified verified : step.verified()) {
                ExploreVerifiedAnchorUpsert.Result r = verifiedAnchorUpsert.upsert(
                        session.getProjectId(), step.pageUrl(), verified, step.label());
                outcomes.add(verified.type() + "=" + r.action());
            }
            log.info("探索验证定位同步 sessionId={} seq={} page={} outcomes={}",
                    session.getId(), input.seq(), step.pageUrl(), outcomes);
        } catch (ServiceException business) {
            log.warn("探索验证定位同步被业务闸拒绝（并发修复环竞争等），本步不重试 sessionId={} code={}",
                    session.getId(), business.getCode());
        }
    }

    /**
 * WE-S4 segment P2b relay: steps of a session whose goal carries the survey
 * marker journal the full interactive candidate pool, and that pool is
 * exactly a survey payload — ingest it into the page ledger through
 * WebElementSurveyCollector's single-source upsert doors (candidate
 * create/merge only; confirmed/stale states untouched, retired never
 * resurrected; same-pool replay writes nothing). Ordinary explore
 * sessions keep the previous behaviour untouched. Failure posture mirrors
 * syncVerifiedAnchor: the step journal row already committed, so business
 * refusals (a concurrent heal racing the same revision) are logged and
 * skipped, never bounced back into the AI loop's duplicate-seq replay hole.
 */
    private List<WebElementSurveyCollector.ItemOutcome> syncSurveyCandidates(
            ExploreSessionView session, ExploreStepInput input) {
        if (session.getProjectId() == null
                || !WebElementSurveyCollector.isSurveyGoal(session.getGoalText())) {
            return List.of();
        }
        if (input.candidatePoolJson() == null || input.candidatePoolJson().isBlank()) {
            return List.of();
        }
        String pageUrl = candidatePoolPageUrl(input.stepAction());
        if (pageUrl == null) {
            log.warn("测绘候选入账缺页面锚（stepAction 无 arguments.url），跳过 sessionId={} seq={}",
                    session.getId(), input.seq());
            return List.of();
        }
        try {
            // WE segment P3: AI names land on the pool before ingestion; any
            // naming trouble returns the original pool (honest degrade).
            // R62 segment b2: the session id rides along so the naming face
            // reports one batch frame onto this survey's own tail.
            // Whole-site relay face: a naming outage over nameable
            // candidates tallies one degraded page on the run ledger.
            NamingOutcome naming = surveyNaming.namePoolOutcome(
                    session.getProjectId(), pageUrl, session.getId(), input.candidatePoolJson());
            String namedPoolJson = naming.pool();
            if (naming.degraded()
                    && WebElementSurveyCollector.isSiteSurveyGoal(session.getGoalText())) {
                siteSurveyRunLedger.noteNamingDegraded(session.getId());
            }
            List<WebElementSurveyCollector.ItemOutcome> outcomes = surveyCollector.survey(
                    session.getProjectId(), pageUrl, namedPoolJson,
                    new WebElementSurveyCollector.Evidence(session.getId(),
                            input.screenshotKey(), input.domSnapshotKey()));
            log.info("测绘候选入账 sessionId={} seq={} page={} items={}",
                    session.getId(), input.seq(), pageUrl, outcomes.size());
            return outcomes;
        } catch (ServiceException business) {
            log.warn("测绘候选入账被业务闸拒绝（并发修复环竞争等），本步不重试 sessionId={} code={}",
                    session.getId(), business.getCode());
            return List.of();
        }
    }

    /**
 * WE-S3 wiring: a step that journals the page's interactive
 * candidate pool doubles as the free surveyor — every confirmed ledger
 * entry of that page is reconciled against the pool (all-miss anchors
 * flip confirmed→stale through ExploreAnchorReconcile's single-source
 * doors). Detection failures never cost the step fact: the journaling
 * insert above already succeeded, so business rejections (a concurrent
 * heal racing the same revision) are logged and skipped instead of
 * bouncing the AI loop's write-back into the duplicate-seq replay hole.
 */
    private void reconcileCandidatePool(ExploreSessionView session, ExploreStepInput input) {
        if (input.candidatePoolJson() == null || input.candidatePoolJson().isBlank()
                || session.getProjectId() == null) {
            return;
        }
        String pageUrl = candidatePoolPageUrl(input.stepAction());
        if (pageUrl == null) {
            log.warn("候选池回写缺页面锚（stepAction 无 arguments.url），跳过察觉 sessionId={} seq={}",
                    session.getId(), input.seq());
            return;
        }
        try {
            ExploreAnchorReconcile.Result result = anchorReconcile.reconcile(
                    session.getProjectId(), pageUrl, input.candidatePoolJson());
            log.info("探索候选池察觉 sessionId={} seq={} checked={} matched={} mismatched={} "
                            + "notJudgable={} staleMarked={}",
                    session.getId(), input.seq(), result.checked(), result.matched().size(),
                    result.mismatched().size(), result.notJudgable().size(), result.staleMarked());
            fileReanchorTickets(session, input, result);
        } catch (ServiceException business) {
            log.warn("探索候选池察觉被业务闸拒绝（并发修复环竞争等），本步不重试 sessionId={} code={}",
                    session.getId(), business.getCode());
        }
    }

    /**
 * WE-S3 wiring: the mismatch side does not stop at marking —
 * each all-miss row gains an element repair suggestion (WEB shape) filed
 * through the A9 record door, whose human-approval chain revives the
 * entry with the pool-derived anchor. The act task journaled with the
 * step is the ownership anchor; without it (or without an evidenced
 * proposal) nothing is filed — honest zero-ticket instead of a guess.
 */
    private void fileReanchorTickets(ExploreSessionView session, ExploreStepInput input,
                                     ExploreAnchorReconcile.Result result) {
        if (result.mismatched().isEmpty()) {
            return;
        }
        long execTaskId = ExploreReanchorProposal.readExecTaskId(input.stepResult());
        if (execTaskId <= 0) {
            log.warn("失配集未建单：步进结果无探索任务锚（归属反查缺锚），sessionId={} seq={} mismatched={}",
                    session.getId(), input.seq(), result.mismatched().size());
            return;
        }
        ExploreReanchorProposal.Outcome tickets = reanchorProposal.file(
                session.getProjectId(), input.seq(), execTaskId,
                result.mismatched(), input.candidatePoolJson());
        log.info("探索失配重锚建议 sessionId={} seq={} mismatched={} filed={} skipped={}",
                session.getId(), input.seq(), result.mismatched().size(),
                tickets.filed(), tickets.skipped());
    }

    /** arguments.url recorded with the step action; malformed action JSON simply has no url. */
    private String candidatePoolPageUrl(String stepActionJson) {
        try {
            String url = objectMapper.readTree(stepActionJson)
                    .path("arguments").path("url").asText(null);
            return url == null || url.isBlank() ? null : url;
        } catch (Exception malformed) {
            return null;
        }
    }

    
    @Override
    public ExploreEvidenceGrantView grantEvidence(long sessionId, String kind, String fileName) {
        ExploreSessionView session = loadSession(sessionId);
        StorageGrant grant = artifactStorage.grantExploreUpload(
                session.getProjectId() == null ? 0L : session.getProjectId(), kind, fileName);
        return new ExploreEvidenceGrantView(sessionId, grant.key(), grant.url());
    }

    
    @Override
    public long submitAct(long sessionId, String actionJson) {
        ExploreSessionView session = loadSession(sessionId);
        if (session.isTerminal()) {
            throw new ServiceException("会话已是终态（" + statusLabel(session.getStatus())
                    + "），探索动作无法提交：请在「智能探索」页点击「新建探索」开启新会话后再提交动作",
                    BizErrorCode.STATE_MACHINE_REJECT);
        }
        appautoAccessGate.assertActAllowed(actionJson);
        long projectId = session.getProjectId() == null ? 0L : session.getProjectId();
        String enriched = envelopeEnricher.enrich(
                projectId, actionJson, session.getCredRef(), sessionId);
        // WE-S4 P2c: survey sessions carry the declared viewport tiers onto the
        // candidates envelope here (additive one field, everything else passes
        // through byte-identical — zero drift on the shared explore-act channel).
        // WE-S4 P2d: the session login-state handle rides the same additive
        // discipline as a top-level sessionState{name} declaration — read from
        // its own column, never from the credential reference namespace.
        return execTaskService.createExploreAct(projectId, attachSessionStateDeclaration(
                surveyEnvelopeTiers.attach(enriched, session.getGoalText()),
                session.getLoginStateName()));
    }

    /**
     * Attach the session login-state handle onto a web explore envelope as the
     * top-level sessionState{name} selector consumed by the shared claim
     * materialisation (SessionStateDeclarationSupport is the single read point —
     * this method mints no second translation, red line 6). Blank handle,
     * mobile envelopes (whose credentials ride the {{CRED:}} placeholder face),
     * an existing kernel declaration and malformed JSON all return the input
     * byte-identical (shape legality stays guarded write-side at createExploreAct).
     * The credential reference (cred_key namespace) never flows in here: the
     * claim side resolves names against tp_login_state.state_name only.
     */
    private String attachSessionStateDeclaration(String actionJson, String loginStateHandle) {
        if (loginStateHandle == null || loginStateHandle.isBlank()) {
            return actionJson;
        }
        try {
            if (!(objectMapper.readTree(actionJson)
                    instanceof com.fasterxml.jackson.databind.node.ObjectNode envelope)
                    || com.sixbean.testpike.common.util.ExploreEnvelopeContract.isMobileEnvelope(
                            envelope.path("kind").asText(""),
                            envelope.path("target").asText(""))
                    || envelope.path("sessionState").isObject()) {
                return actionJson;
            }
            envelope.putObject("sessionState").put("name", loginStateHandle.strip());
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception malformedOrSerialization) {
            return actionJson;
        }
    }

    
    @Override
    public long submitStandaloneAct(long projectId, String actionJson) {
        return execTaskService.createExploreAct(projectId, actionJson);
    }


    private ExploreSessionView loadSession(long sessionId) {
        ExploreSessionView view = named.query("""
                SELECT id, project_id, goal_text, start_url, status, steps_used,
                       pages_visited, cost_cents, report_md, created_by, env_id, cred_ref,
                       login_state_name
                  FROM tp_explore_session WHERE id = :id
                """, new MapSqlParameterSource("id", sessionId), SESSION_ROW)
                .stream().findFirst().orElse(null);
        if (view == null) {
            throw new ServiceException("探索会话 #" + sessionId + " 不存在（可能已被删除）：请回到「智能探索」列表页重新选择会话，或点击「新建探索」重建",
                    BizErrorCode.NOT_FOUND);
        }
        return view;
    }

    private String readSnapshotText(long sessionId) {
        return named.queryForObject(
                "SELECT budget_snapshot::text FROM tp_explore_session WHERE id = :id",
                new MapSqlParameterSource("id", sessionId), String.class);
    }

 
    private String budgetSnapshotJson(boolean mobile) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("maxSteps", configReader.getInt(KEY_MAX_STEPS, 30));
        snapshot.put("maxPages", configReader.getInt(KEY_MAX_PAGES, 10));
        snapshot.put("budgetConfirm", configReader.getBoolean(KEY_BUDGET_CONFIRM, true));
        snapshot.put("store", configReader.getString(KEY_SNAPSHOT_STORE, "explore"));
        snapshot.put("noProgressLimit", NO_PROGRESS_LIMIT);
        if (mobile) {
            Map<String, Object> appauto = new LinkedHashMap<>();
            appauto.put("maxSteps", configReader.getInt(KEY_APPAUTO_MAX_STEPS, 60));
            appauto.put("maxTokens", configReader.getInt(KEY_APPAUTO_MAX_TOKENS, 1_000_000));
            appauto.put("maxDurationSeconds",
                    configReader.getInt(KEY_APPAUTO_MAX_DURATION, 3600));
            snapshot.put("appauto", appauto);
        }
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new IllegalStateException("预算快照序列化失败", e);
        }
    }

    
    private String jsonOrNull(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new ServiceException(field + " 不是合法 JSON，无法解析（本次回写未生效）：请改成合法 JSON 后重新提交，正确写法：形如 {\"ok\":true}",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return raw;
    }
}
