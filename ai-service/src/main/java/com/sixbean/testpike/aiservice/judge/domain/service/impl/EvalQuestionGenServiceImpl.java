package com.sixbean.testpike.aiservice.judge.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.aichat.domain.component.ChatBehaviorDirectives;
import com.sixbean.testpike.aiservice.doc.domain.component.ContextBudgetPlanner;
import com.sixbean.testpike.aiservice.doc.domain.component.ExtractFidelityLedger;
import com.sixbean.testpike.aiservice.doc.domain.component.ExtractFidelityLedgerStore;
import com.sixbean.testpike.aiservice.doc.domain.component.ExtractFidelityMeter;
import com.sixbean.testpike.aiservice.doc.domain.component.ModelAdaptiveBudget;
import com.sixbean.testpike.aiservice.doc.domain.component.PlaneLabels;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.GroupFanoutAbortException;
import com.sixbean.testpike.aiservice.llm.domain.component.GroupParallelFanout;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmTimeouts;
import com.sixbean.testpike.aiservice.llm.domain.component.RunBudgetAbort;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.pipeline.BehaviorKernelAssembly;
import com.sixbean.testpike.aiservice.llm.pipeline.RecallDirectiveSwitch;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmTimeoutResolver;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.llm.pipeline.RecallDensityGate;
import com.sixbean.testpike.aiservice.llm.domain.component.SysConfigInts;
import com.sixbean.testpike.aiservice.judge.api.dto.QaGenJobView;
import com.sixbean.testpike.aiservice.judge.api.dto.QaGenPreviewView;
import com.sixbean.testpike.aiservice.judge.api.dto.QaGenRequest;
import com.sixbean.testpike.aiservice.judge.api.dto.QaGenRunView;
import com.sixbean.testpike.aiservice.judge.api.dto.QaGenSubmitView;
import com.sixbean.testpike.aiservice.judge.domain.component.QaGenJobStore;
import com.sixbean.testpike.aiservice.judge.domain.component.QaPairItemsParser;
import com.sixbean.testpike.aiservice.judge.domain.service.EvalQuestionGenService;
import com.sixbean.testpike.aiservice.taskstream.domain.component.TaskEventRun;
import com.sixbean.testpike.aiservice.taskstream.domain.component.TaskProcessEvent;
import com.sixbean.testpike.aiservice.taskstream.domain.component.TaskProcessEventStore;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.LlmExtractMarkers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <p>Job plane : {@code submit} answers inline only
 * within judge.qa-gen.sync-group-max sealed groups; beyond the cap a single
 * background thread per run walks the groups, lands cumulative items on the
 * job row and books one process event per group through the shared event
 * door. The run-token predicate on every write is the preemption boundary —
 * an old run can only ever touch its own row.
 */
@Service
public class EvalQuestionGenServiceImpl implements EvalQuestionGenService {

    private static final Logger log = LoggerFactory.getLogger(EvalQuestionGenServiceImpl.class);

    
    static final String GEN_MARKER = LlmExtractMarkers.JUDGE_QA_GEN;

    
    public static final String KEY_MAX_CHUNKS = "judge.qa-gen.max-chunks";

    
    static final int DEFAULT_MAX_CHUNKS = 20;

    /** Behaviour-directive gate for the question-generation leg (factory default on). */
    public static final String KEY_DIRECTIVES_ENABLED = "ai.judge.qa-gen-directives.enabled";

    /** Template family of this leg (tp_prompt_template name). */
    public static final String TEMPLATE_NAME = "qa-gen-directives";

    /** Template business domain of this family (tp_prompt_template domain). */
    public static final String TEMPLATE_DOMAIN = "judge-qa-gen";

    /**
 * grouped-intake door (tri-state, factory on): open = over-count
 * chunk sets are sealed into token-bounded groups through the shared
 * planner instead of being rejected; only an explicit 0/false restores
 * the legacy cost-gate rejection face byte-for-byte.
 */
    public static final String KEY_GROUPED_INTAKE_ENABLED = "ai.judge.grouped-intake.enabled";

