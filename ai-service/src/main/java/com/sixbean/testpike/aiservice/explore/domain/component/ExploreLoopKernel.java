package com.sixbean.testpike.aiservice.explore.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.explore.infrastructure.remote.ExploreEvidenceGateway;
import com.sixbean.testpike.aiservice.explore.infrastructure.remote.ExecTaskResultRemote;
import com.sixbean.testpike.aiservice.explore.infrastructure.remote.ExploreSessionBrief;
import com.sixbean.testpike.aiservice.explore.infrastructure.remote.ExploreStepRecord;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatToolDef;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.LlmToolCall;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.AiCallLogWriter;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.llm.pipeline.BehaviorKernelAssembly;
import com.sixbean.testpike.aiservice.tool.domain.FcPlan;
import com.sixbean.testpike.common.webelement.PageWriteRiskView;
import com.sixbean.testpike.aiservice.tool.domain.component.LlmFunctionCalling;
import com.sixbean.testpike.aiservice.tool.domain.component.ToolCallOutcome;
import com.sixbean.testpike.aiservice.tool.domain.component.ToolDispatcher;
import com.sixbean.testpike.common.clientobject.ExploreRunStatsUpdate;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.ExploreEnvelopeContract;
import com.sixbean.testpike.common.util.UiActionContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


@Component
public class ExploreLoopKernel {

    private static final Logger log = LoggerFactory.getLogger(ExploreLoopKernel.class);

    
    static final String GOAL_MARKER = "GOAL_REACHED";

    
    static final Set<String> BUILTIN_ACTIONS =
            Set.of("page-snapshot", "click-path", "input-path", "assert",
                    "element-candidates");

    /** Write-form built-ins gated by the page write-risk ledger: on a
     *  booked risk page the kernel never dispatches these actions — it
     *  answers with the read-only downgrade observation instead. */
    static final Set<String> WRITE_FORM_ACTIONS = Set.of("click-path", "input-path");

    /** Cap of risk-page lines annotated into the system prompt (worst-scale:
     *  the prompt stays bounded, the remainder reports its count honestly). */
    static final int PROMPT_RISK_LINE_CAP = 20;

    static final int EXEC_STATUS_SUCCESS = 3;

    
    static final String CRASH_MARK_JAVA = "FATAL EXCEPTION";
    static final String CRASH_MARK_NATIVE = "Fatal signal";

    
    static final String LOG_FN_WEB = "explore";
    static final String LOG_FN_MOBILE = "explore-appauto";

    /** Web-modality behaviour-directive gate key (factory default on). */
    public static final String KEY_WEB_DIRECTIVES_ENABLED = "ai.explore.web-directives.enabled";

    /** Mobile-modality behaviour-directive gate key (factory default on). */
    public static final String KEY_MOBILE_DIRECTIVES_ENABLED = "ai.explore.mobile-directives.enabled";

    /** Template family of each modality (tp_prompt_template name/domain pair). */
    public static final String TEMPLATE_NAME_WEB = "explore-web-directives";
    public static final String TEMPLATE_DOMAIN_WEB = "explore-web-loop";
    public static final String TEMPLATE_NAME_MOBILE = "explore-mobile-directives";
    public static final String TEMPLATE_DOMAIN_MOBILE = "explore-mobile-loop";

    /**
     * Built-in web contract — the conservative fallback head when the gate is
     * closed, no published row exists, or the template read fails. Carried
     * verbatim by the seed row of the web family; the per-session goal line is
     * code-side on purpose so template edits can never drop the run context.
     */
    static final String BUILTIN_WEB_CONTRACT = ("""
            你是自主测试探索代理。
            每轮至多选择一个工具动作发起 tool_call（内置动作：page-snapshot 读页面快照、\
            click-path 点击路径、input-path 输入、assert 断言观察；项目启用工具亦可只读调用）。
            当你判断目标已经达成时，回复正文必须包含契约位 %s；判断无法继续时直接总结收尾。
            """).formatted(GOAL_MARKER).strip();

    /** Built-in mobile contract (same three-way fallback as the web head);
     *  the roaming wording stays mutually pinned with executor MOBILE_ROAM_ACTIONS
     *  through ExploreEnvelopeContract. */
    static final String BUILTIN_MOBILE_CONTRACT = ("""
            你是 App 自主探索代理（移动形态）。
            被测应用以无障碍树（view tree）为观测面：先 tree-snapshot 读取当前界面元素树，\
            再在漫游动作中选择其一发起 tool_call——tree-snapshot 树观察、tap 点击元素、\
            back 返回、swipe 滑动、home 回桌面（每步在设备租约内续接上一界面，无需重复进入）。
            卸载/清库/安装类不可逆动作探索模式一律禁生成（请求也会被平台拒绝）。
            遇到应用崩溃迹象（观测含 FATAL EXCEPTION / Fatal signal）时，总结复现步骤并宣告。
            当你判断目标已经达成时，回复正文必须包含契约位 %s；判断无法继续时直接总结收尾。
            """).formatted(GOAL_MARKER).strip();

    private final LlmFallbackChain llmFallbackChain;
    private final ProjectLlmRouter projectLlmRouter;
    private final ExploreBuiltinToolCatalog builtinToolCatalog;
    private final LlmFunctionCalling functionCalling;
    private final ToolDispatcher dispatcher;
    private final ExploreEvidenceGateway evidenceGateway;
    private final CoreGateway coreGateway;
    private final AiCallLogWriter aiCallLog;
    private final ObjectMapper objectMapper;
    /** AP-2 per-round prompt-budget fitting , same gate as the
 * ReAct loop — one implementation, two consumers). */
    private final com.sixbean.testpike.aiservice.llm.domain.component.LoopContextFitting
            loopFitting;