    /** A3 namespace rule: this door's grouping caps live under its own
     *  governance prefix and never borrow the ai.doc.* rows. */
    static final String GROUP_KEY_PREFIX = "judge.qa-gen.";

    /**
 * K8 hot key : how many sealed groups may still be
 * answered synchronously inline. Default 1 keeps today's single-group
 * healthy face byte-identical; over the cap the submit face accepts a
 * background job instead of riding the provider window to a timeout.
 */
    public static final String KEY_SYNC_GROUP_MAX = "judge.qa-gen.sync-group-max";

    /** Factory default of the sync answer cap (whole runs become jobs). */
    static final int DEFAULT_SYNC_GROUP_MAX = 1;

    /** Process-event job kind of this door; stays inside the viewer's filter face. */
    static final String JOB_KIND = "qa-gen";

    /**
     * System head for one chunk-generation call through the shared L0
     * assembly: gate → published row → built-in contract fallback on any
     * miss, kernel invariant appended exactly once. The built-in contract
     * keeps the {@code JUDGE_QA_GEN_JSON} marker and output schema, so the
     * parser and the core-side source-anchor re-check stay intact either way.
     */
    String behaviorDirectivesHead() {
        return BehaviorKernelAssembly.assembleHeadFromGateway(coreGateway,
                KEY_DIRECTIVES_ENABLED, TEMPLATE_NAME, TEMPLATE_DOMAIN,
                buildSystemPrompt(),
                "行为指令模板读路径不可用，本轮 AI 造题回退内置契约")
                // : recall block appended at the assembled-head tail so
                // both the template-row path and the built-in fallback carry the
                // count contract; OFF state stays byte-identical to the pre- head.
                + recallSegment();
    }

    /**
 * per-domain hot switch for the exhaustive-recall clause block on
 * the judge question-generation face (fail-soft ON, read single-sourced
 * in RecallDirectiveSwitch).
 */
    public static final String KEY_RECALL_DIRECTIVE_ENABLED = "ai.judge.recall-directive.enabled";

    /** Recall segment appended to the assembled head (zero bytes off). */
    String recallSegment() {
        return RecallDirectiveSwitch.segment(
                coreGateway.getSysConfig(KEY_RECALL_DIRECTIVE_ENABLED), KEY_RECALL_DIRECTIVE_ENABLED);
    }

    private final CoreGateway coreGateway;
    private final LlmFallbackChain llmFallbackChain;
    private final ProjectLlmRouter projectLlmRouter;
    private final QaPairItemsParser itemsParser;
    /** Shared fidelity ledger store (; null or a failing door never
 * fails the generation run — bookkeeping is fail-soft by contract). */
    private final ExtractFidelityLedgerStore fidelityStore;
    /** Judge-plane per-call provider ceiling resolver :
 * K8 ai.judge.qa-gen.llm-timeout-seconds → global face. Null = no domain
 * ceiling, the legacy 4-face call stays byte-identical. */
    private final LlmTimeoutResolver judgeTimeoutResolver;
    /** Job ledger door . Null = job plane unavailable, the
 * submit face honestly stays on the synchronous answer. */
    private final QaGenJobStore jobStore;
    /** Shared process-event ledger of long AI runs . */
    private final TaskProcessEventStore processEvents;
    private final ObjectMapper objectMapper;

    public EvalQuestionGenServiceImpl(CoreGateway coreGateway, LlmFallbackChain llmFallbackChain,
                                      ProjectLlmRouter projectLlmRouter, ObjectMapper objectMapper,
                                      ExtractFidelityLedgerStore fidelityStore,
                                      LlmTimeoutResolver judgeTimeoutResolver) {
        this(coreGateway, llmFallbackChain, projectLlmRouter, objectMapper, fidelityStore,
                judgeTimeoutResolver, null, null);
    }