    /** AP-1(1) budget folder seam for built-in act observations (nullable = legacy no-fold wiring). */
    private final com.sixbean.testpike.aiservice.tool.domain.component.ToolResultBudgetFolder
            folder;

    /** segment P1: process event ledger door (null = legacy zero-report — the
 * exploration loop never hinges on this bookkeeping; same optional-seam
 * posture as the extraction pipeline). */
    private com.sixbean.testpike.aiservice.taskstream.domain.component.TaskProcessEventStore
            processEvents;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setProcessEvents(
            com.sixbean.testpike.aiservice.taskstream.domain.component.TaskProcessEventStore store) {
        this.processEvents = store;
    }

    /** Ledger job kind of the autonomous exploration loop. Keys follow the
     *  frozen read-side convention (steps-gen posture): job_ref = run_token =
     *  the session id — one row set per session, a redelivered run appends to
     *  the same tail instead of forking an unkeyable stream. */
    public static final String PROCESS_JOB_KIND = "explore";

    @org.springframework.beans.factory.annotation.Autowired
    public ExploreLoopKernel(LlmFallbackChain llmFallbackChain,
                             ProjectLlmRouter projectLlmRouter,
                             ExploreBuiltinToolCatalog builtinToolCatalog,
                             LlmFunctionCalling functionCalling,
                             ToolDispatcher dispatcher,
                             ExploreEvidenceGateway evidenceGateway,
                             CoreGateway coreGateway,
                             AiCallLogWriter aiCallLog,
                             ObjectMapper objectMapper,
                             com.sixbean.testpike.aiservice.llm.domain.component
                                     .LoopContextFitting loopFitting,
                             com.sixbean.testpike.aiservice.tool.domain.component
                                     .ToolResultBudgetFolder folder) {
        this.llmFallbackChain = llmFallbackChain;
        this.projectLlmRouter = projectLlmRouter;
        this.builtinToolCatalog = builtinToolCatalog;
        this.functionCalling = functionCalling;
        this.dispatcher = dispatcher;
        this.evidenceGateway = evidenceGateway;
        this.coreGateway = coreGateway;
        this.aiCallLog = aiCallLog;
        this.objectMapper = objectMapper;
        this.loopFitting = loopFitting;
        this.folder = folder;
    }

    
    public void run(ExploreSessionBrief session, long actorId) {
        // Whole-site survey dialect : the crawl is deterministic —
        // one envelope, real-browser BFS on the executor, zero model rounds.
        if (ExploreEnvelopeContract.isSiteSurveyGoal(session.goalText())) {
            runSiteSurvey(session);
            return;
        }
        ExploreBudgetPolicy policy =
                ExploreBudgetPolicy.parse(session.budgetSnapshot(), objectMapper);
        boolean mobile = ExploreEnvelopeContract.isAppStartUrl(session.startUrl());
        // One narrow risk-door read per run feeds both faces —
        // the prompt annotation and the mechanical dispatch gate. Mobile roam
        // carries no page-url ledger, so the read stays web-shape only.
        List<PageWriteRiskView> riskPages = mobile ? List.of() : fetchRiskPages(session);
        FcPlan plan = functionCalling.assemblePlan(session.projectId(), actorId);
        LlmEndpointConfig endpoint = projectLlmRouter.route(session.projectId());
        List<ChatToolDef> builtinDefs = mobile
                ? builtinToolCatalog.mobileToolDefs() : builtinToolCatalog.toolDefs();
        List<ChatToolDef> tools = new ArrayList<>(builtinDefs);
        tools.addAll(plan.tools());
        FcPlan effectivePlan = new FcPlan(List.copyOf(tools), plan.index());
        List<ChatMessage> messages = new ArrayList<>(List.of(
                new ChatMessage("system", systemPrompt(session, mobile, riskPages)),
                new ChatMessage("user", userPrompt(session, mobile))));

        recordProcessEvent(session, "step",
                "已发起自动浏览｜" + (mobile ? "移动应用" : "网页") + "｜目标："
                        + (session.goalText() == null ? "未提供" : session.goalText())
                        + "｜起点：" + session.startUrl()
                        + "｜步数上限 " + policy.maxSteps(),
                Map.of("startUrl", String.valueOf(session.startUrl()),
                        "maxSteps", policy.maxSteps()));

        int stepsUsed = 0;
        int pagesVisited = session.pagesVisited() == null ? 0 : session.pagesVisited();
        String lastUrl = null;
        int noProgressStreak = 0;
        long tokensUsed = 0L;
        long startMs = System.currentTimeMillis();
        List<String> stepLog = new ArrayList<>();
        List<String> irStepSequence = new ArrayList<>();
        boolean crashSeen = false;
        int outcome = ExploreBudgetPolicy.OUTCOME_RUNNING;

        while (true) {
            ChatCompletionRequest base = new ChatCompletionRequest(endpoint.modelName(),
                    List.copyOf(messages), null, null);
            ChatCompletionRequest decorated = functionCalling.decorate(base, effectivePlan);
            // AP-2: over-budget exploration transcripts fold the older rounds
            // into the rolling summary instead of failing the whole run (and
            // the pair contract of the newest assistant/tool rounds stays raw).
            // A budget REFUSE (41301, packet still over limit after folding)
            // terminalizes here — propagating it would hand the message to the
            // stateless MQ retry, whose re-run dies on the duplicate step
            // write-back and leaves the session stuck non-terminal forever.
            try {
                decorated = loopFitting.fit(decorated, session.projectId());
            } catch (ServiceException refuse) {
                if (refuse.getCode() == null
                        || refuse.getCode() != BizErrorCode.AI_PROMPT_TOO_LARGE) {
                    throw refuse;
                }
                outcome = ExploreBudgetPolicy.OUTCOME_PARTIAL;
                stepLog.add("本次自动浏览提前结束：" + refuse.getMessage());
                recordProcessEvent(session, "error",
                        "本次自动浏览提前结束：" + refuse.getMessage(), null);
                log.warn("探索循环预算拒止终态封转 sessionId={} reason={}",
                        session.id(), refuse.getMessage());
                break;
            }
            LlmChatResult result = llmFallbackChain.chat(decorated, session.projectId(), null);
            tokensUsed += Math.max(0, result.promptTokens())
                    + Math.max(0, result.completionTokens());
            aiCallLog.insert(session.projectId(), result.provider(), result.model(),
                    mobile ? LOG_FN_MOBILE : LOG_FN_WEB,
                    Math.max(0, result.promptTokens()), Math.max(0, result.completionTokens()),
                    result.latencyMs());
            // One thinking line per provider round: the model's VISIBLE output
            // digest (the hidden chain-of-thought is never persisted — same
            // open-source redaction posture as the store door) plus the
            // machine token face the viewer can chart.
            recordProcessEvent(session, "thinking", result.content(),
                    Map.of("promptTokens", Math.max(0, result.promptTokens()),
                            "completionTokens", Math.max(0, result.completionTokens()),
                            "latencyMs", result.latencyMs()));
            boolean goalReached = result.content() != null && result.content().contains(GOAL_MARKER);
            List<LlmToolCall> calls = result.toolCalls();
            boolean noActionAvailable = calls == null || calls.isEmpty();
            long elapsedSeconds = (System.currentTimeMillis() - startMs) / 1000L;

            outcome = mobile
                    ? policy.classifyMobile(stepsUsed, tokensUsed, elapsedSeconds, goalReached,
                            noProgressStreak, noActionAvailable)
                    : policy.classify(stepsUsed, pagesVisited, goalReached,
                            noProgressStreak, noActionAvailable);
            if (outcome != ExploreBudgetPolicy.OUTCOME_RUNNING) {
                break;
            }
            messages.add(ChatMessage.assistantWithToolCalls(result.content(), calls));

            for (LlmToolCall call : calls) {
                stepsUsed++;
                if (mobile && !ExploreEnvelopeContract.isMobileRoamAction(call.name())
                        && isForbiddenRoamVerb(call.name())) {
                    messages.add(ChatMessage.tool(call.id(), toJson(Map.of(
                            "ok", false, "observation", ExploreEnvelopeContract
                                    .mobileRoamViolation(call.name())))));
                    stepLog.add("第 " + stepsUsed + "/" + policy.maxSteps() + " 步｜"
                            + ExploreActionNaming.humanLabel(call.name(), tools)
                            + "被安全限制拦下（不可逆操作不会对外发出）");
                    recordProcessEvent(session, "step",
                            "第 " + stepsUsed + "/" + policy.maxSteps() + " 步｜"
                                    + ExploreActionNaming.humanLabel(call.name(), tools)
                                    + "被安全限制拦下（不可逆操作不会对外发出）",
                            Map.of("action", call.name()));
                    continue;
                }
                // Mechanical gate (never bets on model
                // self-restraint): a click/input aimed at a booked write-risk
                // page is answered with the downgrade observation and the
                // loop steers away — zero outbound act submission.
                if (!mobile && WRITE_FORM_ACTIONS.contains(call.name())) {
                    String riskTarget = riskTargetUrl(call, lastUrl, session);
                    if (hitsRiskPage(riskPages, riskTarget)) {
                        messages.add(ChatMessage.tool(call.id(), toJson(Map.of(
                                "ok", false,
                                "observation", "该页已降级为只读：在该页输入与点击会直接保存数据，"
                                        + "请改用 page-snapshot 读取页面内容，再从其它只读入口离开该页。"))));
                        stepLog.add("第 " + stepsUsed + "/" + policy.maxSteps() + " 步｜"
                                + ExploreActionNaming.humanLabel(call.name(), tools)
                                + "命中写入风险页面，已改为只读观察，该操作未执行");
                        recordProcessEvent(session, "step",
                                "第 " + stepsUsed + "/" + policy.maxSteps() + " 步｜"
                                        + ExploreActionNaming.humanLabel(call.name(), tools)
                                        + "命中写入风险页面，已改为只读观察，该操作未执行",
                                Map.of("action", call.name(), "url", String.valueOf(riskTarget)));
                        continue;
                    }
                }
                ExploreActOutcome act = executeCall(session, plan, call, actorId, mobile);
                messages.add(ChatMessage.tool(call.id(), act.stepResultJson()));
                boolean actOk = isStepOk(act.stepResultJson());
                stepLog.add("第 " + stepsUsed + "/" + policy.maxSteps() + " 步｜"
                        + ExploreActionNaming.humanLabel(call.name(), tools)
                        + (actOk ? "执行成功" : "执行失败"));
                recordProcessEvent(session, "step",
                        "第 " + stepsUsed + "/" + policy.maxSteps() + " 步｜"
                                + ExploreActionNaming.humanLabel(call.name(), tools)
                                + (actOk ? "执行成功" : "执行失败"),
                        Map.of("action", call.name(), "ok", actOk));
                if (mobile) {
                    irStepSequence.add(ExploreEnvelopeContract.roamToIrStepAction(call.name()));
                    crashSeen |= containsCrashMark(act.stepResultJson());
                }

                String url = extractUrl(call.argumentsJson());
                if (url != null) {
                    if (url.equals(lastUrl)) {
                        noProgressStreak++;
                    } else {
                        noProgressStreak = 0;
                        pagesVisited++;
                        lastUrl = url;
                    }
                }
                String domKey = uploadDomEvidence(session.id(), stepsUsed, call.name(),
                        act.stepResultJson());
                recordStepIdempotent(session, stepsUsed, call, act, domKey, result.content());
            }
            long elapsed = (System.currentTimeMillis() - startMs) / 1000L;
            outcome = mobile
                    ? policy.classifyMobile(stepsUsed, tokensUsed, elapsed, false,
                            noProgressStreak, false)
                    : policy.classify(stepsUsed, pagesVisited, false,
                            noProgressStreak, false);
            if (outcome != ExploreBudgetPolicy.OUTCOME_RUNNING) {
                break;
            }
        }

        recordProcessEvent(session, "step",
                "自动浏览结束｜" + outcomeLabel(outcome) + "｜共 " + stepsUsed + "/"
                        + policy.maxSteps() + " 步｜浏览 " + pagesVisited + " 页",
                Map.of("outcome", outcome, "stepsUsed", stepsUsed,
                        "pagesVisited", pagesVisited, "tokensUsed", tokensUsed));
        coreGateway.updateExploreStats(session.id(), new ExploreRunStatsUpdate(
                pagesVisited, null, buildReport(session, outcome, stepsUsed, pagesVisited,
                stepLog, mobile, crashSeen, irStepSequence)));
        coreGateway.updateExploreStatus(session.id(), outcome);
        log.info("探索循环出口 sessionId={} outcome={} steps={} pages={}",
                session.id(), outcome, stepsUsed, pagesVisited);
    }