    @Autowired
    public EvalQuestionGenServiceImpl(CoreGateway coreGateway, LlmFallbackChain llmFallbackChain,
                                      ProjectLlmRouter projectLlmRouter, ObjectMapper objectMapper,
                                      ExtractFidelityLedgerStore fidelityStore,
                                      LlmTimeoutResolver judgeTimeoutResolver,
                                      QaGenJobStore jobStore,
                                      TaskProcessEventStore processEvents) {
        this.coreGateway = coreGateway;
        this.llmFallbackChain = llmFallbackChain;
        this.projectLlmRouter = projectLlmRouter;
        this.objectMapper = objectMapper;
        this.itemsParser = new QaPairItemsParser(objectMapper);
        this.fidelityStore = fidelityStore;
        this.judgeTimeoutResolver = judgeTimeoutResolver;
        this.jobStore = jobStore;
        this.processEvents = processEvents;
    }

    @Override
    public QaGenPreviewView generate(QaGenRequest request) {
        long documentId = request.documentId();
        long projectId = request.projectId();
        LlmEndpointConfig endpoint = projectLlmRouter.route(projectId);
        String model = request.modelHint() == null || request.modelHint().isBlank()
                ? endpoint.modelName() : request.modelHint();
        boolean modelPinned = request.modelHint() != null && !request.modelHint().isBlank();

        List<Map<String, Object>> chunks = coreGateway.getDocumentChunks(documentId);
        if (chunks == null || chunks.isEmpty()) {
            throw new ServiceException("文档没有可用分块，AI 造题失败，请确认文档解析成功后重试",
                    BizErrorCode.AI_FORMAT_ERROR);
        }
        boolean grouped = ChatBehaviorDirectives.switchOn(
                coreGateway.getSysConfig(KEY_GROUPED_INTAKE_ENABLED));
        ContextBudgetPlanner planner = null;
        List<List<Map<String, Object>>> groups = new ArrayList<>();
        if (grouped) {
            planner = ContextBudgetPlanner.intakePlanner(
                    ModelAdaptiveBudget.effectiveTokens(endpoint.promptLimitTokens(),
                            coreGateway.getSysConfig(ModelAdaptiveBudget.KEY_SAFETY_FACTOR)),
                    endpoint.tokenizerFamily(), model, coreGateway::getSysConfig,
                    GROUP_KEY_PREFIX);
            groups = planner.planGroupsByTokens(chunks);
        } else {
            int maxChunks = SysConfigInts.positiveInt(
                    coreGateway.getSysConfig(KEY_MAX_CHUNKS), DEFAULT_MAX_CHUNKS);
            if (chunks.size() > maxChunks) {
                throw new ServiceException("文档分块数 " + chunks.size() + " 超过单次造题上限 "
                        + maxChunks + "（系统设置-" + KEY_MAX_CHUNKS + " 可调），请拆分文档后重试",
                        BizErrorCode.AI_FORMAT_ERROR);
            }
            for (Map<String, Object> chunk : chunks) {
                groups.add(List.of(chunk));
            }
        }

        log.info("AI 造题开始 documentId={} projectId={} model={} chunks={} groups={} grouped={}",
                documentId, projectId, model, chunks.size(), groups.size(), grouped);
        LlmFallbackNotice notice = new LlmFallbackNotice(request.operatorId(), projectId,
                "评测集 AI 造题（文档#" + documentId + "）");
        List<Map<String, Object>> items = new ArrayList<>();
        LinkedHashMap<String, Map<String, Object>> merged = new LinkedHashMap<>();
        ExtractFidelityMeter meter = new ExtractFidelityMeter();
        int promptTokens = 0;
        int completionTokens = 0;
        int estimateTokens = 0;
        int ordinal = 0;
        try {
            for (List<Map<String, Object>> group : groups) {
                LlmChatResult result = genGroupCall(model, modelPinned, projectId, notice, group);
                promptTokens += result.promptTokens();
                completionTokens += result.completionTokens();
                meter.record(GEN_MARKER, result.promptTokens(), result.completionTokens());
                for (Map<String, Object> chunk : group) {
                    estimateTokens += planner == null
                            ? String.valueOf(chunk.get("content")).length()
                            : planner.tokensOf(chunk);
                }
                for (Map<String, Object> item : itemsParser.parse(result.content())) {
                    if (grouped) {
                        merged.putIfAbsent(mergeKeyOf(item, ordinal++), item);
                    } else {
                        items.add(item);
                    }
                }
            }
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("AI 造题服务异常: " + e.getMessage(),
                    BizErrorCode.AI_FORMAT_ERROR);
        }
        // Preview faces : chunkCount stays the source-chunk count, the
        // LLM call face is the group count above (booked into the fidelity
        // row); items merge cross-group by normalized question when the door
        // is on, legacy per-chunk accumulation when it is closed.
        List<Map<String, Object>> finalItems = grouped ? new ArrayList<>(merged.values()) : items;
        if (finalItems.isEmpty()) {
            throw new ServiceException("文档中未生成可回答的问答对，请确认上传的是含事实内容的文档",
                    BizErrorCode.AI_FORMAT_ERROR);
        }
        recordFidelityQuiet(documentId, chunks, groups.size(), meter, finalItems, planner,
                estimateTokens);
        log.info("AI 造题完成 documentId={} 条目数={} tokens={}/{}",
                documentId, finalItems.size(), promptTokens, completionTokens);
        recallDensityCheck(documentId, finalItems.size(), estimateTokens);
        return new QaGenPreviewView(finalItems, chunks.size(), promptTokens, completionTokens, model);
    }

    /** piece 2: per-door recall-density floor hot key (band 0-50,
 * 0 = check off); factory 19.0 is the recalibrated low pass of the two
 * measured samples (V503) so a healthy run never trips. The decision
 * arithmetic is single-sourced in {@link RecallDensityGate}. */
    static final String KEY_RECALL_DENSITY_FLOOR =
            "ai.judge.recall-density-floor-per-ktoken";
    static final double DEFAULT_RECALL_DENSITY_FLOOR = 19.0;

    /** WARN one honest numbers line when the run stays under the floor; a
     *  met or switched-off floor keeps both close-out faces byte-silent. */
    private void recallDensityCheck(long documentId, int itemCount, int estimatedTokens) {
        double floor = RecallDensityGate.parseFloor(
                coreGateway.getSysConfig(KEY_RECALL_DENSITY_FLOOR),
                DEFAULT_RECALL_DENSITY_FLOOR);
        if (RecallDensityGate.missed(floor, itemCount, estimatedTokens)) {
            log.warn("造题密度下限闸 documentId={} 实收={} 条数下限={} 源token={}",
                    documentId, itemCount,
                    RecallDensityGate.expectedItems(estimatedTokens, floor), estimatedTokens);
        }
    }

    @Override
    public QaGenSubmitView submit(QaGenRequest request) {
        long documentId = request.documentId();
        long projectId = request.projectId();
        LlmEndpointConfig endpoint = projectLlmRouter.route(projectId);
        String model = request.modelHint() == null || request.modelHint().isBlank()
                ? endpoint.modelName() : request.modelHint();
        boolean modelPinned = request.modelHint() != null && !request.modelHint().isBlank();

        List<Map<String, Object>> chunks = coreGateway.getDocumentChunks(documentId);
        if (chunks == null || chunks.isEmpty()) {
            throw new ServiceException("文档没有可用分块，AI 造题失败，请确认文档解析成功后重试",
                    BizErrorCode.AI_FORMAT_ERROR);
        }
        if (!ChatBehaviorDirectives.switchOn(
                coreGateway.getSysConfig(KEY_GROUPED_INTAKE_ENABLED))) {
            throw new ServiceException("分组造题开关处于关闭态，任务通道不可用；请在 系统设置 打开 "
                    + KEY_GROUPED_INTAKE_ENABLED + " 后重试，或改用单次同步造题",
                    BizErrorCode.AI_FORMAT_ERROR);
        }
        ContextBudgetPlanner planner = ContextBudgetPlanner.intakePlanner(
                ModelAdaptiveBudget.effectiveTokens(endpoint.promptLimitTokens(),
                        coreGateway.getSysConfig(ModelAdaptiveBudget.KEY_SAFETY_FACTOR)),
                endpoint.tokenizerFamily(), model, coreGateway::getSysConfig,
                GROUP_KEY_PREFIX);
        List<List<Map<String, Object>>> groups = planner.planGroupsByTokens(chunks);
        int syncCap = SysConfigInts.positiveInt(
                coreGateway.getSysConfig(KEY_SYNC_GROUP_MAX), DEFAULT_SYNC_GROUP_MAX);
        if (groups.size() <= syncCap || jobStore == null || processEvents == null) {
            // Within the cap (or with no job door wired) the caller gets the
            // exact legacy inline answer — same prompt, same merge, same cost.
            return QaGenSubmitView.sync(generate(request));
        }

        String runToken = UUID.randomUUID().toString();
        jobStore.startRun(documentId, runToken, projectId, model, groups.size());
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "qa-gen-job-" + documentId);
                    thread.setDaemon(true);
                    return thread;
                });
        pool.submit(() -> runJobGroups(request, model, modelPinned, runToken, groups,
                chunks, planner));
        pool.shutdown();
        log.info("AI 造题转后台任务 documentId={} runToken={} groups={} syncCap={}",
                documentId, runToken, groups.size(), syncCap);
        return QaGenSubmitView.accepted(runToken);
    }

    @Override
    public QaGenJobView jobStatus(long documentId) {
        return jobStore == null ? null : jobStore.findLatestByDocument(documentId);
    }

    @Override
    public List<TaskProcessEvent> jobEvents(long documentId, String runToken,
                                            long cursor, int limit) {
        if (processEvents == null) {
            return List.of();
        }
        return processEvents.fetchSince(JOB_KIND, String.valueOf(documentId), runToken,
                cursor, limit);
    }

    @Override
    public List<QaGenRunView> qaGenRuns(long documentId, int limit) {
        if (processEvents == null) {
            return List.of();
        }
        List<TaskEventRun> runs = processEvents.listRuns(JOB_KIND,
                String.valueOf(documentId), limit);
        // Terminal verdict is resolved read-side only (no new write path):
        // the live job row answers running, a stream carrying an error frame
        // answers failed, every other aggregated run reads as done.
        QaGenJobView latest = jobStore == null ? null : jobStore.findLatestByDocument(documentId);
        String liveToken = latest != null && QaGenJobView.STATUS_RUNNING.equals(latest.status())
                ? latest.runToken()
                : null;
        List<QaGenRunView> views = new ArrayList<>(runs.size());
        for (TaskEventRun run : runs) {
            String status = run.runToken().equals(liveToken)
                    ? QaGenJobView.STATUS_RUNNING
                    : run.hasError()
                            ? QaGenJobView.STATUS_FAILED
                            : QaGenJobView.STATUS_DONE;
            views.add(new QaGenRunView(run.runToken(), status, run.events(),
                    run.firstAt(), run.lastAt()));
        }
        return views;
    }

    /**
     * Background body of one accepted run: walks the sealed groups with the
     * same call/merge face as the synchronous door (single source), lands the
     * cumulative items on the job row after every group, and books one step
     * event per landed group. Terminal success books the same qa-gen fidelity
     * row as the synchronous door through the shared recordFidelityQuiet
     * seam; any failure closes the row as failed with the human cause plus an
     * error event and books no row — a half-run never pollutes the retention
     * account.
     */
    private void runJobGroups(QaGenRequest request, String model, boolean modelPinned,
                              String runToken, List<List<Map<String, Object>>> groups,
                              List<Map<String, Object>> chunks, ContextBudgetPlanner planner) {
        long documentId = request.documentId();
        long projectId = request.projectId();
        LlmFallbackNotice notice = new LlmFallbackNotice(request.operatorId(), projectId,
                "评测集 AI 造题·后台任务（文档#" + documentId + "）");
        LinkedHashMap<String, Map<String, Object>> merged = new LinkedHashMap<>();
        ExtractFidelityMeter meter = new ExtractFidelityMeter();
        int ordinal = 0;
        int promptTokens = 0;
        int completionTokens = 0;
        int estimateTokens = 0;
        int landed = 0;
        try {
            // wiring (R40): the job's group calls ride the
            // single-source GroupParallelFanout (bounded, hot key
            // ai.pipeline.group-parallel read fresh per run, hard cap 3) —
            // the previous strict serial walk on the launcher thread is the
            // closed spectrum the key<=1 face keeps byte-identical. The
            // landing face (cumulative job rows, step events, merge ordinals)
            // is replayed strictly in GROUP order after collection, so a
            // half-run can never leak partial items: an aborting group fails
            // closed with zero landings.
            int groupParallel = GroupParallelFanout.parallelismOf(
                    coreGateway.getSysConfig(GroupParallelFanout.KEY_GROUP_PARALLEL));
            // one run-level cooperative-cancel signal for the
            // whole job run — the first provider-timeout family failure trips
            // it BEFORE rethrowing untouched (fail-closed, original
            // vocabulary zero drift), so the fan-out never launches an
            // unstarted sibling and the launcher stops within one poll
            // instead of riding every group to its own clamp death.
            RunBudgetAbort budgetAbort = new RunBudgetAbort();
            List<java.util.concurrent.Callable<LlmChatResult>> groupTasks =
                    new ArrayList<>(groups.size());
            for (int gi = 0; gi < groups.size(); gi++) {
                List<Map<String, Object>> group = groups.get(gi);
                int groupSeq = gi + 1;
                groupTasks.add(() -> {
                    try {
                        return genGroupCall(model, modelPinned, projectId, notice, group);
                    } catch (RuntimeException failure) {
                        if (LlmTimeouts.isTimeoutFamily(failure)) {
                            budgetAbort.trip("造题组 " + groupSeq + " 检出模型调用超时");
                        }
                        throw failure;
                    }
                });
            }
            List<LlmChatResult> results = GroupParallelFanout.runAll(
                    groupTasks, groupParallel, "qa-gen-group-" + documentId, budgetAbort);
            for (int i = 0; i < groups.size(); i++) {
                List<Map<String, Object>> group = groups.get(i);
                LlmChatResult result = results.get(i);
                promptTokens += result.promptTokens();
                completionTokens += result.completionTokens();
                meter.record(GEN_MARKER, result.promptTokens(), result.completionTokens());
                for (Map<String, Object> chunk : group) {
                    estimateTokens += planner == null
                            ? String.valueOf(chunk.get("content")).length()
                            : planner.tokensOf(chunk);
                }
                for (Map<String, Object> item : itemsParser.parse(result.content())) {
                    merged.putIfAbsent(mergeKeyOf(item, ordinal++), item);
                }
                landed++;
                List<Map<String, Object>> cumulative = new ArrayList<>(merged.values());
                jobStore.landGroup(documentId, runToken, writeJsonQuiet(cumulative),
                        landed, groups.size(), promptTokens, completionTokens);
                processEvents.record(JOB_KIND, String.valueOf(documentId), runToken, "step",
                        "第" + landed + "/" + groups.size() + "组完成，累计问答对 "
                                + cumulative.size() + " 条（tokens " + promptTokens + "/"
                                + completionTokens + "）", null);
            }
            if (merged.isEmpty()) {
                String cause = "文档中未生成可回答的问答对，请确认上传的是含事实内容的文档";
                jobStore.finishRun(runToken, QaGenJobView.STATUS_FAILED, cause);
                // The terminal error event goes through the
                // single-source formatter so the drawer's last line carries
                // category + suggestion + retry entry, never a bare cause text.
                com.sixbean.testpike.aiservice.taskstream.domain.component.TaskFailureEvent
                        terminal = com.sixbean.testpike.aiservice.taskstream.domain.component
                                .TaskFailureEventFormatter.format(cause, "评测集页「AI 造题」重新发起");
                processEvents.record(JOB_KIND, String.valueOf(documentId), runToken, "error",
                        terminal.summary(), terminal.detailJson());
                return;
            }
            recordFidelityQuiet(documentId, chunks, groups.size(), meter,
                    new ArrayList<>(merged.values()), planner, estimateTokens, runToken);
            // Same close-out floor verdict as the synchronous face (shared
            // helper, single source) so a background job shortfall is never
            // louder-silent than an inline preview of the same document.
            recallDensityCheck(documentId, merged.size(), estimateTokens);
            jobStore.finishRun(runToken, QaGenJobView.STATUS_DONE, null);
            log.info("AI 造题后台任务完成 documentId={} runToken={} 条目数={} tokens={}/{}",
                    documentId, runToken, merged.size(), promptTokens, completionTokens);
        } catch (Exception raw) {
            // a gate-stop attributes as an honest timeout on
            // the job plane's own per-call ceiling BEFORE the terminal face
            // reads the cause — the job row and the drawer event name the
            // timeout and its adjustable key, never a generic service error
            // .
            Exception e = raw instanceof GroupFanoutAbortException
                    ? new ServiceException("评测集造题的模型调用超时，已停止未开始的分组"
                            + "（单调用时限 ai.judge.qa-gen.llm-timeout-seconds，可在系统设置调整），"
                            + "请在评测集页重新发起", BizErrorCode.AI_TIMEOUT)
                    : raw;
            String cause = e instanceof ServiceException se
                    ? se.getMessage() : "AI 造题服务异常: " + e.getMessage();
            jobStore.finishRun(runToken, QaGenJobView.STATUS_FAILED, cause);
            // Same single-source shape for the exception-killed run (the job
            // row keeps the raw cause; the event face adds the guidance).
            com.sixbean.testpike.aiservice.taskstream.domain.component.TaskFailureEvent
                    terminal = com.sixbean.testpike.aiservice.taskstream.domain.component
                            .TaskFailureEventFormatter.format(e, "评测集页「AI 造题」重新发起");
            processEvents.record(JOB_KIND, String.valueOf(documentId), runToken, "error",
                    terminal.summary(), terminal.detailJson());
            log.warn("AI 造题后台任务失败 documentId={} runToken={} cause={}",
                    documentId, runToken, cause);
        }
    }

    private String writeJsonQuiet(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception malformed) {
            log.warn("造题作业行 items 序列化失败，按空清单落账 cause={}", malformed.toString());
            return "[]";
        }
    }

    /** One grouped intake call: the whole group rides the prompt once (the
     *  closed-door legacy face passes single-chunk lists, keeping the old
     *  per-chunk prompt byte-identical). */
    private LlmChatResult genGroupCall(String model, boolean modelPinned, long projectId,
                                       LlmFallbackNotice notice,
                                       List<Map<String, Object>> group) {
        List<ChatMessage> messages = List.of(
                new ChatMessage("system", behaviorDirectivesHead()),
                new ChatMessage("user", buildGroupPrompt(group)));
        ChatCompletionRequest request = new ChatCompletionRequest(model, messages, null, null);
        if (judgeTimeoutResolver == null) {
            return llmFallbackChain.chat(request, projectId, notice, modelPinned);
        }
        // L-C: the judge plane rides its own per-call ceiling (K8
        // ai.judge.qa-gen.llm-timeout-seconds → global face), composed
        // through the shared seam; shortens only, never lengthens.
        return llmFallbackChain.chat(request, projectId, notice, modelPinned,
                LlmTimeoutResolver.clampUnderCeiling(null, judgeTimeoutResolver.resolveJudgeQaGen()));
    }

    /** Group user prompt = every block's body, numbered; a single-block
     *  group reuses the legacy face verbatim. */
    private String buildGroupPrompt(List<Map<String, Object>> group) {
        if (group.size() == 1) {
            return buildUserPrompt(String.valueOf(group.get(0).get("content")));
        }
        StringBuilder sb = new StringBuilder("请从以下文档分块中生成问答对：\n");
        int index = 1;
        for (Map<String, Object> chunk : group) {
            sb.append("分块").append(index++).append("：\n正文：")
              .append(String.valueOf(chunk.get("content"))).append("\n\n");
        }
        return sb.toString();
    }

    /** Cross-group question identity: the normalized question (first
     *  occurrence wins). A blank question keeps an ordinal key so distinct
     *  unusable entries cannot cancel each other silently. */
    private static String mergeKeyOf(Map<String, Object> item, int ordinal) {
        String question = String.valueOf(item.getOrDefault("question", "")).trim()
                .toLowerCase(java.util.Locale.ROOT);
        return question.isEmpty() ? "@unkeyed:" + ordinal : question;
    }

    /** : book one fidelity row per generation through the shared
 * ledger store (fail-soft — bookkeeping never fails the generation
 * run; a null store simply skips the row). */
    /** Synchronous door books under a fresh run token (no job plane row). */
    private void recordFidelityQuiet(long documentId, List<Map<String, Object>> chunks,
                                     int nodeCount, ExtractFidelityMeter meter,
                                     List<Map<String, Object>> items,
                                     ContextBudgetPlanner planner, int estimateTokens) {
        recordFidelityQuiet(documentId, chunks, nodeCount, meter, items, planner,
                estimateTokens, UUID.randomUUID().toString());
    }

    /**
 * : the run identity is now an explicit parameter so the background
 * door books under its job run token — the ledger row binds
 * ref_qa_job_id through that token and survives the temporary document's
 * reclamation (the old random token could never bind, and the row
 * cascaded away with the document).
 */
    private void recordFidelityQuiet(long documentId, List<Map<String, Object>> chunks,
                                     int nodeCount, ExtractFidelityMeter meter,
                                     List<Map<String, Object>> items,
                                     ContextBudgetPlanner planner, int estimateTokens,
                                     String runId) {
        if (fidelityStore == null) {
            return;
        }
        try {
            int sourceTokens = 0;
            if (planner != null) {
                sourceTokens = (int) planner.totalTokensOf(chunks);
            } else {
                for (Map<String, Object> chunk : chunks) {
                    sourceTokens += String.valueOf(chunk.get("content")).length();
                }
            }
            int anchored = 0;
            for (Map<String, Object> item : items) {
                if (item.get("sourceAnchor") instanceof String anchor && !anchor.isBlank()) {
                    anchored++;
                }
            }
            int distinctChapters = (int) chunks.stream()
                    .map(chunk -> String.valueOf(chunk.getOrDefault("chapter", "")))
                    .distinct().count();
            fidelityStore.upsertIfChanged(ExtractFidelityLedger.entry(documentId,
                    runId, ExtractFidelityLedger.TASK_QA_GEN,
                    sourceTokens, chunks.size(), nodeCount, distinctChapters, meter,
                    items.size(), anchored, estimateTokens,
                    new PlaneLabels(GEN_MARKER, GEN_MARKER + ":summary",
                            GEN_MARKER + ":gap")));
        } catch (Exception e) {
            log.warn(ExtractFidelityLedger.rejectNote(
                    ExtractFidelityLedger.TASK_QA_GEN, documentId, runId, e));
        }
    }

 
    private String buildSystemPrompt() {
        return "你是评测集造题器（" + GEN_MARKER + "）。"
                + "从给定文档分块中生成带参考答案的问答对，输出 JSON：\n"
                + "{\"items\":[{\"question\":\"问题\",\"answer\":\"参考答案\",\n"
                + "\"sourceAnchor\":\"答案在原文中的连续原文片段（必填，逐字引用）\"}]}\n"
                + "硬性约束：只出文档内可回答的 QA 对，答案带出处原句 sourceAnchor，"
                + "禁发明文档外事实；该分块出不了题就输出 {\"items\":[]}。";
    }

    
    private String buildUserPrompt(String chunkContent) {
        return "请从以下文档分块中生成问答对：\n正文：" + chunkContent;
    }
}