    /**
     * Step write-back re-entry (CTXCURVE r9 defect 2): when a retried or
     * replayed delivery re-records a seq core already journaled, core answers
     * 40904 (duplicate step seq). The step fact is on the books, so the duplicate is
     * skipped and the loop continues — propagating it would send the message
     * into the stateless retry, which re-hits the same guard and leaves the
     * session stuck non-terminal. All other errors propagate unchanged.
     */
    private void recordStepIdempotent(ExploreSessionBrief session, int stepsUsed,
                                      LlmToolCall call, ExploreActOutcome act,
                                      String domKey, String thinkContent) {
        journalStep(session, new ExploreStepRecord(stepsUsed,
                buildStepActionJson(call), act.stepResultJson(), act.screenshotKey(),
                domKey, summarizeThink(thinkContent), act.candidatePoolJson()));
    }

    /** Single step-door write with the duplicate-seq skip (one door body —
     *  both the LLM loop and the deterministic site replay ride it, R40). */
    private void journalStep(ExploreSessionBrief session, ExploreStepRecord record) {
        try {
            coreGateway.recordExploreStep(session.id(), record);
        } catch (ServiceException dup) {
            if (dup.getCode() != null && dup.getCode() == BizErrorCode.RESOURCE_CONFLICT) {
                log.info("步进回写重复已跳过（重投重放） sessionId={} seq={}",
                        session.id(), record.seq());
                return;
            }
            throw dup;
        }
    }

    
    private static boolean isForbiddenRoamVerb(String name) {
        return UiActionContract.exploreForbiddenViolation(name) != null;
    }

    
    private static boolean containsCrashMark(String observationJson) {
        return observationJson != null && (observationJson.contains(CRASH_MARK_JAVA)
                || observationJson.contains(CRASH_MARK_NATIVE));
    }

    
    private ExploreActOutcome executeCall(ExploreSessionBrief session, FcPlan plan,
                                          LlmToolCall call, long actorId, boolean mobile) {
        if (mobile ? ExploreEnvelopeContract.isMobileRoamAction(call.name())
                : BUILTIN_ACTIONS.contains(call.name())) {
            long execTaskId = coreGateway.submitExploreAct(session.id(),
                    buildActEnvelopeJson(call, mobile));
            ExecTaskResultRemote task = pollExecTask(execTaskId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", task != null && task.status() == EXEC_STATUS_SUCCESS);
            result.put("execTaskId", execTaskId);
            // AP-1(1): built-in observations backfill outside the dispatcher,
            // so they hit the SAME tool-block budget gate here (one folding
            // implementation, two entry points — never a second copy).
            result.put("observation", folder == null ? buildObservation(task)
                    : folder.fold(buildObservation(task), null, session.projectId()));
            // WE-S3 an element-candidates step carries the page's raw
            // interactive pool in its timeline detail; hand it to the step
            // journal so core can reconcile confirmed anchors against it.
            String candidatePool = mobile ? null
                    : ExploreTaskTimeline.detailFor(
                            task == null ? null : task.stepsTimeline(), "element-candidates");
            return new ExploreActOutcome(toJson(result), firstScreenshotKey(task), candidatePool);
        }
        ToolCallOutcome outcome = dispatcher.dispatch(plan, call, actorId, null, null, null,
                session.projectId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", outcome.success());
        result.put("observation", outcome.content());
        return new ExploreActOutcome(toJson(result), null, null);
    }

 
    private ExecTaskResultRemote pollExecTask(long execTaskId) {
        return coreGateway.pollExecTaskResult(execTaskId);
    }

    // ------------------------------------------------- whole-site survey branch

    /** Safety belt over the crawl's frozen deadline (poll stops only past it). */
    static final long SITE_POLL_MARGIN_MS = 120_000L;

    /** Crawl deadline assumed when the snapshot carries none (dispatch shipped default 1800s). */
    static final long SITE_POLL_DEFAULT_DEADLINE_MS = 1_800_000L;

    /**
     * Poll cap of the deterministic crawl: the dispatch-frozen deadlineSeconds
     * plus the shipped margin — a run never waits past its own budget promise.
     * Missing deadline in the snapshot falls back to the shipped default.
     */
    static long siteSurveyPollCapMs(JsonNode rules) {
        long deadlineMs = SITE_POLL_DEFAULT_DEADLINE_MS;
        if (rules != null && rules.path("deadlineSeconds").isNumber()
                && rules.path("deadlineSeconds").asInt() > 0) {
            deadlineMs = rules.path("deadlineSeconds").asInt() * 1000L;
        }
        return SITE_POLL_MARGIN_MS + deadlineMs;
    }

    /**
 * Deterministic whole-site survey run : no model is
 * ever asked on this branch. The loop reads the dispatch-time rules
 * snapshot from core, submits ONE site-survey envelope through the shared
 * /acts door, waits for the crawl's terminal task state within the
 * deadline-derived poll cap, and replays every page journal frame back
 * through the single step door (the core relay tallies pages and the
 * collector keeps its one ingest path there). Honest terminals: an
 * unreadable snapshot or unreachable read path closes the session partial
 * with guidance — never done, never thrown.
 */
    private void runSiteSurvey(ExploreSessionBrief session) {
        JsonNode rules;
        try {
            rules = readJson(coreGateway.getSiteSurveyRulesBySession(session.id()));
        } catch (Exception doorFailure) {
            log.warn("site survey rules door failed sessionId={}: {}",
                    session.id(), doorFailure.getMessage());
            rules = null;
        }
        if (rules == null) {
            closeSiteSurveyWithoutCrawl(session);
            return;
        }
        long execTaskId = coreGateway.submitExploreAct(session.id(),
                buildSiteSurveyEnvelopeJson(rules));
        recordProcessEvent(session, "step",
                "整站测绘已发起｜起点 " + rules.path("startUrl").asText("")
                        + "｜页数上限 " + rules.path("maxPages").asInt(0)
                        + "｜将逐页采集页面元素", null);
        ExecTaskResultRemote task = coreGateway.pollExecTaskResult(
                execTaskId, siteSurveyPollCapMs(rules));
        int okPages = 0;
        int frames = 0;
        JsonNode timeline = task == null ? null : readJson(task.stepsTimeline());
        if (timeline != null && timeline.isArray()) {
            int nextSeq = 1;
            for (JsonNode frame : timeline) {
                if (!ExploreEnvelopeContract.SITE_PAGE_FRAME_ACTION
                        .equals(frame.path("action").asText())) {
                    continue;
                }
                JsonNode detail = readJson(frame.path("detail").asText(null));
                if (detail == null) {
                    continue;
                }
                int seq = frame.path("stepNo").asInt(nextSeq);
                nextSeq = seq + 1;
                boolean pageOk = "ok".equals(detail.path("status").asText());
                Map<String, Object> arguments = new LinkedHashMap<>();
                arguments.put("url", detail.path("url").asText(null));
                arguments.put("status", detail.path("status").asText(null));
                arguments.put("reason", detail.path("reason").asText(""));
                if (detail.path("counters").isObject()) {
                    arguments.put("counters", detail.path("counters"));
                }
                // the guard's per-page network window rides the
                // step door too (light payload: keys appear only when they
                // carry rows). The page write-risk ledger is the only
                // consumer; a frame without risk facts stays byte-identical.
                forwardWhenPresent(detail, arguments, "writeEvents");
                forwardWhenPresent(detail, arguments, "writeSurface");
                forwardWhenPresent(detail, arguments, "netFailures");
                if (detail.path("snapshotOnly").asBoolean(false)) {
                    arguments.put("snapshotOnly", true);
                }
                Map<String, Object> stepAction = new LinkedHashMap<>();
                stepAction.put("name", ExploreEnvelopeContract.SITE_PAGE_FRAME_ACTION);
                stepAction.put("arguments", arguments);
                Map<String, Object> stepResult = new LinkedHashMap<>();
                stepResult.put("ok", pageOk);
                stepResult.put("execTaskId", execTaskId);
                JsonNode pool = detail.path("candidatePool");
                // Wire contract (executor single source): the pool rides the
                // frame detail as the serialized candidate envelope TEXT
                // ({"candidates": [...], "count": n}) — the same payload the
                // single-page element-candidates door journals into this
                // step field. Anything textual and non-blank forwards as
                // pool json; absent/blank is an honest pool-less frame.
                String poolJson = pool.isTextual() && !pool.asText().isBlank()
                        ? pool.asText() : null;
                journalStep(session, new ExploreStepRecord(seq, toJson(stepAction),
                        toJson(stepResult), null, null, null, poolJson));
                frames++;
                if (pageOk) {
                    okPages++;
                }
            }
        }
        int outcome = task != null && task.status() == EXEC_STATUS_SUCCESS
                ? ExploreBudgetPolicy.OUTCOME_DONE : ExploreBudgetPolicy.OUTCOME_PARTIAL;
        coreGateway.updateExploreStats(session.id(), new ExploreRunStatsUpdate(okPages, null,
                buildSiteSurveyReport(session, task, frames, okPages, outcome)));
        coreGateway.updateExploreStatus(session.id(), outcome);
        log.info("site survey kernel branch closed sessionId={} outcome={} frames={} okPages={}",
                session.id(), outcome, frames, okPages);
    }

    /** Crawl envelope: one explore-act carrying the frozen snapshot verbatim
     *  (the executor consumes the siteSurvey face; no rule is re-read downstream). */
    private String buildSiteSurveyEnvelopeJson(JsonNode rules) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("kind", ExploreEnvelopeContract.EXPLORE_ACT_KIND);
        envelope.put("action", ExploreEnvelopeContract.SITE_SURVEY_ACTION);
        envelope.put("url", rules.path("startUrl").asText(null));
        envelope.put(ExploreEnvelopeContract.SITE_SURVEY_RULES_FIELD, rules);
        return toJson(envelope);
    }

    /** Honest stop before any crawl when the snapshot door has no answer
     *  (R63 face: why + re-dispatch path + the retention parameter name). */
    private void closeSiteSurveyWithoutCrawl(ExploreSessionBrief session) {
        String guidance = "整站测绘未能开始：没有读到本次会话的测绘规则记录（运行记录不存在，或已超过留存期）。"
                + "请到「站点档案」页对该站点重新发起一次整站测绘；如需延长历史留存，"
                + "可在系统设置参数配置中按名称 element.survey-site.event-retention-days 调整留存天数。";
        recordProcessEvent(session, "error", guidance, null);
        coreGateway.updateExploreStats(session.id(), new ExploreRunStatsUpdate(0, null, guidance));
        coreGateway.updateExploreStatus(session.id(), ExploreBudgetPolicy.OUTCOME_PARTIAL);
    }

    /** Session report of the crawl close (pages on the books + the failure face). */
    private String buildSiteSurveyReport(ExploreSessionBrief session, ExecTaskResultRemote task,
                                         int frames, int okPages, int outcome) {
        StringBuilder md = new StringBuilder("# 整站测绘报告\n\n")
                .append("- 目标：").append(session.goalText() == null ? "" : session.goalText())
                .append('\n')
                .append("- 出口：")
                .append(outcome == ExploreBudgetPolicy.OUTCOME_DONE
                        ? "整站深钻完成" : "整站深钻部分完成")
                .append('\n')
                .append("- 页帧：").append(frames).append("　成功页：").append(okPages).append('\n');
        if (task == null) {
            md.append("- 执行结果未能取得（执行器离线或回传通道故障），本次未重放页帧；")
                    .append("已采集页数以步进记录为准，可稍后从「站点档案」页重新发起整站测绘。\n");
        } else if (task.errorDetail() != null && !task.errorDetail().isBlank()) {
            md.append("- 失败明细：").append(task.errorDetail()).append('\n');
        }
        return md.toString();
    }

    
    private String buildObservation(ExecTaskResultRemote task) {
        if (task == null) {
            return "动作已提交但未取得执行结果（读路径暂不可达），请基于现有观察决策";
        }
        StringBuilder obs = new StringBuilder();
        appendTimeline(obs, task.stepsTimeline());
        if (task.errorDetail() != null && !task.errorDetail().isBlank()) {
            obs.append("失败详情：").append(task.errorDetail()).append('\n');
        }
        if (obs.isEmpty()) {
            obs.append("执行状态码=").append(task.status()).append("（无步骤明细）");
        }
        return obs.toString();
    }

    
    private void appendTimeline(StringBuilder obs, String stepsTimelineJson) {
        JsonNode timeline = readJson(stepsTimelineJson);
        if (timeline == null || !timeline.isArray()) {
            return;
        }
        for (JsonNode step : timeline) {
            obs.append("步骤").append(step.path("stepNo").asInt())
                    .append(" [").append(step.path("action").asText()).append("] ")
                    .append(step.path("status").asText());
            String detail = step.path("detail").asText(null);
            if (detail != null && !detail.isBlank()) {
                obs.append("：").append(detail);
            }
            obs.append('\n');
        }
    }

    
    private String firstScreenshotKey(ExecTaskResultRemote task) {
        if (task == null) {
            return null;
        }
        JsonNode shots = readJson(task.screenshots());
        if (shots != null && shots.isArray() && !shots.isEmpty()) {
            return shots.get(0).asText(null);
        }
        return fallbackScreenshotKey(task.stepsTimeline());
    }

    
    private String fallbackScreenshotKey(String stepsTimelineJson) {
        JsonNode timeline = readJson(stepsTimelineJson);
        if (timeline == null || !timeline.isArray()) {
            return null;
        }
        for (JsonNode step : timeline) {
            String evidence = step.path("evidence").path("screenshot").asText(null);
            if (isScreenshotKindKey(evidence)) {
                return evidence;
            }
            String artifact = step.path("artifactKey").asText(null);
            if (isScreenshotKindKey(artifact)) {
                return artifact;
            }
        }
        return null;
    }

    
    private boolean isScreenshotKindKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        int slash = key.lastIndexOf('/');
        String base = key.substring(slash + 1);
        int dash = base.indexOf('-');
        String kindSegment = dash >= 0 ? base.substring(dash + 1) : base;
        return kindSegment.startsWith("screenshot_");
    }

    
    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    
    private String buildActEnvelopeJson(LlmToolCall call, boolean mobile) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("kind", ExploreEnvelopeContract.EXPLORE_ACT_KIND);
        if (mobile) {
            envelope.put("target", ExploreEnvelopeContract.TARGET_MOBILE);
        }
        envelope.put("action", call.name());
        JsonNode args = readArgs(call.argumentsJson());
        if (!mobile) {
            envelope.put("url", args == null ? null : args.path("url").asText(null));
        }
        envelope.put("arguments", args);
        return toJson(envelope);
    }

    
    private String buildStepActionJson(LlmToolCall call) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("name", call.name());
        action.put("arguments", readArgs(call.argumentsJson()));
        return toJson(action);
    }

    
    private String uploadDomEvidence(long sessionId, int seq, String actionName,
                                     String stepResultJson) {
        try {
            return evidenceGateway.uploadEvidence(sessionId, "dom_snapshot",
                    "step-" + seq + "-" + actionName + ".txt",
                    stepResultJson.getBytes(StandardCharsets.UTF_8), "text/plain");
        } catch (Exception e) {
            log.warn("留证上传失败 sessionId={} seq={}: {}", sessionId, seq, e.getMessage());
            return null;
        }
    }

    private String extractUrl(String argumentsJson) {
        JsonNode args = readArgs(argumentsJson);
        return args == null ? null : args.path("url").asText(null);
    }

    
    private JsonNode readArgs(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(argumentsJson);
        } catch (Exception e) {
            return null;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"ok\":false,\"output\":\"内核载荷序列化失败\"}";
        }
    }

    /**
     * Append one exploration process event (tp_ai_task_event through the
     * shared store door). Quiet on every face by design: an uninstalled seam
     * is a zero-touch no-op, a detail serialisation hiccup degrades the
     * machine face to "{}" but still lands the human line, and the store door
     * itself swallows write outages — bookkeeping never fails the run it
     * observes (same contract as the extraction pipeline's seam).
     */
    private void recordProcessEvent(ExploreSessionBrief session, String level,
                                    String summary, Map<String, Object> detail) {
        if (processEvents == null || session == null || session.id() == null) {
            return;
        }
        String anchor = String.valueOf(session.id());
        String detailJson = null;
        if (detail != null) {
            try {
                detailJson = objectMapper.writeValueAsString(detail);
            } catch (Exception quiet) {
                detailJson = "{}";
            }
        }
        processEvents.record(PROCESS_JOB_KIND, anchor, anchor, level,
                com.sixbean.testpike.aiservice.taskstream.domain.component
                        .ProcessEventDigest.digest(summary), detailJson);
    }

    /** Observation envelope ok flag (unreadable = not ok). */
    private boolean isStepOk(String stepResultJson) {
        JsonNode node = readJson(stepResultJson);
        return node != null && node.path("ok").asBoolean(false);
    }

    
    private static String summarizeThink(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        return content.length() <= 500 ? content : content.substring(0, 500);
    }

    /**
 * System head for one exploration round through the shared L0 assembly
 * : governance gate first (closed = zero template reads),
 * the published row of the modality's family when open, the built-in
 * contract on any miss, and the kernel invariant appended exactly once —
 * all single-sourced in BehaviorKernelAssembly.
 */
    String behaviorDirectivesHead(boolean mobile) {
        return mobile
                ? BehaviorKernelAssembly.assembleHeadFromGateway(coreGateway,
                        KEY_MOBILE_DIRECTIVES_ENABLED, TEMPLATE_NAME_MOBILE,
                        TEMPLATE_DOMAIN_MOBILE, BUILTIN_MOBILE_CONTRACT,
                        "行为指令模板读路径不可用，本轮移动探索改用内置指令模板")
                : BehaviorKernelAssembly.assembleHeadFromGateway(coreGateway,
                        KEY_WEB_DIRECTIVES_ENABLED, TEMPLATE_NAME_WEB,
                        TEMPLATE_DOMAIN_WEB, BUILTIN_WEB_CONTRACT,
                        "行为指令模板读路径不可用，本轮网页探索改用内置指令模板");
    }

    /**
     * System prompt per modality (one piece, two shapes — never a copy): the
     * static contract resolves through the L0 head assembly (published row or
     * built-in fallback), while the per-session goal line stays code-side so
     * an operator rewording the template can never drop the run context.
     * Mobile = accessibility-tree roaming briefing plus the five roam
     * candidates, pinned against executor MOBILE_ROAM_ACTIONS through
     * ExploreEnvelopeContract on both ends.
     */
    private String systemPrompt(ExploreSessionBrief session, boolean mobile,
                                List<PageWriteRiskView> riskPages) {
        String goal = session.goalText() == null ? "" : session.goalText();
        return behaviorDirectivesHead(mobile) + "\n\n探索目标：" + goal
                + riskLedgerLines(riskPages);
    }

    /**
     * One annotation line per booked write-risk page with the
     * literal screen guidance, bounded by {@link #PROMPT_RISK_LINE_CAP}; the
     * remainder reports its honest count instead of silently dropping rows.
     */
    static String riskLedgerLines(List<PageWriteRiskView> riskPages) {
        if (riskPages == null || riskPages.isEmpty()) {
            return "";
        }
        StringBuilder lines = new StringBuilder();
        riskPages.stream().limit(PROMPT_RISK_LINE_CAP).forEach(page -> lines
                .append("\n写入风险页：").append(page.pageUrl())
                .append(" — 该页输入会直接保存数据，只允许读取页面快照。"));
        if (riskPages.size() > PROMPT_RISK_LINE_CAP) {
            lines.append("\n另有 ").append(riskPages.size() - PROMPT_RISK_LINE_CAP)
                    .append(" 个写入风险页未在此列出，此类页面上的操作同样不会被执行。");
        }
        return lines.toString();
    }

    /**
     * Risk-door read with the established fail-soft posture:
     * a door outage never kills the run — it degrades visibly (WARN log plus
     * a process-event line, same shape as the behavior-template fallback)
     * and the loop keeps its pre-slice behavior for this round only.
     */
    private List<PageWriteRiskView> fetchRiskPages(ExploreSessionBrief session) {
        try {
            return coreGateway.listPageWriteRiskPages(session.projectId());
        } catch (ServiceException | org.springframework.web.client.RestClientException outage) {
            // Outage faces include transport/HTTP errors (unreachable core,
            // 404, undecodable body) which Spring surfaces below
            // RestClientException rather than ServiceException.
            log.warn("页面写入风险清单读路径不可用，本轮不标注风险页 sessionId={} reason={}",
                    session.id(), outage.getMessage());
            recordProcessEvent(session, "error",
                    "页面写入风险清单暂时不可用（" + outage.getMessage() + "），本轮探索不标注风险页",
                    null);
            return List.of();
        }
    }

    /** Gate target of one write-form call: the call's own url, else the last
     *  page the loop visited, else the session start url (fallback chain). */
    private String riskTargetUrl(LlmToolCall call, String lastUrl,
                                 ExploreSessionBrief session) {
        String url = extractUrl(call.argumentsJson());
        return url != null ? url : (lastUrl != null ? lastUrl : session.startUrl());
    }

    /** Ledger membership of a page URL (normalization = trim + trailing slash
     *  only — the same face the ledger rows carry from executor observation). */
    static boolean hitsRiskPage(List<PageWriteRiskView> riskPages, String url) {
        if (url == null || url.isBlank() || riskPages == null || riskPages.isEmpty()) {
            return false;
        }
        String target = normalizePageUrl(url);
        return riskPages.stream().anyMatch(page -> normalizePageUrl(page.pageUrl()).equals(target));
    }

    private static String normalizePageUrl(String url) {
        String trimmed = url.strip();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    
    private static String userPrompt(ExploreSessionBrief session, boolean mobile) {
        if (!mobile) {
            return "起始 URL：" + (session.startUrl() == null ? "未提供" : session.startUrl())
                    + "。目标：" + (session.goalText() == null ? "" : session.goalText());
        }
        String pkg = session.startUrl().strip()
                .substring(ExploreEnvelopeContract.APP_URL_PREFIX.length());
        StringBuilder prompt = new StringBuilder("被测应用包名：").append(pkg)
                .append("。目标：").append(session.goalText() == null ? "" : session.goalText());
        if (session.credRef() != null && !session.credRef().isBlank()) {
            prompt.append("。测试账号引用：{{CRED:")
                    .append(session.credRef().strip()).append("}}（执行时由系统自动填入真实账号）");
        }
        return prompt.toString();
    }

 
    private static String buildReport(ExploreSessionBrief session, int outcome,
                                      int stepsUsed, int pagesVisited, List<String> stepLog,
                                      boolean mobile, boolean crashSeen,
                                      List<String> irStepSequence) {
        StringBuilder md = new StringBuilder("# 探索报告\n\n")
                .append("- 目标：").append(session.goalText() == null ? "" : session.goalText()).append('\n')
                .append("- 出口：").append(outcomeLabel(outcome)).append('\n')
                .append("- 步数：").append(stepsUsed)
                .append("　页数：").append(pagesVisited).append('\n')
                .append("\n## 步骤序列\n\n");
        if (stepLog.isEmpty()) {
            md.append("（无执行步）\n");
        } else {
            stepLog.forEach(line -> md.append("- ").append(line).append('\n'));
        }
        if (mobile && crashSeen) {
            md.append("\n## 崩溃现场步骤序列\n\n")
                    .append("应用崩溃已捕获，复现本崩溃的抽象步骤序列如下")
                    .append("（app.* IR 动词，可一键存为用例草稿进评审门）：\n\n");
            if (irStepSequence.isEmpty()) {
                md.append("（无已执行步）\n");
            } else {
                irStepSequence.forEach(step -> md.append("- ").append(step).append('\n'));
            }
        }
        return md.toString();
    }

    private static String outcomeLabel(int outcome) {
        return switch (outcome) {
            case ExploreBudgetPolicy.OUTCOME_DONE -> "目标达成";
            case ExploreBudgetPolicy.OUTCOME_BUDGET_STOP -> "达到本次任务的步数上限（部分完成）";
            case ExploreBudgetPolicy.OUTCOME_PARTIAL -> "无进展终止（部分完成）";
            default -> "未正常结束";
        };
    }

    /** Copy one optional guard-window key from a page frame detail into the
     *  relayed step arguments (light payload: empty or absent arrays trail
     *  nothing, so clean frames stay byte-identical on the step door). */
    private void forwardWhenPresent(JsonNode detail, Map<String, Object> arguments,
                                    String key) {
        JsonNode node = detail.path(key);
        if (node.isArray() && !node.isEmpty()) {
            arguments.put(key, node);
        }
    }
}
