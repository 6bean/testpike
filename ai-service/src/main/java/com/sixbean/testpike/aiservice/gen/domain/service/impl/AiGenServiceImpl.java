package com.sixbean.testpike.aiservice.gen.domain.service.impl;

import com.sixbean.testpike.common.util.ActionJsonValidator;
import com.sixbean.testpike.common.util.JsonOutputExtractor;
import com.sixbean.testpike.common.util.LlmExtractMarkers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.api.dto.GeneratedCase;
import com.sixbean.testpike.aiservice.gen.api.dto.GeneratedStep;
import com.sixbean.testpike.aiservice.gen.domain.component.AppStepGenBridge;
import com.sixbean.testpike.aiservice.gen.domain.component.CaseJsonParser;
import com.sixbean.testpike.aiservice.gen.domain.component.GenStructuredOutput;
import com.sixbean.testpike.aiservice.gen.domain.component.CoverageChecker;
import com.sixbean.testpike.aiservice.gen.domain.component.CoverageResult;
import com.sixbean.testpike.aiservice.gen.domain.component.OriginalTextBlockAssembler;
import com.sixbean.testpike.aiservice.gen.domain.component.RuleLedgerOutcome;
import com.sixbean.testpike.aiservice.gen.domain.component.RuleLedgerReconciler;
import com.sixbean.testpike.aiservice.gen.domain.component.RuleSourceBlock;
import com.sixbean.testpike.aiservice.gen.domain.component.PageInspectBridge;
import com.sixbean.testpike.aiservice.gen.domain.component.WebElementLedgerSummaryBridge;
import com.sixbean.testpike.aiservice.gen.domain.component.PriorityRule;
import com.sixbean.testpike.aiservice.gen.domain.component.PromptTemplateResolver;
import com.sixbean.testpike.aiservice.gen.domain.component.PromptWordingAdoption;
import com.sixbean.testpike.aiservice.gen.domain.component.PoolRowRelevanceScoring;
import com.sixbean.testpike.aiservice.gen.domain.component.ResourceGapReconciliation;
import com.sixbean.testpike.aiservice.gen.domain.component.ScriptLimitResolver;
import com.sixbean.testpike.aiservice.gen.domain.component.ScriptSliceMerger;
import com.sixbean.testpike.aiservice.gen.domain.component.ScriptSlicePlanner;
import com.sixbean.testpike.aiservice.gen.domain.component.SelfCheckResult;
import com.sixbean.testpike.aiservice.gen.domain.component.SelfCheckValidator;
import com.sixbean.testpike.aiservice.gen.domain.service.AiGenService;
import com.sixbean.testpike.aiservice.aichat.domain.component.ChatBehaviorDirectives;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.pipeline.BehaviorKernelAssembly;
import com.sixbean.testpike.aiservice.llm.pipeline.RedactGate;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.llm.domain.component.SkillAssemblyBridge;
import com.sixbean.testpike.aiservice.llm.domain.component.SkillStage;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.EntityGoneSignals;
import com.sixbean.testpike.common.mq.AiGenMessage;
import com.sixbean.testpike.common.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;


@Service
public class AiGenServiceImpl implements AiGenService {

    private static final Logger log = LoggerFactory.getLogger(AiGenServiceImpl.class);

 
    private static final int SELF_CHECK_RETRY = 2;
    
    private static final int ACTION_RETRY = 2;
 
    private static final int COVERAGE_RETRY = 1;

    private final LlmFallbackChain llmFallbackChain;
    private final ProjectLlmRouter projectLlmRouter;
    private final CoreGateway coreGateway;
    private final ObjectMapper objectMapper;
    private final CaseJsonParser caseJsonParser;
    private final SelfCheckValidator selfCheckValidator;
    private final CoverageChecker coverageChecker;
    private final PriorityRule priorityRule;
    private final ActionJsonValidator actionJsonValidator;
    private final ScriptLimitResolver scriptLimitResolver;
 
    private final SkillAssemblyBridge skillBridge;
 
    private final com.sixbean.testpike.aiservice.mcp.domain.component.McpKnowledgeBridge mcpBridge;
 
    private final com.sixbean.testpike.aiservice.mcp.domain.component.McpClientPool mcpPool;
 
    private final com.sixbean.testpike.aiservice.tool.domain.component.ReActRunner reactRunner;
 
    private final com.sixbean.testpike.aiservice.knowledge.domain.component.StyleFewshotBridge fewshotBridge;
 
    private final com.sixbean.testpike.aiservice.knowledge.domain.component.DdlDictBridge ddlBridge;
 
    private final PromptTemplateResolver promptTemplateResolver;
 
    private final PromptWordingAdoption wordingAdoption;
 
    private final PageInspectBridge pageInspectBridge;
 
    private final AppStepGenBridge appStepGenBridge;

 
    private final com.sixbean.testpike.aiservice.gen.domain.component
            .OriginalTextBlockAssembler originalTextBlockAssembler;

    /**
 * faithfulness checker wired into the stage1 ring
 * verifier (null = legacy assembly, zero behaviour change). Hot keys
 * below are read per call — K8 semantics = read fresh, never cache.
 */
    private final com.sixbean.testpike.aiservice.gen.domain.component
            .FidelityGate fidelityGate;

    /** Master switch of the faithfulness gate; the row ships on, an absent row keeps it on. */
    static final String FIDELITY_GATE_KEY = "ai.gen.fidelity-gate.enabled";

    /**
 * honest-marking sub-rule of the same gate: with no probe candidates
 * values the contract and the element ledger cannot back. Ships on (R32);
 * off = the legacy blank-segment shape with zero audited claims.
 */
    static final String NO_TRUTH_HONEST_MARK_KEY = "ai.gen.no-truth-honest-mark.enabled";

    /** Regeneration rounds the faithfulness disputes may drive (default 1, range 0..2). */
    static final String FIDELITY_ROUNDS_KEY = "ai.gen.fidelity-recheck-rounds";

    /** segment B-2: rule-ledger door; an absent row reads as off. */
    static final String RULE_LEDGER_KEY = "ai.gen.rule-ledger.enabled";

    private static final int FIDELITY_ROUNDS_DEFAULT = 1;
    private static final int FIDELITY_ROUNDS_MAX = 2;

    /**
     * Format-residual targeted repair budget (V615 stop-loss): rounds of one
     * schema-bearing tool-free re-issue the system drives itself when the
     * deliberation ring ends on a shape violation (default 1, range 0..2;
     * 0 = repair off, the legacy hard-fail returns). Read per call (K8).
     */
    static final String FORMAT_REPAIR_ROUNDS_KEY = "ai.gen.format-repair-rounds";

    private static final int FORMAT_REPAIR_ROUNDS_DEFAULT = 1;
    private static final int FORMAT_REPAIR_ROUNDS_MAX = 2;

    /** Draft characters the repair prompt may carry (hot, floor 1000). */
    static final String FORMAT_REPAIR_CHARS_KEY = "ai.gen.format-repair-chars";

    private static final int FORMAT_REPAIR_CHARS_DEFAULT = 6000;

    /**
 * measured-confidence composite wired at the stage1
 * return point (null = legacy assembly, the self-report is kept).
 */
    private final com.sixbean.testpike.aiservice.gen.domain.component
            .ConfidenceMeasurer confidenceMeasurer;

    /**
 * contract truth-source bridge wired at the stage1
 * prompt build (null = legacy assembly, zero injection). Gate, fail-soft
 * and budget rules live in the bridge — read fresh per task, K8 semantics.
 */
    private final com.sixbean.testpike.aiservice.gen.domain.component
            .ContractTruthSourceBridge contractBridge;

    /**
 * S3: the per-send prompt-budget fitting seam shared with the dialog
 * loops (null = LoopContextFitting.NONE identity = legacy "send as built"
 * bytes). The gate component itself stays the single aichat-domain source —
 * this is only a NEW consumer entry on the generation plane.
 */
    private final com.sixbean.testpike.aiservice.llm.domain.component.LoopContextFitting
            loopFitting;

    /**
 * Shared process-event ledger door of long AI runs ( single source,
 * S4 segment B): the steps-gen run appends its ring milestone, save
 * point and rejection/failure causes there so the job drawer can tail
 * the run live like a log. Null = legacy wiring, the recording hooks
 * are silent no-ops; the store itself is fail-soft (a write outage
 * never fails the job), so no extra try/catch layer rides on top.
 */
    private final com.sixbean.testpike.aiservice.taskstream.domain.component.TaskProcessEventStore
            processEvents;
    /** WE piece C: confirmed page-element summary source for the stage-2
     * prompt (null = zero injection = legacy bytes). */
    private final WebElementLedgerSummaryBridge ledgerSummaryBridge;

    /**
     * Output-shape capability ledger (V615): null on the zero-injection legacy
     * assemblies — form decisions then stay exactly the shipped shape. A
     * present ledger records the accept-but-not-enforce class observed at the
     * enforcement-notice site and steers later schema-bearing calls of the
     * same model into the enforceable (tool-free) form.
     */
    private final com.sixbean.testpike.aiservice.llm.domain.component
            .LlmOutputCapabilityLedger capabilityLedger;

    /** segment B-2: per-item rule-ledger reconciliation source
 * (null = zero ledger = legacy stage-1 judgement bytes). The hot switch
 * ai.gen.rule-ledger.enabled fails closed to off, so an unwired or
 * switched-off assembly pulls no chunks and emits no feedback lines. */
    private final RuleLedgerReconciler ruleLedgerReconciler;

    /**
     * Job-kind literal of the steps-gen face, frozen by the V460 ledger
     * registration and mirrored by the core read door
     * (AiTaskServiceImpl.JOB_KIND_STEPS_GEN) — job_ref and run_token both
     * key on the task id, one keying scheme, no second form.
     */
    static final String PROCESS_JOB_KIND = "steps-gen";

    /** Master switch of the measured confidence; the row ships on, an absent row keeps it on. */
    static final String CONFIDENCE_SIGNALS_KEY = "ai.gen.confidence-signals.enabled";

 
    public AiGenServiceImpl(LlmFallbackChain llmFallbackChain, ProjectLlmRouter projectLlmRouter,
                            CoreGateway coreGateway,
                            ObjectMapper objectMapper, CaseJsonParser caseJsonParser,
                            SelfCheckValidator selfCheckValidator, CoverageChecker coverageChecker,
                            PriorityRule priorityRule, ActionJsonValidator actionJsonValidator,
                            ScriptLimitResolver scriptLimitResolver) {
        this(llmFallbackChain, projectLlmRouter, coreGateway, objectMapper, caseJsonParser,
                selfCheckValidator, coverageChecker, priorityRule, actionJsonValidator,
                scriptLimitResolver, null, null, null, null, null, null, null);
    }

 
    public AiGenServiceImpl(LlmFallbackChain llmFallbackChain, ProjectLlmRouter projectLlmRouter,
                            CoreGateway coreGateway,
                            ObjectMapper objectMapper, CaseJsonParser caseJsonParser,
                            SelfCheckValidator selfCheckValidator, CoverageChecker coverageChecker,
                            PriorityRule priorityRule, ActionJsonValidator actionJsonValidator,
                            ScriptLimitResolver scriptLimitResolver,
                            com.sixbean.testpike.aiservice.gen.domain.component
                                    .OriginalTextBlockAssembler originalTextBlockAssembler) {
        this(llmFallbackChain, projectLlmRouter, coreGateway, objectMapper, caseJsonParser,
                selfCheckValidator, coverageChecker, priorityRule, actionJsonValidator,
                scriptLimitResolver, null, null, null, null, null, null, null, null, null, null,
                originalTextBlockAssembler);
    }

 
    public AiGenServiceImpl(LlmFallbackChain llmFallbackChain, ProjectLlmRouter projectLlmRouter,
                            CoreGateway coreGateway,
                            ObjectMapper objectMapper, CaseJsonParser caseJsonParser,
                            SelfCheckValidator selfCheckValidator, CoverageChecker coverageChecker,
                            PriorityRule priorityRule, ActionJsonValidator actionJsonValidator,
                            ScriptLimitResolver scriptLimitResolver,
                            SkillAssemblyBridge skillBridge) {
        this(llmFallbackChain, projectLlmRouter, coreGateway, objectMapper, caseJsonParser,
                selfCheckValidator, coverageChecker, priorityRule, actionJsonValidator,
                scriptLimitResolver, skillBridge, null, null, null, null, null);
    }

 
    public AiGenServiceImpl(LlmFallbackChain llmFallbackChain, ProjectLlmRouter projectLlmRouter,
                            CoreGateway coreGateway,
                            ObjectMapper objectMapper, CaseJsonParser caseJsonParser,
                            SelfCheckValidator selfCheckValidator, CoverageChecker coverageChecker,
                            PriorityRule priorityRule, ActionJsonValidator actionJsonValidator,
                            ScriptLimitResolver scriptLimitResolver,
                            SkillAssemblyBridge skillBridge,
                            com.sixbean.testpike.aiservice.mcp.domain.component.McpKnowledgeBridge mcpBridge) {
        this(llmFallbackChain, projectLlmRouter, coreGateway, objectMapper, caseJsonParser,
                selfCheckValidator, coverageChecker, priorityRule, actionJsonValidator,
                scriptLimitResolver, skillBridge, mcpBridge, null, null, null, null);
    }

 
    public AiGenServiceImpl(LlmFallbackChain llmFallbackChain, ProjectLlmRouter projectLlmRouter,
                            CoreGateway coreGateway,
                            ObjectMapper objectMapper, CaseJsonParser caseJsonParser,
                            SelfCheckValidator selfCheckValidator, CoverageChecker coverageChecker,
                            PriorityRule priorityRule, ActionJsonValidator actionJsonValidator,
                            ScriptLimitResolver scriptLimitResolver,
                            SkillAssemblyBridge skillBridge,
                            com.sixbean.testpike.aiservice.mcp.domain.component.McpKnowledgeBridge mcpBridge,
                            com.sixbean.testpike.aiservice.mcp.domain.component.McpClientPool mcpPool,
                            com.sixbean.testpike.aiservice.tool.domain.component.ReActRunner reactRunner) {
        this(llmFallbackChain, projectLlmRouter, coreGateway, objectMapper, caseJsonParser,
                selfCheckValidator, coverageChecker, priorityRule, actionJsonValidator,
                scriptLimitResolver, skillBridge, mcpBridge, mcpPool, reactRunner, null, null);
    }

 
    public AiGenServiceImpl(LlmFallbackChain llmFallbackChain, ProjectLlmRouter projectLlmRouter,
                            CoreGateway coreGateway,
                            ObjectMapper objectMapper, CaseJsonParser caseJsonParser,
                            SelfCheckValidator selfCheckValidator, CoverageChecker coverageChecker,
                            PriorityRule priorityRule, ActionJsonValidator actionJsonValidator,
                            ScriptLimitResolver scriptLimitResolver,
                            SkillAssemblyBridge skillBridge,
                            com.sixbean.testpike.aiservice.mcp.domain.component.McpKnowledgeBridge mcpBridge,
                            com.sixbean.testpike.aiservice.mcp.domain.component.McpClientPool mcpPool,
                            com.sixbean.testpike.aiservice.tool.domain.component.ReActRunner reactRunner,
                            com.sixbean.testpike.aiservice.knowledge.domain.component.StyleFewshotBridge fewshotBridge) {
        this(llmFallbackChain, projectLlmRouter, coreGateway, objectMapper, caseJsonParser,
                selfCheckValidator, coverageChecker, priorityRule, actionJsonValidator,
                scriptLimitResolver, skillBridge, mcpBridge, mcpPool, reactRunner,
                fewshotBridge, null, null);
    }

 
    public AiGenServiceImpl(LlmFallbackChain llmFallbackChain, ProjectLlmRouter projectLlmRouter,
                            CoreGateway coreGateway,
                            ObjectMapper objectMapper, CaseJsonParser caseJsonParser,
                            SelfCheckValidator selfCheckValidator, CoverageChecker coverageChecker,
                            PriorityRule priorityRule, ActionJsonValidator actionJsonValidator,
                            ScriptLimitResolver scriptLimitResolver,
                            SkillAssemblyBridge skillBridge,
                            com.sixbean.testpike.aiservice.mcp.domain.component.McpKnowledgeBridge mcpBridge,
                            com.sixbean.testpike.aiservice.mcp.domain.component.McpClientPool mcpPool,
                            com.sixbean.testpike.aiservice.tool.domain.component.ReActRunner reactRunner,
                            com.sixbean.testpike.aiservice.knowledge.domain.component.StyleFewshotBridge fewshotBridge,
                            com.sixbean.testpike.aiservice.knowledge.domain.component.DdlDictBridge ddlBridge) {
        this(llmFallbackChain, projectLlmRouter, coreGateway, objectMapper, caseJsonParser,
                selfCheckValidator, coverageChecker, priorityRule, actionJsonValidator,
                scriptLimitResolver, skillBridge, mcpBridge, mcpPool, reactRunner,
                fewshotBridge, ddlBridge, null, null);
    }

 
    public AiGenServiceImpl(LlmFallbackChain llmFallbackChain, ProjectLlmRouter projectLlmRouter,
                            CoreGateway coreGateway,
                            ObjectMapper objectMapper, CaseJsonParser caseJsonParser,
                            SelfCheckValidator selfCheckValidator, CoverageChecker coverageChecker,
                            PriorityRule priorityRule, ActionJsonValidator actionJsonValidator,
                            ScriptLimitResolver scriptLimitResolver,
                            SkillAssemblyBridge skillBridge,
                            com.sixbean.testpike.aiservice.mcp.domain.component.McpKnowledgeBridge mcpBridge,
                            com.sixbean.testpike.aiservice.mcp.domain.component.McpClientPool mcpPool,
                            com.sixbean.testpike.aiservice.tool.domain.component.ReActRunner reactRunner,
                            com.sixbean.testpike.aiservice.knowledge.domain.component.StyleFewshotBridge fewshotBridge,
                            com.sixbean.testpike.aiservice.knowledge.domain.component.DdlDictBridge ddlBridge,
                            PromptTemplateResolver promptTemplateResolver) {
        this(llmFallbackChain, projectLlmRouter, coreGateway, objectMapper, caseJsonParser,
                selfCheckValidator, coverageChecker, priorityRule, actionJsonValidator,
                scriptLimitResolver, skillBridge, mcpBridge, mcpPool, reactRunner,
                fewshotBridge, ddlBridge, promptTemplateResolver, null, null);
    }

 
    public AiGenServiceImpl(LlmFallbackChain llmFallbackChain, ProjectLlmRouter projectLlmRouter,
                            CoreGateway coreGateway,
                            ObjectMapper objectMapper, CaseJsonParser caseJsonParser,
                            SelfCheckValidator selfCheckValidator, CoverageChecker coverageChecker,
                            PriorityRule priorityRule, ActionJsonValidator actionJsonValidator,
                            ScriptLimitResolver scriptLimitResolver,
                            SkillAssemblyBridge skillBridge,
                            com.sixbean.testpike.aiservice.mcp.domain.component.McpKnowledgeBridge mcpBridge,
                            com.sixbean.testpike.aiservice.mcp.domain.component.McpClientPool mcpPool,
                            com.sixbean.testpike.aiservice.tool.domain.component.ReActRunner reactRunner,
                            com.sixbean.testpike.aiservice.knowledge.domain.component.StyleFewshotBridge fewshotBridge,
                            com.sixbean.testpike.aiservice.knowledge.domain.component.DdlDictBridge ddlBridge,
                            PromptTemplateResolver promptTemplateResolver,
                            PromptWordingAdoption wordingAdoption) {
        this(llmFallbackChain, projectLlmRouter, coreGateway, objectMapper, caseJsonParser,
                selfCheckValidator, coverageChecker, priorityRule, actionJsonValidator,
                scriptLimitResolver, skillBridge, mcpBridge, mcpPool, reactRunner,
                fewshotBridge, ddlBridge, promptTemplateResolver, wordingAdoption, null, null);
    }

 
    public AiGenServiceImpl(LlmFallbackChain llmFallbackChain, ProjectLlmRouter projectLlmRouter,
                            CoreGateway coreGateway,
                            ObjectMapper objectMapper, CaseJsonParser caseJsonParser,
                            SelfCheckValidator selfCheckValidator, CoverageChecker coverageChecker,
                            PriorityRule priorityRule, ActionJsonValidator actionJsonValidator,
                            ScriptLimitResolver scriptLimitResolver,
                            SkillAssemblyBridge skillBridge,
                            com.sixbean.testpike.aiservice.mcp.domain.component.McpKnowledgeBridge mcpBridge,
                            com.sixbean.testpike.aiservice.mcp.domain.component.McpClientPool mcpPool,
                            com.sixbean.testpike.aiservice.tool.domain.component.ReActRunner reactRunner,
                            com.sixbean.testpike.aiservice.knowledge.domain.component.StyleFewshotBridge fewshotBridge,
                            com.sixbean.testpike.aiservice.knowledge.domain.component.DdlDictBridge ddlBridge,
                            PromptTemplateResolver promptTemplateResolver,
                            PromptWordingAdoption wordingAdoption,
                            PageInspectBridge pageInspectBridge) {
        this(llmFallbackChain, projectLlmRouter, coreGateway, objectMapper, caseJsonParser,
                selfCheckValidator, coverageChecker, priorityRule, actionJsonValidator,
                scriptLimitResolver, skillBridge, mcpBridge, mcpPool, reactRunner,
                fewshotBridge, ddlBridge, promptTemplateResolver, wordingAdoption,
                pageInspectBridge, null, null);
    }

 
    public AiGenServiceImpl(LlmFallbackChain llmFallbackChain, ProjectLlmRouter projectLlmRouter,
                            CoreGateway coreGateway,
                            ObjectMapper objectMapper, CaseJsonParser caseJsonParser,
                            SelfCheckValidator selfCheckValidator, CoverageChecker coverageChecker,
                            PriorityRule priorityRule, ActionJsonValidator actionJsonValidator,
                            ScriptLimitResolver scriptLimitResolver,
                            SkillAssemblyBridge skillBridge,
                            com.sixbean.testpike.aiservice.mcp.domain.component.McpKnowledgeBridge mcpBridge,
                            com.sixbean.testpike.aiservice.mcp.domain.component.McpClientPool mcpPool,
                            com.sixbean.testpike.aiservice.tool.domain.component.ReActRunner reactRunner,
                            com.sixbean.testpike.aiservice.knowledge.domain.component.StyleFewshotBridge fewshotBridge,
                            com.sixbean.testpike.aiservice.knowledge.domain.component.DdlDictBridge ddlBridge,
                            PromptTemplateResolver promptTemplateResolver,
                            PromptWordingAdoption wordingAdoption,
                            PageInspectBridge pageInspectBridge,
                            AppStepGenBridge appStepGenBridge) {
        this(llmFallbackChain, projectLlmRouter, coreGateway, objectMapper, caseJsonParser,
                selfCheckValidator, coverageChecker, priorityRule, actionJsonValidator,
                scriptLimitResolver, skillBridge, mcpBridge, mcpPool, reactRunner, fewshotBridge,
                ddlBridge, promptTemplateResolver, wordingAdoption, pageInspectBridge,
                appStepGenBridge, null);
    }

 
    public AiGenServiceImpl(LlmFallbackChain llmFallbackChain, ProjectLlmRouter projectLlmRouter,
                            CoreGateway coreGateway,
                            ObjectMapper objectMapper, CaseJsonParser caseJsonParser,
                            SelfCheckValidator selfCheckValidator, CoverageChecker coverageChecker,
                            PriorityRule priorityRule, ActionJsonValidator actionJsonValidator,
                            ScriptLimitResolver scriptLimitResolver,
                            SkillAssemblyBridge skillBridge,
                            com.sixbean.testpike.aiservice.mcp.domain.component.McpKnowledgeBridge mcpBridge,
                            com.sixbean.testpike.aiservice.mcp.domain.component.McpClientPool mcpPool,
                            com.sixbean.testpike.aiservice.tool.domain.component.ReActRunner reactRunner,
                            com.sixbean.testpike.aiservice.knowledge.domain.component.StyleFewshotBridge fewshotBridge,
                            com.sixbean.testpike.aiservice.knowledge.domain.component.DdlDictBridge ddlBridge,
                            PromptTemplateResolver promptTemplateResolver,
                            PromptWordingAdoption wordingAdoption,
                            PageInspectBridge pageInspectBridge,
                            AppStepGenBridge appStepGenBridge,
                            com.sixbean.testpike.aiservice.gen.domain.component
                                    .OriginalTextBlockAssembler originalTextBlockAssembler) {
        this(llmFallbackChain, projectLlmRouter, coreGateway, objectMapper, caseJsonParser,
                selfCheckValidator, coverageChecker, priorityRule, actionJsonValidator,
                scriptLimitResolver, skillBridge, mcpBridge, mcpPool, reactRunner, fewshotBridge,
                ddlBridge, promptTemplateResolver, wordingAdoption, pageInspectBridge,
                appStepGenBridge, originalTextBlockAssembler, null, null, null);
    }

    /**
 * assembly: adds the faithfulness checker
 * (null = legacy behaviour — the verifier skips the fidelity audit).
 */
    public AiGenServiceImpl(LlmFallbackChain llmFallbackChain, ProjectLlmRouter projectLlmRouter,
                            CoreGateway coreGateway,
                            ObjectMapper objectMapper, CaseJsonParser caseJsonParser,
                            SelfCheckValidator selfCheckValidator, CoverageChecker coverageChecker,
                            PriorityRule priorityRule, ActionJsonValidator actionJsonValidator,
                            ScriptLimitResolver scriptLimitResolver,
                            SkillAssemblyBridge skillBridge,
                            com.sixbean.testpike.aiservice.mcp.domain.component.McpKnowledgeBridge mcpBridge,
                            com.sixbean.testpike.aiservice.mcp.domain.component.McpClientPool mcpPool,
                            com.sixbean.testpike.aiservice.tool.domain.component.ReActRunner reactRunner,
                            com.sixbean.testpike.aiservice.knowledge.domain.component.StyleFewshotBridge fewshotBridge,
                            com.sixbean.testpike.aiservice.knowledge.domain.component.DdlDictBridge ddlBridge,
                            PromptTemplateResolver promptTemplateResolver,
                            PromptWordingAdoption wordingAdoption,
                            PageInspectBridge pageInspectBridge,
                            AppStepGenBridge appStepGenBridge,
                            com.sixbean.testpike.aiservice.gen.domain.component
                                    .OriginalTextBlockAssembler originalTextBlockAssembler,
                            com.sixbean.testpike.aiservice.gen.domain.component
                                    .FidelityGate fidelityGate,
                            com.sixbean.testpike.aiservice.gen.domain.component
                                    .ConfidenceMeasurer confidenceMeasurer,
                            com.sixbean.testpike.aiservice.gen.domain.component
                                    .ContractTruthSourceBridge contractBridge) {
        this(llmFallbackChain, projectLlmRouter, coreGateway, objectMapper, caseJsonParser,
                selfCheckValidator, coverageChecker, priorityRule, actionJsonValidator,
                scriptLimitResolver, skillBridge, mcpBridge, mcpPool, reactRunner, fewshotBridge,
                ddlBridge, promptTemplateResolver, wordingAdoption, pageInspectBridge,
                appStepGenBridge, originalTextBlockAssembler, fidelityGate, confidenceMeasurer,
                contractBridge, null, null);
    }

    /**
 * S3 Spring-preferred assembly: adds the prompt-budget fitting slot
 * (null = NONE identity fitting = byte-identical legacy sends). Both the
 * stage1 direct pass and every stage2 ring pass now ride the SAME
 * LoopContextBudgetGate seam the dialog loops use — new entry, gate
 * semantics untouched (DeliberationLoop / LoopContextBudgetGate zero edit).
 */
    public AiGenServiceImpl(LlmFallbackChain llmFallbackChain, ProjectLlmRouter projectLlmRouter,
                            CoreGateway coreGateway,
                            ObjectMapper objectMapper, CaseJsonParser caseJsonParser,
                            SelfCheckValidator selfCheckValidator, CoverageChecker coverageChecker,
                            PriorityRule priorityRule, ActionJsonValidator actionJsonValidator,
                            ScriptLimitResolver scriptLimitResolver,
                            SkillAssemblyBridge skillBridge,
                            com.sixbean.testpike.aiservice.mcp.domain.component.McpKnowledgeBridge mcpBridge,
                            com.sixbean.testpike.aiservice.mcp.domain.component.McpClientPool mcpPool,
                            com.sixbean.testpike.aiservice.tool.domain.component.ReActRunner reactRunner,
                            com.sixbean.testpike.aiservice.knowledge.domain.component.StyleFewshotBridge fewshotBridge,
                            com.sixbean.testpike.aiservice.knowledge.domain.component.DdlDictBridge ddlBridge,
                            PromptTemplateResolver promptTemplateResolver,
                            PromptWordingAdoption wordingAdoption,
                            PageInspectBridge pageInspectBridge,
                            AppStepGenBridge appStepGenBridge,
                            com.sixbean.testpike.aiservice.gen.domain.component
                                    .OriginalTextBlockAssembler originalTextBlockAssembler,
                            com.sixbean.testpike.aiservice.gen.domain.component
                                    .FidelityGate fidelityGate,
                            com.sixbean.testpike.aiservice.gen.domain.component
                                    .ConfidenceMeasurer confidenceMeasurer,
                            com.sixbean.testpike.aiservice.gen.domain.component
                                    .ContractTruthSourceBridge contractBridge,
                            com.sixbean.testpike.aiservice.llm.domain.component
                                    .LoopContextFitting loopFitting,
                            com.sixbean.testpike.aiservice.taskstream.domain.component
                                    .TaskProcessEventStore processEvents) {
        this(llmFallbackChain, projectLlmRouter, coreGateway, objectMapper, caseJsonParser,
                selfCheckValidator, coverageChecker, priorityRule, actionJsonValidator,
                scriptLimitResolver, skillBridge, mcpBridge, mcpPool, reactRunner,
                fewshotBridge, ddlBridge, promptTemplateResolver, wordingAdoption,
                pageInspectBridge, appStepGenBridge, originalTextBlockAssembler,
                fidelityGate, confidenceMeasurer, contractBridge, loopFitting, processEvents,
                null);
    }

    /**
     * WE batch piece C: Spring-preferred assembly adds the page-element
     * summary bridge slot (null = zero injection = byte-identical legacy
     * stage-2 prompt). The bridge itself owns the K8 gate, the row/char
     * budgets and the fail-soft read — see WebElementLedgerSummaryBridge.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public AiGenServiceImpl(LlmFallbackChain llmFallbackChain, ProjectLlmRouter projectLlmRouter,
                            CoreGateway coreGateway,
                            ObjectMapper objectMapper, CaseJsonParser caseJsonParser,
                            SelfCheckValidator selfCheckValidator, CoverageChecker coverageChecker,
                            PriorityRule priorityRule, ActionJsonValidator actionJsonValidator,
                            ScriptLimitResolver scriptLimitResolver,
                            SkillAssemblyBridge skillBridge,
                            com.sixbean.testpike.aiservice.mcp.domain.component.McpKnowledgeBridge mcpBridge,
                            com.sixbean.testpike.aiservice.mcp.domain.component.McpClientPool mcpPool,
                            com.sixbean.testpike.aiservice.tool.domain.component.ReActRunner reactRunner,
                            com.sixbean.testpike.aiservice.knowledge.domain.component.StyleFewshotBridge fewshotBridge,
                            com.sixbean.testpike.aiservice.knowledge.domain.component.DdlDictBridge ddlBridge,
                            PromptTemplateResolver promptTemplateResolver,
                            PromptWordingAdoption wordingAdoption,
                            PageInspectBridge pageInspectBridge,
                            AppStepGenBridge appStepGenBridge,
                            com.sixbean.testpike.aiservice.gen.domain.component
                                    .OriginalTextBlockAssembler originalTextBlockAssembler,
                            com.sixbean.testpike.aiservice.gen.domain.component
                                    .FidelityGate fidelityGate,
                            com.sixbean.testpike.aiservice.gen.domain.component
                                    .ConfidenceMeasurer confidenceMeasurer,
                            com.sixbean.testpike.aiservice.gen.domain.component
                                    .ContractTruthSourceBridge contractBridge,
                            com.sixbean.testpike.aiservice.llm.domain.component
                                    .LoopContextFitting loopFitting,
                            com.sixbean.testpike.aiservice.taskstream.domain.component
                                    .TaskProcessEventStore processEvents,
                            WebElementLedgerSummaryBridge ledgerSummaryBridge,
                            com.sixbean.testpike.aiservice.llm.domain.component
                                    .LlmOutputCapabilityLedger capabilityLedger,
                            RuleLedgerReconciler ruleLedgerReconciler) {
        this.llmFallbackChain = llmFallbackChain;
        this.projectLlmRouter = projectLlmRouter;
        this.coreGateway = coreGateway;
        this.objectMapper = objectMapper;
        this.caseJsonParser = caseJsonParser;
        this.selfCheckValidator = selfCheckValidator;
        this.coverageChecker = coverageChecker;
        this.priorityRule = priorityRule;
        this.actionJsonValidator = actionJsonValidator;
        this.scriptLimitResolver = scriptLimitResolver;
        this.skillBridge = skillBridge;
        this.mcpBridge = mcpBridge;
        this.mcpPool = mcpPool;
        this.reactRunner = reactRunner;
        this.fewshotBridge = fewshotBridge;
        this.ddlBridge = ddlBridge;
        this.promptTemplateResolver = promptTemplateResolver;
        this.wordingAdoption = wordingAdoption;
        this.pageInspectBridge = pageInspectBridge;
        this.appStepGenBridge = appStepGenBridge;
        this.originalTextBlockAssembler = originalTextBlockAssembler;
        this.fidelityGate = fidelityGate;
        this.confidenceMeasurer = confidenceMeasurer;
        this.contractBridge = contractBridge;
        this.loopFitting = loopFitting == null
                ? com.sixbean.testpike.aiservice.llm.domain.component.LoopContextFitting.NONE
                : loopFitting;
        this.processEvents = processEvents;
        this.ledgerSummaryBridge = ledgerSummaryBridge;
        this.capabilityLedger = capabilityLedger;
        this.ruleLedgerReconciler = ruleLedgerReconciler;
    }

    /** Pre-ledger full assembly (compat): Spring never picks it — the
     *  @Autowired constructor above carries the capability-ledger slot.
     *  Delegating ladders and wiring suites keep the zero-ledger shape =
     *  legacy form decisions byte-for-byte. */
    public AiGenServiceImpl(LlmFallbackChain llmFallbackChain, ProjectLlmRouter projectLlmRouter,
                            CoreGateway coreGateway,
                            ObjectMapper objectMapper, CaseJsonParser caseJsonParser,
                            SelfCheckValidator selfCheckValidator, CoverageChecker coverageChecker,
                            PriorityRule priorityRule, ActionJsonValidator actionJsonValidator,
                            ScriptLimitResolver scriptLimitResolver,
                            SkillAssemblyBridge skillBridge,
                            com.sixbean.testpike.aiservice.mcp.domain.component.McpKnowledgeBridge mcpBridge,
                            com.sixbean.testpike.aiservice.mcp.domain.component.McpClientPool mcpPool,
                            com.sixbean.testpike.aiservice.tool.domain.component.ReActRunner reactRunner,
                            com.sixbean.testpike.aiservice.knowledge.domain.component.StyleFewshotBridge fewshotBridge,
                            com.sixbean.testpike.aiservice.knowledge.domain.component.DdlDictBridge ddlBridge,
                            PromptTemplateResolver promptTemplateResolver,
                            PromptWordingAdoption wordingAdoption,
                            PageInspectBridge pageInspectBridge,
                            AppStepGenBridge appStepGenBridge,
                            com.sixbean.testpike.aiservice.gen.domain.component
                                    .OriginalTextBlockAssembler originalTextBlockAssembler,
                            com.sixbean.testpike.aiservice.gen.domain.component
                                    .FidelityGate fidelityGate,
                            com.sixbean.testpike.aiservice.gen.domain.component
                                    .ConfidenceMeasurer confidenceMeasurer,
                            com.sixbean.testpike.aiservice.gen.domain.component
                                    .ContractTruthSourceBridge contractBridge,
                            com.sixbean.testpike.aiservice.llm.domain.component
                                    .LoopContextFitting loopFitting,
                            com.sixbean.testpike.aiservice.taskstream.domain.component
                                    .TaskProcessEventStore processEvents,
                            WebElementLedgerSummaryBridge ledgerSummaryBridge) {
        this(llmFallbackChain, projectLlmRouter, coreGateway, objectMapper, caseJsonParser,
                selfCheckValidator, coverageChecker, priorityRule, actionJsonValidator,
                scriptLimitResolver, skillBridge, mcpBridge, mcpPool, reactRunner,
                fewshotBridge, ddlBridge, promptTemplateResolver, wordingAdoption,
                pageInspectBridge, appStepGenBridge, originalTextBlockAssembler,
                fidelityGate, confidenceMeasurer, contractBridge, loopFitting, processEvents,
                ledgerSummaryBridge, null, null);
    }

    /**
 * segment B-2 (compat shape for wiring suites): the rule-ledger
 * reconciler slot without the capability ledger. The hot switch still
 * fails closed to off, so every unwired or switched-off assembly keeps
 * the exact legacy stage-1 judgement bytes with zero extra chunk pulls.
 */
    public AiGenServiceImpl(LlmFallbackChain llmFallbackChain, ProjectLlmRouter projectLlmRouter,
                            CoreGateway coreGateway,
                            ObjectMapper objectMapper, CaseJsonParser caseJsonParser,
                            SelfCheckValidator selfCheckValidator, CoverageChecker coverageChecker,
                            PriorityRule priorityRule, ActionJsonValidator actionJsonValidator,
                            ScriptLimitResolver scriptLimitResolver,
                            SkillAssemblyBridge skillBridge,
                            com.sixbean.testpike.aiservice.mcp.domain.component.McpKnowledgeBridge mcpBridge,
                            com.sixbean.testpike.aiservice.mcp.domain.component.McpClientPool mcpPool,
                            com.sixbean.testpike.aiservice.tool.domain.component.ReActRunner reactRunner,
                            com.sixbean.testpike.aiservice.knowledge.domain.component.StyleFewshotBridge fewshotBridge,
                            com.sixbean.testpike.aiservice.knowledge.domain.component.DdlDictBridge ddlBridge,
                            PromptTemplateResolver promptTemplateResolver,
                            PromptWordingAdoption wordingAdoption,
                            PageInspectBridge pageInspectBridge,
                            AppStepGenBridge appStepGenBridge,
                            com.sixbean.testpike.aiservice.gen.domain.component
                                    .OriginalTextBlockAssembler originalTextBlockAssembler,
                            com.sixbean.testpike.aiservice.gen.domain.component
                                    .FidelityGate fidelityGate,
                            com.sixbean.testpike.aiservice.gen.domain.component
                                    .ConfidenceMeasurer confidenceMeasurer,
                            com.sixbean.testpike.aiservice.gen.domain.component
                                    .ContractTruthSourceBridge contractBridge,
                            com.sixbean.testpike.aiservice.llm.domain.component
                                    .LoopContextFitting loopFitting,
                            com.sixbean.testpike.aiservice.taskstream.domain.component
                                    .TaskProcessEventStore processEvents,
                            WebElementLedgerSummaryBridge ledgerSummaryBridge,
                            RuleLedgerReconciler ruleLedgerReconciler) {
        this(llmFallbackChain, projectLlmRouter, coreGateway, objectMapper, caseJsonParser,
                selfCheckValidator, coverageChecker, priorityRule, actionJsonValidator,
                scriptLimitResolver, skillBridge, mcpBridge, mcpPool, reactRunner,
                fewshotBridge, ddlBridge, promptTemplateResolver, wordingAdoption,
                pageInspectBridge, appStepGenBridge, originalTextBlockAssembler,
                fidelityGate, confidenceMeasurer, contractBridge, loopFitting, processEvents,
                ledgerSummaryBridge, null, ruleLedgerReconciler);
    }

    @Override
    public void generate(AiGenMessage message) {
        long taskId = message.taskId();
        long projectId = message.projectId();
        long operatorId = message.operatorId();
        coreGateway.markRunning(taskId);
        LlmEndpointConfig endpoint = projectLlmRouter.route(projectId);
        coreGateway.markModel(taskId, endpoint.modelName());
        log.info("AI 生成任务开始 taskId={} projectId={} taskType={} model={}",
                taskId, projectId, message.taskType(), endpoint.modelName());

        if (message.taskType() == 2) {
            generateScriptOnly(message, endpoint,
                    new AtomicReference<>(endpoint.modelName()));
            return;
        }

        try {
            CoreGateway.ProjectBrief project = coreGateway.getProject(projectId);
            String lang = project == null || project.defaultLang() == null ? "zh-CN" : project.defaultLang();
            CoreGateway.RequirementBrief requirement = message.requirementId() == null
                    ? null : coreGateway.getRequirement(message.requirementId());
            String acceptanceCriteria = requirement == null ? null : requirement.acceptanceCriteria();
            // (design 3.2): anchored points pull their source chunk
            // (±1 neighbours) as an isolated original-text block; every failure
            // path (no anchor / core unreachable / empty pull) yields "" and the
            // prompt stays byte-for-byte the legacy shape.
            String originalSegment = originalSegmentFor(requirement);

            AtomicReference<String> markedModel = new AtomicReference<>(endpoint.modelName());
            // the measured composite rides out of stage1
            // through this holder (null = self-report kept, legacy depth).
            AtomicReference<com.sixbean.testpike.aiservice.gen.domain.component.ConfidenceMeasure>
                    measuredConfidence = new AtomicReference<>();
            GeneratedCase generated = stage1(message.requirementText(), lang, acceptanceCriteria,
                    message, endpoint, markedModel, originalSegment, requirement,
                    measuredConfidence);
            String caseTitle = generated.title();

            long caseId = persistCase(message, generated, requirement);
            log.info("AI 生成用例落库 taskId={} caseId={} caseNo=TC-{}", taskId, caseId, caseTitle);

            if (message.requirementId() != null) {
                coreGateway.linkRequirement(message.requirementId(), List.of(caseId), operatorId);
            }

            // a measured level-3 confidence auto-limits the
            // depth — the script pass is skipped, the WARN and the notification
            // body both say honestly that no script was produced (the manual
            // regeneration path taskType=2 stays untouched).
            com.sixbean.testpike.aiservice.gen.domain.component.ConfidenceMeasure measured =
                    measuredConfidence.get();
            if (measured != null && measured.low()) {
                log.warn("低置信自动限深 taskId={} caseId={} 实测置信=3，阶段 2 未执行；"
                        + "可在用例上重新生成脚本", taskId, caseId);
                coreGateway.markDone(taskId, caseId);
                coreGateway.createNotification(operatorId, projectId, "AI_GEN_DONE",
                        "AI 用例生成完成",
                        "需求「" + truncate(caseTitle, 40) + "」已生成用例；脚本未生成（置信度实测偏低），可重新生成脚本",
                        "ai-gen-done:" + taskId);
                log.info("AI 生成任务完成（自动限深） taskId={} caseId={}", taskId, caseId);
                return;
            }
            generateAndSaveScript(message, caseId, generated, endpoint, markedModel,
                    originalSegment);

            coreGateway.markDone(taskId, caseId);
            coreGateway.createNotification(operatorId, projectId, "AI_GEN_DONE",
                    "AI 用例生成完成",
                    "需求「" + truncate(caseTitle, 40) + "」已生成用例并生成脚本，等待评审",
                    "ai-gen-done:" + taskId);
            log.info("AI 生成任务完成 taskId={} caseId={}", taskId, caseId);
        } catch (ServiceException e) {
            handleFailure(taskId, projectId, operatorId, message.requirementText(), e);
            throw e;
        } catch (Exception e) {
            handleFailure(taskId, projectId, operatorId, message.requirementText(), e);
            throw new ServiceException("AI 生成服务异常: " + e.getMessage(), BizErrorCode.AI_FORMAT_ERROR);
        }
    }

    
    private void generateScriptOnly(AiGenMessage message, LlmEndpointConfig endpoint,
                                    AtomicReference<String> markedModel) {
        long taskId = message.taskId();
        long projectId = message.projectId();
        long operatorId = message.operatorId();
        Long caseId = message.caseId();
        try {
            Map<String, Object> caseInfo = coreGateway.getCase(caseId);
            GeneratedCase generated = generatedFromCaseInfo(caseInfo);
            String title = generated.title();
            // (A1): the regenerate path re-pulls the requirement's
            // frozen original block through the SAME stage-1 assembly door
            // (no second assembly, R40); no requirement id / no anchor = "" =
            // legacy bytes unchanged.
            CoreGateway.RequirementBrief requirement = message.requirementId() == null
                    ? null : coreGateway.getRequirement(message.requirementId());
            generateAndSaveScript(message, caseId, generated, endpoint, markedModel,
                    originalSegmentFor(requirement));
            coreGateway.markDone(taskId, caseId);
            coreGateway.createNotification(operatorId, projectId, "SCRIPT_DONE",
                    "脚本重新生成完成",
                    "用例「" + truncate(title, 40) + "」脚本已重新生成",
                    "script-done:" + taskId);
            log.info("AI 脚本重生成完成 taskId={} caseId={}", taskId, caseId);
        } catch (ServiceException e) {
            handleFailure(taskId, projectId, operatorId, "脚本重新生成", e);
            throw e;
        } catch (Exception e) {
            handleFailure(taskId, projectId, operatorId, "脚本重新生成", e);
            throw new ServiceException("AI 脚本生成服务异常: " + e.getMessage(), BizErrorCode.AI_FORMAT_ERROR);
        }
    }

    /**
     * Maps the stored case row returned by the core getCase door into the
     * stage-2 input shape (regenerate path, taskType=2). Faithful fields:
     * precondition, expected result, step lines and the stored end type
     * ride the generation prompt instead of the former title-only stub, so
     * an App-target case no longer reads as "web" on this path. Fail-soft
     * by design — a null row or missing keys yield the legacy empty shape
     * (title fallback, blank body, web end type) and the generation chain
     * is never blocked by the read door.
     */
    static GeneratedCase generatedFromCaseInfo(Map<String, Object> caseInfo) {
        if (caseInfo == null) {
            return new GeneratedCase("用例", "", "", List.of(), 1, 2, "web");
        }
        List<GeneratedStep> steps = new ArrayList<>();
        if (caseInfo.get("steps") instanceof List<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> step
                        && step.get("description") != null
                        && !String.valueOf(step.get("description")).isBlank()) {
                    steps.add(new GeneratedStep(
                            step.get("stepNo") instanceof Number n ? n.intValue() : steps.size() + 1,
                            String.valueOf(step.get("description")),
                            step.get("isAssert") instanceof Number n ? n.intValue() : 0));
                }
            }
        }
        return new GeneratedCase(
                textFromRowValue(caseInfo.get("title"), "用例"),
                textFromRowValue(caseInfo.get("precondition"), ""),
                textFromRowValue(caseInfo.get("expectedResult"), ""),
                steps,
                caseInfo.get("priority") instanceof Number n ? n.intValue() : 1,
                caseInfo.get("confidence") instanceof Number n ? n.intValue() : 2,
                textFromRowValue(caseInfo.get("endType"), "web"));
    }

    private static String textFromRowValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    /**
     * Stage-2 fold-back depth reuses the repair round cap key ai.repair.rounds
     * (single authority for how many repair rounds a case gets; same K8 value
     * the repair loop gates on). Missing or illegal raw values fall back to 3,
     * clamped to 0..9 — 0 disables the segment and skips the door read.
     */
    static int repairFoldCap(String raw) {
        if (raw == null || raw.isBlank()) {
            return 3;
        }
        try {
            return Math.max(0, Math.min(9, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException e) {
            return 3;
        }
    }

    /**
     * Failed repair notes of one case, newest first (the ledger door returns
     * id DESC), capped by the fold depth. Only outcome=2 rows with a non-blank
     * note fold in — passing rows, gate-skipped rows and blank notes carry no
     * honest failure text and are dropped.
     */
    static List<String> priorRepairFailures(List<Map<String, Object>> rows, int cap) {
        if (rows == null || rows.isEmpty() || cap <= 0) {
            return List.of();
        }
        List<String> notes = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (notes.size() >= cap) {
                break;
            }
            if (row == null || !(row.get("outcome") instanceof Number n) || n.intValue() != 2) {
                continue;
            }
            Object note = row.get("failNote");
            String text = note == null ? "" : String.valueOf(note).trim();
            if (text.isEmpty()) {
                continue;
            }
            notes.add(text.length() > 200 ? text.substring(0, 200) : text);
        }
        return notes;
    }

    /**
 * Project resource-pool brief for the script prompt ( S2 segment B,
 * context contract item 4; relevance selection slices a/b):
 * environments / data factories / API collections the generated steps may
 * legitimately reference, read once per task over EXISTING core internal
 * doors (zero new endpoints, zero second data-access path).
 * <p> changes WHICH entries ride the prompt, never how they render:
 * factory and collection rows are ranked by case-text token match plus a
 * usage hint (miss / wrong-reference risk state 3 of the ruling), ties
 * break on the newest stored time, an all-zero-score category keeps its
 * original order, and every environment row stays exempt from the cap —
 * a missing base URL invalidates every step. The named-entry cap rides
 * the hot key {@code ai.gen.resource-pool.segment-cap} (window 10-100,
 * fail-closed to {@code RESOURCE_SEGMENT_CAP}); a blank case text and the
 * default cap keep the legacy prompt bytes. Any door failure or empty
 * pool folds to the empty string — the generation chain is never blocked
 * by the pool read.
 */
    /**
 * segment c tail line: teaches the model the optional output key
 * {@code missingResources[]} so a wanted-but-absent pool category can be
 * declared honestly (risk state 3: silent under-reference no existing gate
 * catches). Live window 2026-11-12 (task 2895) proved the optional phrasing
 * is too weak — the model invented an off-list URL instead — so the tail
 * now DIRECTS the declaration and FORBIDS off-list invention, while the
 * key stays optional in the payload contract: non-declaration keeps the
 * legacy shape and DSL validation is not tightened. The declaration rides
 * the process-event ledger (detail stream) after {@link
 * com.sixbean.testpike.aiservice.gen.domain.component.ResourceGapDeclaration}
 * strips it from the persisted payload. Second live proof (tasks 2898/2899,
 * still produced silence plus off-topic placeholder steps — the tail
 * therefore drops the contradiction, shows one declaration example and
 * bans placeholder replies explicitly.
 */
    private static final String GAP_DECLARATION_GUIDANCE =
            "如步骤需要引用的资源类目（环境/数据工厂/接口集）不在上述清单内，必须在输出 JSON"
                    + " 顶层键 missingResources[] 中申报，每项含 category（类目）与 intent（用途），"
                    + "申报形态示例 {\"missingResources\": [{\"category\": \"第三方支付沙箱\", \"intent\": \"模拟支付回调\"}]}；"
                    + "禁止编造清单外的地址或条目名称继续生成，也禁止改输出与用例无关的占位步骤。"
                    + "不申报不影响本次生成（申报为提示，供人工补录后一键再生成）。\n";

    static String projectResourceSegment(CoreGateway coreGateway, long projectId) {
        return projectResourceSegment(coreGateway, projectId, "");
    }

    /** Case-text-aware form : computed ONCE per task before the ring. */
    static String projectResourceSegment(CoreGateway coreGateway, long projectId,
                                         String caseText) {
        return projectResourceSegment(coreGateway, projectId, caseText, 0L);
    }

    /**
 * Operator-aware form : the task initiator from the
 * MQ message rides X-Operator-Id through both list doors, so core never
 * sees an anonymous internal read. 0 = system (tests / headerless pulls).
 */
    static String projectResourceSegment(CoreGateway coreGateway, long projectId,
                                         String caseText, long operatorId) {
        try {
            List<String> envLines = new ArrayList<>();
            for (CoreGateway.EnvBrief env : coreGateway.listProjectEnvs(projectId)) {
                String url = env == null || env.baseUrl() == null
                        ? "" : String.valueOf(env.baseUrl()).trim();
                if (!url.isEmpty()) {
                    envLines.add("环境（envType=" + env.envType() + "）：" + url);
                }
            }
            List<Map<String, Object>> rankedFactories = rankRowsByCaseRelevance(caseText,
                    coreGateway.listProjectDataFactories(projectId, operatorId));
            List<Map<String, Object>> rankedCollections = rankRowsByCaseRelevance(caseText,
                    coreGateway.listProjectApiCollections(projectId, operatorId));
            List<String> poolLines = mergePoolLines(caseText, rankedFactories, rankedCollections);
            int cap = resourceSegmentCap(
                    coreGateway.getSysConfig("ai.gen.resource-pool.segment-cap"));
            List<String> lines = new ArrayList<>(envLines);
            lines.addAll(poolLines.subList(0, Math.min(poolLines.size(), cap)));
            if (poolLines.size() > cap) {
                lines.add("（其余 " + (poolLines.size() - cap) + " 项未列出）");
            }
            if (lines.isEmpty()) {
                return "";
            }
            return com.sixbean.testpike.aiservice.llm.domain.component
                    .RegenerationFeedback.render(lines,
                            "可引用项目资源（步骤引用的环境地址/数据工厂/接口集必须来自以下已存在条目，"
                                    + "引用不存在的名称会被执行前校验拒绝）：")
                    + GAP_DECLARATION_GUIDANCE;
        } catch (Exception e) {
            log.warn("脚本生成项目资源池摘要读取失败 projectId={}（fail-soft 零注入）",
                    projectId, e);
            return "";
        }
    }

    /**
 * Full pool rows for the slice-c mechanical gap audit :
 * env URLs + data factories + API collections, UN-capped (a reference
 * may legitimately ride a row outside the prompt window) and re-read
 * through the same EXISTING doors only. Env rows enter as name=URL maps
 * so the shared scoring face can cover them exactly like pool names.
 * Any door failure folds to the empty list — an unreadable pool makes
 * the audit indeterminate, and an indeterminate audit declares nothing.
 */
    static List<Map<String, Object>> loadPoolRows(CoreGateway coreGateway, long projectId,
                                                  long operatorId) {
        try {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (CoreGateway.EnvBrief env : coreGateway.listProjectEnvs(projectId)) {
                String url = env == null || env.baseUrl() == null
                        ? "" : String.valueOf(env.baseUrl()).trim();
                if (!url.isEmpty()) {
                    Map<String, Object> envRow = new java.util.LinkedHashMap<>();
                    envRow.put("name", url);
                    envRow.put("description", env.envType() == null ? "" : env.envType());
                    rows.add(envRow);
                }
            }
            rows.addAll(coreGateway.listProjectDataFactories(projectId, operatorId));
            rows.addAll(coreGateway.listProjectApiCollections(projectId, operatorId));
            return rows;
        } catch (Exception e) {
            log.warn("阶段 2 缺资源核对池读取失败 projectId={}（判定不确定=零申报）",
                    projectId, e);
            return List.of();
        }
    }

    /**
 * Relevance ranking of one pool category against the case text (
 * segment a single source — never re-sort downstream of prompt assembly).
 * Pure in-memory: zero LLM calls, zero new cache surface, zero IO.
 * Score = distinct name/tag tokens found in the case text (+2 when the
 * whole stored name appears verbatim — a direct mention is the strongest
 * signal) + 1 usage hint when the row carries a positive requestCount.
 * Ordering contract: score descending; a score tie between RELEVANT rows
 * breaks on the newest stored time (updatedAt, else createdAt; rows
 * without any time keep their relative order); rows that score zero on
 * every match fall back to the original storage order — never gamble on
 * relevance, never hand out an empty head.
 */
    static List<Map<String, Object>> rankRowsByCaseRelevance(String caseText,
                                                             List<Map<String, Object>> rows) {
        if (rows == null || rows.size() < 2) {
            return rows == null ? List.of() : rows;
        }
        String text = caseText == null ? "" : caseText.toLowerCase(java.util.Locale.ROOT);
        int n = rows.size();
        int[] score = new int[n];
        String[] time = new String[n];
        boolean anyScore = false;
        for (int i = 0; i < n; i++) {
            Map<String, Object> row = rows.get(i);
            int s = PoolRowRelevanceScoring.rowRelevanceScore(text, row);
            score[i] = s;
            anyScore |= s > 0;
            String t = PoolRowRelevanceScoring.rowText(row, "updatedAt");
            time[i] = t.isEmpty() ? PoolRowRelevanceScoring.rowText(row, "createdAt") : t;
        }
        if (!anyScore) {
            return rows;
        }
        List<Integer> idx = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            idx.add(i);
        }
        idx.sort((a, b) -> {
            if (score[a] != score[b]) {
                return Integer.compare(score[b], score[a]);
            }
            if (score[a] == 0) {
                return Integer.compare(a, b);
            }
            int byTime = time[b].compareTo(time[a]);
            return byTime != 0 ? byTime : Integer.compare(a, b);
        });
        List<Map<String, Object>> out = new ArrayList<>(n);
        for (int i : idx) {
            out.add(rows.get(i));
        }
        return out;
    }

    /**
 * Cross-category merge of the two ranked lists into one labelled line
 * list ordered by relevance (: a relevant collection must not be
 * pushed out of the cap by a wall of irrelevant factories). Scores are
 * non-increasing inside each ranked category, so a head-to-head merge
 * keeps that shape; an equal head score keeps factories first, which
 * reproduces the legacy storage-order bytes exactly for an all-zero pool.
 */
    private static List<String> mergePoolLines(String caseText,
                                               List<Map<String, Object>> factories,
                                               List<Map<String, Object>> collections) {
        String text = caseText == null ? "" : caseText.toLowerCase(java.util.Locale.ROOT);
        List<String> out = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < factories.size() || j < collections.size()) {
            int fs = i < factories.size()
                    ? PoolRowRelevanceScoring.rowRelevanceScore(text, factories.get(i)) : -1;
            int cs = j < collections.size()
                    ? PoolRowRelevanceScoring.rowRelevanceScore(text, collections.get(j)) : -1;
            if (fs >= cs) {
                appendRowName(factories.get(i++), "数据工厂", out);
            } else {
                appendRowName(collections.get(j++), "接口集", out);
            }
        }
        return out;
    }

    /**
 * Named-cap three states ( segment b, fail-closed reader of the hot key
 * {@code ai.gen.resource-pool.segment-cap}): null/blank/garbage or any
 * value outside the 10-100 window falls back to the factory default —
 * neither a silent prompt shrink nor a silent prompt growth. The core
 * write side validates the same window at save time.
 */
    static int resourceSegmentCap(String raw) {
        if (raw == null || raw.isBlank()) {
            return RESOURCE_SEGMENT_CAP;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            return v < 10 || v > 100 ? RESOURCE_SEGMENT_CAP : v;
        } catch (NumberFormatException e) {
            return RESOURCE_SEGMENT_CAP;
        }
    }

    /** Non-blank row names of one pool category, labelled and appended (segment B helper). */
    private static void collectRowNames(List<Map<String, Object>> rows, String label,
                                        List<String> out) {
        for (Map<String, Object> row : rows == null ? List.<Map<String, Object>>of() : rows) {
            appendRowName(row, label, out);
        }
    }

    /** One labelled line per row with a non-blank name (blank names never ride the prompt). */
    private static void appendRowName(Map<String, Object> row, String label, List<String> out) {
        Object name = row == null ? null : row.get("name");
        String text = name == null ? "" : String.valueOf(name).trim();
        if (!text.isEmpty()) {
            out.add(label + "：" + text);
        }
    }

    /**
 * Factory default of the named resource-entry cap (hot key
 * {@code ai.gen.resource-pool.segment-cap}, window 10-100; segment b
 * kept the value, only the form became configurable).
 */
    static final int RESOURCE_SEGMENT_CAP = 30;

    /**
     * Prior-failure segment for the script prompt (RegenerationFeedback
     * single-source rendering): the case ledger is read once per task over
     * the existing door, fail-soft to the empty string on any read failure or
     * empty history — the generation chain is never blocked by the history
     * read, and a first attempt keeps its legacy prompt bytes.
     */
    static String priorRepairFeedbackSegment(CoreGateway coreGateway, long caseId) {
        try {
            int cap = repairFoldCap(coreGateway.getSysConfig("ai.repair.rounds"));
            if (cap <= 0) {
                return "";
            }
            return com.sixbean.testpike.aiservice.llm.domain.component
                    .RegenerationFeedback.render(
                            priorRepairFailures(coreGateway.listCaseRepairs(caseId, cap), cap),
                            "该用例既往修复失败（逐条避免重演）：");
        } catch (Exception e) {
            log.warn("脚本生成既往修复回灌读取失败 caseId={}（fail-soft 零注入）", caseId, e);
            return "";
        }
    }

    
    private GeneratedCase stage1(String requirementText, String lang, String acceptanceCriteria,
                                 AiGenMessage message, LlmEndpointConfig primary,
                                 AtomicReference<String> markedModel, String originalSegment,
                                 CoreGateway.RequirementBrief requirement,
                                 AtomicReference<com.sixbean.testpike.aiservice.gen.domain.component
                                         .ConfidenceMeasure> measuredConfidenceOut) {
        long taskId = message.taskId();
        LlmFallbackNotice notice = new LlmFallbackNotice(message.operatorId(),
                message.projectId(), "AI 生成任务#" + taskId);

        com.sixbean.testpike.aiservice.mcp.domain.component.McpTaskToolBag bag =
                reactRunner == null || mcpPool == null ? null : mcpPool.openTask(taskId, message.projectId());
        try {
            String knowledge = mcpBridge == null ? ""
                    : (bag != null ? mcpBridge.knowledgeSegment(bag, requirementText)
                            : mcpBridge.knowledgeSegment(taskId, message.projectId(), requirementText));
            String fewshot = fewshotBridge == null ? ""
                    : fewshotBridge.fewshotSegment(taskId, message.projectId(), requirementText,
                            primary);
            String wording = wordingAdoption == null ? ""
                    : wordingAdoption.wordingSegment(message.projectId());
            // contract truth-source brief — one computation
            // per task reused by every retry round (fewshot/wording same
            // discipline); null bridge / gate off / no contracts / read failure
            // = empty segment = the legacy prompt bytes.
            String contract = contractBridge == null ? ""
                    : contractBridge.contractSegment(message.projectId(), primary);
            // AP-6 prefix stability (message-order contract): the SKILL section for
            // CASE_GEN is computed once per task and reused by every retry round —
            // same discipline as the knowledge/few-shot/wording segments. Re-fetching
            // and re-splicing per attempt drifts the system prefix and zeroes out
            // provider-side prompt caching; per-turn variables stay in the user part.
            String stage1System = stage1DirectivesHead(lang);
            if (skillBridge != null) {
                stage1System = skillBridge.composeSystem(stage1System, SkillStage.CASE_GEN,
                        message.projectId(), taskId, primary);
            }
            final String frozenStage1System = stage1System;
            // : the stage1 main judgement runs through the shared kernel
            // deliberation ring — propose over the requirement, verify claim by
            // claim (parser + A6 + A5 + evidence anchor, all existing rule
            // sources, never a second rule), revise only the named disputes.
            // Enforcement notice holders: mount state is recorded by askStage1,
            // the first shape-class verdict on a mounted block proves the
            // endpoint accepted response_format without enforcing it (live
            // probe finding — a silent class that would otherwise read as
            // SUPPORT in the L3 window).
            final boolean[] stage1StructuredMounted = new boolean[1];
            final boolean[] stage1NoticeDone = new boolean[1];
            // Form the mounted block actually rode (V615 ledger key part):
            // an accept-but-not-enforce observation must be attributed to the
            // wire form that carried it, not guessed from the row flag.
            final String[] stage1MountedForm = new String[1];
            // regeneration rounds the fidelity disputes have
            // already driven this task (budget = hot K8 key, read per verifier
            // pass; the holder lives per task so a new generation gets a full
            // budget).
            final int[] fidelityRecheckUsed = {0};
            // signal 1 of the measured confidence rides out of
            // the verifier through this holder (NaN = the gate never ran).
            final double[] fidelityRatioHolder = {Double.NaN};
            com.sixbean.testpike.aiservice.llm.pipeline.DeliberationOutcome ring =
                    com.sixbean.testpike.aiservice.llm.pipeline.DeliberationLoop.deliberate(
                            requirementText,
                            stage1Evidence(requirementText, acceptanceCriteria, knowledge),
                            coreGateway.getSysConfig(com.sixbean.testpike.aiservice.llm.pipeline
                                    .DeliberationLoop.GOVERNANCE_KEY),
                            SELF_CHECK_RETRY + 1,
                            brief -> askStage1(requirementText, lang, acceptanceCriteria, knowledge,
                                    fewshot, wording, contract, originalSegment, message, primary, notice,
                                    markedModel, bag, frozenStage1System, List.of(),
                                    stage1StructuredMounted, stage1MountedForm),
                            (draft, ev) -> {
                                List<String> disputes = verdictOnStage1Draft(draft, ev,
                                        acceptanceCriteria, originalSegment, requirement,
                                        fidelityRecheckUsed, fidelityRatioHolder, taskId,
                                        message.projectId());
                                if (!stage1NoticeDone[0] && stage1StructuredMounted[0]
                                        && GenStructuredOutput.hasFormatResidual(disputes)) {
                                    stage1NoticeDone[0] = true;
                                    log.warn("结构化输出已挂载但端点未强制（格式类分歧残余）"
                                                    + " taskId={} model={} 分歧={}",
                                            taskId, primary.modelName(), disputes);
                                    // Capability-row correction at the moment of
                                    // proof (machine gate): the mounted form of
                                    // this model is recorded as accepted-without-
                                    // enforced, so later schema-bearing calls of
                                    // the same model steer into the enforceable
                                    // form instead of burning the ring again.
                                    if (capabilityLedger != null) {
                                        capabilityLedger.observeNotEnforced(primary.modelName(),
                                                stage1MountedForm[0] != null
                                                        ? stage1MountedForm[0]
                                                        : com.sixbean.testpike.aiservice
                                                                .llm.domain.component
                                                                .LlmOutputCapabilityLedger
                                                                .FORM_TOOLS_SCHEMA);
                                    }
                                }
                                return disputes;
                            },
                            (draft, disputes) -> askStage1(requirementText, lang, acceptanceCriteria,
                                    knowledge, fewshot, wording, contract, originalSegment, message, primary,
                                    notice, markedModel, bag, frozenStage1System, disputes,
                                    stage1StructuredMounted, stage1MountedForm));
            log.info("阶段 1 推理确认环 taskId={} roundsUsed={}/{} modelCalls={} converged={} stalled={}",
                    taskId, ring.roundsUsed(), ring.roundsPlanned(), ring.modelCalls(),
                    ring.converged(), ring.stalled());
            GeneratedCase parsedDraft;
            try {
                parsedDraft = caseJsonParser.parse(ring.draft());
            } catch (IllegalArgumentException rejected) {
                // Last line of defence before the honest hard-fail: the shape
                // gap is a machine-fixable contract breach (named missing keys
                // + an enforceable schema-bearing form), so the system repairs
                // it itself — pointing the user at their input for a model
                // output format failure is a false road (R63).
                parsedDraft = tryStage1FormatRepair(ring.draft(), rejected, message,
                        primary, notice, markedModel, taskId);
                if (parsedDraft == null) {
                    throw new ServiceException("AI 输出格式校验失败（推理确认环 " + ring.roundsUsed()
                            + " 轮残余：" + String.join("；", ring.residualClaims()) + "）",
                            BizErrorCode.AI_FORMAT_ERROR);
                }
            }
            if (!ring.converged()) {
                log.warn("阶段 1 推理确认环未收敛，残余分歧随草案卡诚实亮出 taskId={} 残余={}",
                        taskId, ring.residualClaims());
            }
            // A7 priority-rule override wins over the model suggestion (PRD 4.1 A7)
            int rulePriority = priorityRule.suggest(requirementText);
            int confidence = parsedDraft.confidence();
            // measured confidence (hot switch
            // ai.gen.confidence-signals.enabled, factory on). Three signals,
            // each reused from an existing piece — fidelity ratio from the
            // gate run (holder), coverage ratio on the final draft,
            // source coverage on the same aggregate. Absent signals leave the
            // mean; no signal at all keeps the self-report. Off = the legacy
            // return shape with the self-reported value.
            if (confidenceMeasurer != null && com.sixbean.testpike.aiservice.llm.domain.component
                    .SysConfigBooleans.parse(coreGateway.getSysConfig(CONFIDENCE_SIGNALS_KEY), true)) {
                Double fidelitySignal = Double.isNaN(fidelityRatioHolder[0])
                        ? null : fidelityRatioHolder[0];
                Double coverageSignal = null;
                if (acceptanceCriteria != null && !acceptanceCriteria.isBlank()) {
                    CoverageResult finalCoverage = coverageChecker.check(acceptanceCriteria,
                            aggregateText(parsedDraft), message.projectId(), taskId);
                    int total = finalCoverage.coveredCount() + finalCoverage.uncovered().size();
                    coverageSignal = total == 0 ? null
                            : (double) finalCoverage.coveredCount() / total;
                }
                Double sourceSignal = com.sixbean.testpike.aiservice.llm.pipeline.EvidenceAnchorGate
                        .sourceCoverage(aggregateText(parsedDraft),
                                stage1Evidence(requirementText, acceptanceCriteria, knowledge));
                var measuredOpt = confidenceMeasurer.measure(fidelitySignal, coverageSignal, sourceSignal);
                if (measuredOpt.isPresent()) {
                    var measured = measuredOpt.get();
                    measuredConfidenceOut.set(measured);
                    confidence = measured.level();
                    log.info("置信度实测 taskId={} 实测level={} 自报={} 信号[忠实={} 覆盖={} 出处={}]",
                            taskId, measured.level(), parsedDraft.confidence(),
                            fidelitySignal, coverageSignal, sourceSignal);
                }
            }
            // B3: honest marks are measured evidence that the draft
            // admits steps without truth backing — while the marking rule is
            // on (master gate + rule key, the same dual read as the audit and
            // prompt sites) such a draft can never persist the top level.
            // Both keys off = legacy value byte-for-byte (R32). The count is
            // the shared FidelityGate signal, never a second tally (R40).
            if (confidence < 2
                    && com.sixbean.testpike.aiservice.llm.domain.component
                            .SysConfigBooleans.parse(
                                    coreGateway.getSysConfig(FIDELITY_GATE_KEY), true)
                    && com.sixbean.testpike.aiservice.llm.domain.component
                            .SysConfigBooleans.parse(
                                    coreGateway.getSysConfig(NO_TRUTH_HONEST_MARK_KEY), true)
                    && com.sixbean.testpike.aiservice.gen.domain.component.FidelityGate
                            .honestMarkHits(ring.draft()) > 0) {
                log.info("置信度诚实标注钳制 taskId={} 草案含「{}」无据步骤 level {}→2（不可顶格置信）",
                        taskId, com.sixbean.testpike.aiservice.gen.domain.component
                                .FidelityGate.HONEST_MARK, confidence);
                confidence = 2;
            }
            return new GeneratedCase(parsedDraft.title(), parsedDraft.precondition(),
                    parsedDraft.expectedResult(), parsedDraft.steps(), rulePriority,
                    confidence, parsedDraft.endType());
        } finally {
            if (mcpPool != null) {
                mcpPool.finish(bag);
            }
        }
    }

    /** : evidence blocks for the stage1 ring checker (requirement, criteria, knowledge). */
    private List<String> stage1Evidence(String requirementText, String acceptanceCriteria,
                                        String knowledge) {
        List<String> evidence = new ArrayList<>();
        evidence.add(requirementText);
        if (acceptanceCriteria != null && !acceptanceCriteria.isBlank()) {
            evidence.add(acceptanceCriteria);
        }
        if (knowledge != null && !knowledge.isBlank()) {
            evidence.add(knowledge);
        }
        return evidence;
    }

    /** ring proposer/reviser segment: one stage1 model pass, disputes in the feedback slot. */
    private String askStage1(String requirementText, String lang, String acceptanceCriteria,
                             String knowledge, String fewshot, String wording, String contract,
                             String originalSegment,
                             AiGenMessage message, LlmEndpointConfig primary, LlmFallbackNotice notice,
                             AtomicReference<String> markedModel,
                             com.sixbean.testpike.aiservice.mcp.domain.component.McpTaskToolBag bag,
                             String system, List<String> disputes,
                             boolean[] structuredMountedOut, String[] mountedFormOut) {
        String prompt = buildStage1Prompt(requirementText, lang, acceptanceCriteria,
                new ArrayList<>(disputes), knowledge, fewshot, wording, contract, originalSegment,
                message.projectId(), message.taskId());
        // : schema-constrained decoding when the hot gate and the model
        // row flag both allow it; provider refusal degrades once to the legacy
        // shape below (strip + retry) with the old parse-and-revise path intact.
        ChatCompletionRequest shaped = GenStructuredOutput.decorate(
                new ChatCompletionRequest(
                        primary.modelName(), List.of(
                        new ChatMessage("system", system),
                        new ChatMessage("user", prompt)), null, null),
                primary, GenStructuredOutput.gateOn(coreGateway),
                GenStructuredOutput.CASE_CONTRACT);
        // Mount record: once the block rode the wire, a format-class dispute
        // afterwards proves the endpoint accepted it without enforcing it
        // (the accept-but-not-enforce class; hard refusals keep their own
        // WARN below and the L3 window reads both anchors separately).
        boolean schemaRides = shaped.responseFormat() != null;
        // Shape rule (V615): a format-class revision — or any first pass of a
        // model whose tools+schema form the capability ledger already proved
        // unenforced — rides the tool-free completion form, where the schema
        // block is actually enforceable on OpenAI-compat gateways. Re-rolling
        // inside the same unenforced shape only burns ring rounds on prompt-
        // text feedback the model may ignore (the class this rule retires).
        boolean schemaOnlyForm = GenStructuredOutput.preferSchemaOnlyForm(
                schemaRides,
                GenStructuredOutput.hasFormatResidual(disputes),
                capabilityLedger != null && capabilityLedger.isFormUnenforced(
                        primary.modelName(),
                        com.sixbean.testpike.aiservice.llm.domain.component
                                .LlmOutputCapabilityLedger.FORM_TOOLS_SCHEMA));
        if (schemaRides) {
            structuredMountedOut[0] = true;
            mountedFormOut[0] = schemaOnlyForm
                    ? com.sixbean.testpike.aiservice.llm.domain.component
                            .LlmOutputCapabilityLedger.FORM_SCHEMA_ONLY
                    : com.sixbean.testpike.aiservice.llm.domain.component
                            .LlmOutputCapabilityLedger.FORM_TOOLS_SCHEMA;
        }
        try {
            return (schemaOnlyForm
                    ? stage1ChatDirect(shaped, message, primary, notice, markedModel)
                    : stage1Chat(shaped, message, primary, notice, markedModel, bag)).content();
        } catch (ServiceException refused) {
            if (shaped.responseFormat() == null) {
                throw refused;
            }
            log.warn("阶段 1 端点拒绝结构化输出，回退旧解析路径 taskId={} model={} err={}",
                    message.taskId(), primary.modelName(), refused.getMessage());
            return stage1Chat(shaped.withoutResponseFormat(), message, primary, notice,
                    markedModel, bag).content();
        }
    }

    /**
 * ring checker segment for stage1: composes the EXISTING judgement
 * sources only (case parser, A6 self-check, A5 acceptance coverage,
 * shared evidence-anchor gate) — no second rule source (R40).
 * <p> adds the faithfulness verdict as one more composed
 * source: the FidelityGate audits the draft's key claims (quantities,
 * boundary/state/negation wording) against the same evidence blocks plus
 * the original-text segment — no second block assembly. Named
 * disputes ride the existing revision feedback for at most
 * {@code ai.gen.fidelity-recheck-rounds} regeneration rounds (hot,
 * default 1, range 0..2; 0 = audit but never block). Once the budget is
 * spent the leftovers are announced by WARN instead of silently dropped,
 * and the master switch {@code ai.gen.fidelity-gate.enabled} (factory on)
 * reverts to the exact legacy verifier shape when off.
 */
    private List<String> verdictOnStage1Draft(String draft, List<String> evidence,
                                              String acceptanceCriteria, String originalSegment,
                                              CoreGateway.RequirementBrief requirement,
                                              int[] fidelityRecheckUsed, double[] fidelityRatioOut,
                                              long taskId, long projectId) {
        GeneratedCase parsed;
        try {
            parsed = caseJsonParser.parse(draft);
        } catch (IllegalArgumentException malformed) {
            return List.of("上次输出格式不合格：" + malformed.getMessage());
        }
        List<String> disputes = new ArrayList<>();
        SelfCheckResult selfCheck = selfCheckValidator.check(parsed);
        if (!selfCheck.passed()) {
            disputes.add("上次输出未通过完整性自检：" + String.join("；", selfCheck.errors()));
        }
        if (acceptanceCriteria != null && !acceptanceCriteria.isBlank()) {
            CoverageResult coverage = coverageChecker.check(acceptanceCriteria,
                    aggregateText(parsed), projectId, taskId);
            if (!coverage.passed()) {
                disputes.add("上次输出未覆盖验收标准：" + String.join("；", coverage.uncovered()));
            }
        }
        disputes.addAll(com.sixbean.testpike.aiservice.llm.pipeline.DeliberationLoop
                .verdictAgainstEvidence(draft, evidence));
        if (fidelityGate != null && com.sixbean.testpike.aiservice.llm.domain.component
                .SysConfigBooleans.parse(coreGateway.getSysConfig(FIDELITY_GATE_KEY), true)) {
            int budget = Math.max(0, Math.min(FIDELITY_ROUNDS_MAX,
                    com.sixbean.testpike.aiservice.llm.domain.component.SysConfigInts
                            .parseInt(coreGateway.getSysConfig(FIDELITY_ROUNDS_KEY),
                                    FIDELITY_ROUNDS_DEFAULT)));
            List<String> blocks = new ArrayList<>(evidence);
            if (originalSegment != null && !originalSegment.isBlank()) {
                blocks.add(originalSegment);
            }
            com.sixbean.testpike.aiservice.gen.domain.component.FidelityVerdict fidelity =
                    fidelityGate.verdict(aggregateText(parsed), blocks);
            if (fidelityRatioOut != null) {
                // signal 1: the observed ratio rides out through the
                // holder even when the recheck budget was already spent.
                fidelityRatioOut[0] = fidelity.fidelityRatio();
            }
            if (!fidelity.passed()) {
                if (fidelityRecheckUsed[0] < budget) {
                    fidelityRecheckUsed[0]++;
                    disputes.addAll(fidelity.disputes());
                } else {
                    log.warn("忠实闸轮数上限已用尽 taskId={} budget={} 核对={} 命中={}，"
                                    + "残余不一致点名亮出={}",
                            taskId, budget, fidelity.checkedClaims(), fidelity.matchedClaims(),
                            fidelity.disputes());
                }
            }
        }
        // segment B-2: per-item rule-ledger reconciliation — the
        // fidelity gate guards written WRONG, this answers written MISSING.
        // The door is its own hot switch (absent row = off = zero chunk
        // pulls, zero embedding round-trips, byte-identical legacy
        // disputes). Uncovered table-row items ride the ring's existing
        // feedback slot as named disputes — never a second loop .
        // A degraded embedding chain keeps the legacy judgement and names
        // a WARN; prose candidates are only counted for human adjudication.
        if (ruleLedgerReconciler != null && requirement != null
                && com.sixbean.testpike.aiservice.llm.domain.component.SysConfigBooleans
                        .on(coreGateway.getSysConfig(RULE_LEDGER_KEY))) {
            List<RuleSourceBlock> ledgerBlocks = ruleSourceBlocksFor(requirement);
            RuleLedgerOutcome ledger = ruleLedgerReconciler.reconcile(ledgerBlocks,
                    aggregateText(parsed), projectId,
                    com.sixbean.testpike.aiservice.llm.domain.component.SysConfigDoubles.parse(
                            coreGateway.getSysConfig(CoverageChecker.SEMANTIC_THRESHOLD_KEY),
                            CoverageChecker.DEFAULT_THRESHOLD,
                            CoverageChecker.THRESHOLD_MIN, CoverageChecker.THRESHOLD_MAX));
            if (ledger.embeddingDegraded()) {
                log.warn("规则账本对账降级（嵌入链不可用，本轮维持旧判据）taskId={} 来源块={} 散文候选={}",
                        taskId, ledgerBlocks.size(), ledger.proseCandidateCount());
            } else {
                if (ledger.proseCandidateCount() > 0) {
                    log.warn("规则账本散文候选待人工裁定（不自动判定）taskId={} 条数={}",
                            taskId, ledger.proseCandidateCount());
                }
                disputes.addAll(ledger.feedbackLines());
            }
        }
        return disputes;
    }

    /**
 * segment B-2: the anchored block set the rule ledger reconciles
 * against — the SAME window selection the stage-1 injection segment
 * uses (OriginalTextBlockAssembler single source, R40: no second copy).
 * Every failure path (no assembler / no anchor / core unreachable /
 * empty pull / empty window) yields an empty list, and an empty ledger
 * costs zero outbound calls. The injection budget does not apply here:
 * the extractor reads whole-row text and never rewrites it.
 */
    private List<RuleSourceBlock> ruleSourceBlocksFor(
            CoreGateway.RequirementBrief requirement) {
        if (originalTextBlockAssembler == null || requirement.sourceAnchor() == null) {
            return List.of();
        }
        CoreGateway.SourceAnchor stored = requirement.sourceAnchor();
        OriginalTextBlockAssembler.Anchor anchor = new OriginalTextBlockAssembler.Anchor(
                stored.documentId(), stored.chunkIds(), stored.evidence());
        if (anchor.empty()) {
            return List.of();
        }
        List<Map<String, Object>> chunks;
        try {
            chunks = coreGateway.getDocumentChunks(anchor.documentId());
        } catch (Exception e) {
            log.warn("规则账本锚定块取块失败 requirementId={} documentId={}（本轮账本为空，生成不中断）: {}",
                    requirement.id(), anchor.documentId(), e.getMessage());
            return List.of();
        }
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        boolean chapterAligned = com.sixbean.testpike.aiservice.llm.domain.component
                .SysConfigBooleans.on(coreGateway.getSysConfig(
                        OriginalTextBlockAssembler.KEY_CHAPTER_ALIGNED_WINDOW));
        List<RuleSourceBlock> blocks = new ArrayList<>();
        for (Map<String, Object> row : originalTextBlockAssembler.windowRows(
                anchor, chunks, chapterAligned)) {
            if (row.get("id") instanceof Number id
                    && row.get("content") instanceof String text
                    && !text.isBlank()) {
                blocks.add(new RuleSourceBlock(id.longValue(), text));
            }
        }
        return blocks;
    }

    
    private LlmChatResult stage1Chat(ChatCompletionRequest request, AiGenMessage message,
                                     LlmEndpointConfig primary, LlmFallbackNotice notice,
                                     AtomicReference<String> markedModel,
                                     com.sixbean.testpike.aiservice.mcp.domain.component.McpTaskToolBag bag) {
        if (reactRunner == null) {
            // S3: the direct pass rides the same budget fitting seam the
            // dialog loops use (NONE on legacy assemblies = send as built).
            return stage1ChatDirect(request, message, primary, notice, markedModel);
        }
        var outcome = reactRunner.run(request, message.projectId(), notice,
                message.operatorId(), message.taskId(), bag);
        backfillModelOnFallback(message.taskId(), primary, outcome.activeEndpoint(), markedModel);
        if (outcome.limitReached()) {
            log.warn("阶段 1 工具循环达轮次上限 taskId={}（{} 次派发/{} 次拒绝），"
                            + "末次输出继续走校验链（可能缺终答）",
                    message.taskId(), outcome.dispatched(), outcome.rejected());
        }
        log.info("阶段 1 FC 工具循环 taskId={} rounds={} dispatched={} rejected={}",
                message.taskId(), outcome.rounds(), outcome.dispatched(), outcome.rejected());
        return outcome.result();
    }

    /**
     * The tool-free stage1 pass (V615): the same E7 direct road the legacy
     * assembly uses, now also the shape format-class revisions and ledger-
     * known-unenforced models take — the form where the mounted schema block
     * is actually enforced by OpenAI-compat endpoints. Single source for the
     * direct send (the reactRunner-absent branch and the form rule share it).
     */
    private LlmChatResult stage1ChatDirect(ChatCompletionRequest request, AiGenMessage message,
                                           LlmEndpointConfig primary, LlmFallbackNotice notice,
                                           AtomicReference<String> markedModel) {
        LlmChatHandle handle = com.sixbean.testpike.aiservice.llm.domain.component
                .LoopContextFitting.send(loopFitting, llmFallbackChain, request,
                        message.projectId(), notice, null);
        backfillModelOnFallback(message.taskId(), primary, handle.activeEndpoint(), markedModel);
        return handle.result();
    }

    /**
     * Targeted field fill for a format-class residual (system-side stop-loss,
     * R63: never send the user to fix the JSON shape of a model output).
     * When the ring ends on a parse refusal, one budget of schema-bearing
     * tool-free re-issues asks the endpoint to re-emit the draft whole with
     * the parser-named gaps closed (same required-key list as the parser —
     * never a second rule source). Transport failure ends the attempt honestly
     * (no spinning); every repaired draft still passes the full parser before
     * it is accepted. Returns null on any failure — the caller then throws
     * the exact legacy FORMAT_ERROR message, so the repair is pure addition.
     */
    private GeneratedCase tryStage1FormatRepair(String draft, IllegalArgumentException rejected,
                                                AiGenMessage message, LlmEndpointConfig primary,
                                                LlmFallbackNotice notice,
                                                AtomicReference<String> markedModel, long taskId) {
        int budget = Math.max(0, Math.min(FORMAT_REPAIR_ROUNDS_MAX,
                com.sixbean.testpike.aiservice.llm.domain.component.SysConfigInts
                        .parseInt(coreGateway.getSysConfig(FORMAT_REPAIR_ROUNDS_KEY),
                                FORMAT_REPAIR_ROUNDS_DEFAULT)));
        if (budget == 0 || draft == null || draft.isBlank()) {
            return null;
        }
        int cap = Math.max(1000, com.sixbean.testpike.aiservice.llm.domain.component.SysConfigInts
                .parseInt(coreGateway.getSysConfig(FORMAT_REPAIR_CHARS_KEY),
                        FORMAT_REPAIR_CHARS_DEFAULT));
        String working = draft;
        for (int round = 1; round <= budget; round++) {
            List<String> gaps = caseJsonParser.collectMissingFields(working);
            String gapText = gaps.isEmpty() ? String.valueOf(rejected.getMessage())
                    : String.join("；", gaps);
            String prompt = "以下测试用例草案不满足输出契约（缺失或不合规字段：" + gapText + "）。"
                    + "保留草案中已有的有效内容，补写缺失字段后只输出一个完整合规的 JSON 对象。"
                    + "必填键：title（测试目标）、expectedResult（预期结果）、steps（数组，每项含非空 description）。"
                    + "\n草案：\n" + (working.length() <= cap ? working : working.substring(0, cap));
            ChatCompletionRequest shaped = GenStructuredOutput.decorate(
                    new ChatCompletionRequest(primary.modelName(), List.of(
                            new ChatMessage("system", "你是测试用例数据修复器，只输出一个完整 JSON 对象，"
                                    + "不输出任何其它文本。"),
                            new ChatMessage("user", prompt)), null, null),
                    primary, GenStructuredOutput.gateOn(coreGateway),
                    GenStructuredOutput.CASE_CONTRACT);
            String out;
            try {
                out = stage1ChatDirect(shaped, message, primary, notice, markedModel).content();
            } catch (RuntimeException transportDown) {
                log.warn("格式类残余定向补写传输失败，保留原判 taskId={} round={} reason={}",
                        taskId, round, transportDown.getMessage());
                return null;
            }
            try {
                GeneratedCase repaired = caseJsonParser.parse(out);
                log.info("格式类残余已定向补写字段成功 taskId={} round={} 缺项={}",
                        taskId, round, gapText);
                return repaired;
            } catch (IllegalArgumentException stillBroken) {
                working = out == null || out.isBlank() ? working : out;
                log.warn("格式类残余定向补写第 {} 轮仍不合契约 taskId={} 缺项={}",
                        round, taskId, String.join("；",
                                caseJsonParser.collectMissingFields(working)));
            }
        }
        return null;
    }

    /**
 * Deterministic mechanical gap fill for the non-UI action family
 * candidate connection NAMES come from the core name door (credentials
 * never touched), the rule surface stays in ScriptMechanicalGapFiller
 * (validator semantics are NOT duplicated, R40), and every machine note
 * lands in the shared process ledger. A door outage or an unparsable
 * draft returns the draft UNCHANGED so the caller keeps its original
 * verdict — never a silent "no database" reading of a failed read.
 */
    String applyMechanicalGapFill(long taskId, long projectId, String draft, String groupTag) {
        if (draft == null || draft.isBlank()) {
            return draft;
        }
        String envName;
        try {
            envName = objectMapper.readTree(JsonOutputExtractor.extractJson(draft))
                    .path("env").path("ENV").asText("");
        } catch (Exception unparsable) {
            return draft;
        }
        List<String> candidates;
        try {
            candidates = coreGateway.listEnabledConnectionNames(projectId, envName);
        } catch (RuntimeException doorDown) {
            log.warn("机械缺项装配跳过，连接名读取通道不可用，保留原判 taskId={} reason={}",
                    taskId, doorDown.getMessage());
            return draft;
        }
        com.sixbean.testpike.common.genkit.ScriptGapFillResult filled =
                new com.sixbean.testpike.common.genkit.ScriptMechanicalGapFiller()
                        .fill(draft, candidates);
        for (String note : filled.notes()) {
            recordProcessEvent(taskId, "step",
                    (groupTag == null || groupTag.isBlank() ? "" : groupTag + " ")
                            + "机械缺项装配：" + note);
        }
        return filled.script();
    }

    /**
     * Script-channel format-residual stop-loss (V615 family, the stage-1
     * repairer form applied to the action-JSON output): the missing-item list
     * is named by the SAME ActionJsonValidator the ring checker uses (single
     * rule source, R40 — the schema's required list only pins ["action"], the
     * per-action gaps live in the validator), then one budgeted pass per round
     * re-asks the tool-free schema-bearing form over the shared fitting seam.
     * Budget = ai.gen.format-repair-rounds (hot K8, shared with the case
     * channel, never a new key). A draft that already passes the shape gate is
     * handed straight back; an unchanged answer, a transport failure or a
     * spent budget returns null so the caller keeps its ORIGINAL rejection
     * wording and business code (no false green, no swallowed judgement).
     */
    private String tryStage2FormatRepair(Stage2Scope scope, String draft, String groupTag) {
        int budget = Math.max(0, Math.min(FORMAT_REPAIR_ROUNDS_MAX,
                com.sixbean.testpike.aiservice.llm.domain.component.SysConfigInts
                        .parseInt(coreGateway.getSysConfig(FORMAT_REPAIR_ROUNDS_KEY),
                                FORMAT_REPAIR_ROUNDS_DEFAULT)));
        if (budget == 0 || draft == null || draft.isBlank()) {
            return null;
        }
        int cap = Math.max(1000, com.sixbean.testpike.aiservice.llm.domain.component
                .SysConfigInts.parseInt(coreGateway.getSysConfig(FORMAT_REPAIR_CHARS_KEY),
                        FORMAT_REPAIR_CHARS_DEFAULT));
        long taskId = scope.message().taskId();
        LlmEndpointConfig primary = scope.primary();
        // knife 2: deterministic mechanical assembly runs BEFORE the
        // first budgeted re-issue round — the endpoint does not enforce
        // constrained decoding for this field vocabulary, so re-asking cannot
        String working = applyMechanicalGapFill(taskId, scope.message().projectId(),
                draft, groupTag);
        for (int round = 1; round <= budget + 1; round++) {
            var check = actionJsonValidator.validate(working);
            if (check.passed()) {
                if (round > 1) {
                    recordProcessEvent(taskId, "step",
                            groupTag + " 格式类残余已定向补写通过动作契约校验");
                    log.info("脚本通道格式类残余定向补写成功 taskId={} round={}",
                            taskId, round - 1);
                }
                return JsonOutputExtractor.extractJson(working);
            }
            if (round > budget) {
                break;
            }
            String gapText = String.join("；", check.errors());
            String prompt = "以下测试脚本不满足动作 JSON v2 输出契约（缺失或不合规项："
                    + gapText + "）。保留脚本中已有的有效动作，只补齐缺项后重新输出完整脚本，"
                    + "只输出一个合规的 JSON 对象，不输出任何其它文本。"
                    + "\n草案：\n" + (working.length() <= cap ? working : working.substring(0, cap));
            ChatCompletionRequest shaped = GenStructuredOutput.decorate(
                    new ChatCompletionRequest(primary.modelName(), List.of(
                            new ChatMessage("system", "你是测试脚本数据修复器，"
                                    + "只输出一个完整动作 JSON 对象，不输出任何其它文本。"),
                            new ChatMessage("user", prompt)), null, null),
                    primary, GenStructuredOutput.gateOn(coreGateway),
                    GenStructuredOutput.SCRIPT_CONTRACT);
            String out;
            try {
                out = com.sixbean.testpike.aiservice.llm.domain.component
                        .LoopContextFitting.send(loopFitting, llmFallbackChain, shaped,
                                scope.message().projectId(), scope.notice(), null)
                        .result().content();
            } catch (RuntimeException transportDown) {
                log.warn("脚本通道定向补写传输失败，保留原判 taskId={} round={} reason={}",
                        taskId, round, transportDown.getMessage());
                return null;
            }
            recordProcessEvent(taskId, "step", groupTag + " 环用尽格式类残余，按缺项定向补写第 "
                    + round + "/" + budget + " 轮：" + gapText);
            working = out == null || out.isBlank() ? working : out;
        }
        log.warn("脚本通道格式类残余定向补写预算用尽仍不合契约，保留原判 taskId={} 缺项={}",
                taskId, String.join("；", actionJsonValidator.validate(working).errors()));
        return null;
    }

    /**
     * Stage 2: the action-JSON main judgement runs through the shared
     * deliberation ring (propose, verify claim by claim, targeted revise) and only
     * a converged draft is saved (E7 wiring: every call rides the fallback chain).
     * The domain requests the legacy ACTION_RETRY ceiling, but the ring clamps it
     * down to the shipped base (ai.kernel.deliberation-rounds, factory 2), so a
     * never-passing draft costs 1 proposal + 1 revision instead of the old fixed
     * 3 calls — an honest cost shrink, never a relaxed verification.
     */
    private void generateAndSaveScript(AiGenMessage message, long caseId, GeneratedCase generated,
                                       LlmEndpointConfig primary,
                                       AtomicReference<String> markedModel,
                                       String originalSegment) {
        LlmFallbackNotice notice = new LlmFallbackNotice(message.operatorId(),
                message.projectId(), "AI 脚本生成任务#" + message.taskId());
        String inspectSegment = pageInspectBridge == null
                || !pageInspectBridge.enabledFor(message.projectId())
                ? "" : pageInspectBridge.inspectSegment(message.projectId(), message.taskId());
        // A5: APP dispatch segment computed once before the ring and reused by
        // every round (inspectSegment discipline); gate off / web target / no
        // cropping basis = empty suffix, legacy behaviour unchanged (gate read
        // and fail-soft live inside the bridge, single point).
        String appSegment = appStepGenBridge == null
                ? "" : appStepGenBridge.dispatchSegment(caseId);
        // S2 segment A (context contract item 6): the case's failed repair
        // ledger folds into every stage-2 round — computed ONCE per task (AP-6
        // stable bytes across ring rounds, inspectSegment discipline), K8 hot
        // read per task; door failure or empty history = empty segment = the
        // legacy prompt shape.
        String priorRepairSegment = priorRepairFeedbackSegment(coreGateway, caseId);
        // S2 segment B (context contract items 4/5): the case-level style
        // few-shot queries with the stored case body over the SINGLE retrieval
        // source (fewshotBridge, never a second search component) and the
        // project resource pool names the referable environments / data
        // factories / API collections — both computed ONCE per task before the
        // ring and reused by every round (AP-6 stable bytes, inspectSegment
        // discipline); null bridge / empty pool / door failure = empty segment
        // = the legacy prompt shape.
        String caseBrief = aggregateText(generated);
        String stage2Fewshot = fewshotBridge == null ? ""
                : fewshotBridge.fewshotSegment(message.taskId(), message.projectId(),
                        caseBrief, primary);
        String resourceSegment = projectResourceSegment(coreGateway, message.projectId(),
                caseBrief, message.operatorId());
        // WE piece C: the confirmed page-element summary of the project rides
        // the stage-2 prompt — computed ONCE per task before the ring and
        // reused by every round (AP-6 stable bytes, inspectSegment discipline);
        // null bridge / gate off / empty ledger / door failure = "" = the
        // legacy prompt shape (the K8 gate and fail-soft live in the bridge,
        // single point — no second judgement here).
        String ledgerSummarySegment = ledgerSummaryBridge == null
                ? "" : ledgerSummaryBridge.summarySegment(message.projectId(), message.taskId());
        // AP-6 prefix stability: SCRIPT_GEN (proposal) and REPAIR_LOOP (revision
        // rounds = repair semantics; executor-side L1 self-healing belongs to M3)
        // are each assembled once per task and frozen for every ring round, so the
        // system prefix stays byte-stable and the provider prompt cache keeps hitting.
        String scriptGenSystem = composeStage2System(message, primary, SkillStage.SCRIPT_GEN);
        String[] repairLoopSystem = new String[1];
        // Per-round gate state — B11/B6 stay live-read (K8 hot updates take effect on
        // the very next model call); the last DDL segment doubles as checker evidence
        // so dictionary-backed figures never read as invented numbers.
        List<String> lastDdl = new ArrayList<>();
        boolean[] sqlGenOn = new boolean[1];
        Stage2Scope frozen = new Stage2Scope(message, generated, primary, notice, markedModel,
                inspectSegment, appSegment, scriptGenSystem, repairLoopSystem, priorRepairSegment,
                stage2Fewshot, resourceSegment, ledgerSummarySegment, lastDdl, sqlGenOn,
                originalSegment == null ? "" : originalSegment, "");
        // S4 segment C: per-group dispatch. A case wider than the segment width
        // is generated group by group — the SAME deliberation ring invoked once
        // per group (its entry and semantics untouched), each group asked for only
        // its own steps, every group numbered by its original stepNo so the merge
        // needs no hidden renumbering. Width 0 / a short case = one group = the
        // legacy single pass byte-for-byte.
        int sliceWidth = scriptLimitResolver.sliceSteps();
        List<List<GeneratedStep>> groups = ScriptSlicePlanner.plan(generated.steps(), sliceWidth);
        String normalized = groups.size() > 1
                ? dispatchStage2Groups(frozen, groups)
                : JsonOutputExtractor.extractJson(
                        convergedStage2Draft(frozen, generated, "阶段 2", true));
        // segment c (union posture, ruling addendum 2026-11-12): the model's
        // voluntary missingResources[] reply AND the server-side mechanical audit
        // of dangling {{ENV}}/{{DATA}} references + env-block values (live 2/2
        // self-report misses proved the trigger cannot ride model discipline).
        // Merged rows ride the process-event ledger (detail stream + draft chip);
        // payload bytes keep the legacy shape (declared key stripped, mechanical
        // gaps never touch the draft). Soft signal — generation is never blocked.
        com.sixbean.testpike.aiservice.gen.domain.component
                .ResourceGapDeclaration.Declaration gapDeclaration =
                com.sixbean.testpike.aiservice.gen.domain.component
                        .ResourceGapDeclaration.extract(objectMapper, normalized);
        normalized = gapDeclaration.json();
        List<com.sixbean.testpike.aiservice.gen.domain.component
                .ResourceGapDeclaration.GapItem> declaredGaps =
                ResourceGapReconciliation.union(gapDeclaration.gaps(),
                        ResourceGapReconciliation.reconcile(objectMapper, normalized,
                                loadPoolRows(coreGateway, message.projectId(),
                                        message.operatorId())));
        if (!declaredGaps.isEmpty()) {
            for (com.sixbean.testpike.aiservice.gen.domain.component
                    .ResourceGapDeclaration.GapItem item : declaredGaps) {
                recordProcessEvent(message.taskId(), "step",
                        "缺资源申报：" + item.category()
                                + (item.intent().isBlank() ? "" : "——" + item.intent()));
            }
            log.info("阶段 2 缺资源申报入账 taskId={} caseId={} 条数={}",
                    message.taskId(), caseId, declaredGaps.size());
        }
        String savedDraft = saveWithValidationRepairRounds(frozen, generated, caseId, normalized);
        clampPersistedConfidenceOnStage2HonestMarks(message, caseId,
                generated.confidence(), savedDraft);
        recordProcessEvent(message.taskId(), "step", groups.size() > 1
                ? "AI 脚本已写入用例（caseId " + caseId + "），" + groups.size() + " 个步骤组合并落库"
                : "AI 脚本已写入用例（caseId " + caseId + "），整单一次生成收敛");
        log.info("AI 脚本落库 caseId={} 步骤组数={}（片宽={}）", caseId, groups.size(), sliceWidth);
    }

    /**
 * Honest-mark confidence clamp at the stage-2 consumption point :
 * the marking clause rides the stage-2 prompt builder, so a live chain
 * carries the mark on the SAVED script draft only — a clamp reading the
 * stage-1 skeleton never fires there (the L3 window pinned the gap:
 * hits always 0, top-level confidence persisted). Same dual keys as the
 * audit/prompt sites and the same shared FidelityGate count (R40); a
 * top-level persisted case is capped to level 2 through the field-level
 * case door, idempotent and never rewriting an already-lower value.
 * The write-back is a soft signal with one bounded retry: an optimistic-
 * lock rejection names the case on the process ledger instead of
 * blocking a completed generation (missing-resource-declaration posture).
 */
    private void clampPersistedConfidenceOnStage2HonestMarks(AiGenMessage message, long caseId,
                                                             int persistedConfidence,
                                                             String savedDraft) {
        if (persistedConfidence >= 2
                || !com.sixbean.testpike.aiservice.llm.domain.component
                        .SysConfigBooleans.parse(
                                coreGateway.getSysConfig(FIDELITY_GATE_KEY), true)
                || !com.sixbean.testpike.aiservice.llm.domain.component
                        .SysConfigBooleans.parse(
                                coreGateway.getSysConfig(NO_TRUTH_HONEST_MARK_KEY), true)
                || com.sixbean.testpike.aiservice.gen.domain.component.FidelityGate
                        .honestMarkHits(savedDraft) == 0) {
            return;
        }
        log.info("置信度诚实标注钳制 taskId={} caseId={} 阶段 2 落库稿含「{}」无据步骤 level {}→2（不可顶格置信）",
                message.taskId(), caseId, com.sixbean.testpike.aiservice.gen.domain.component
                        .FidelityGate.HONEST_MARK, persistedConfidence);
        try {
            coreGateway.clampCaseConfidence(caseId, 2, message.operatorId());
        } catch (ServiceException first) {
            try {
                coreGateway.clampCaseConfidence(caseId, 2, message.operatorId());
            } catch (ServiceException rejected) {
                log.warn("置信度诚实标注钳制回写被拒（乐观锁），保留原值 taskId={} caseId={} 原因={}",
                        message.taskId(), caseId, rejected.getMessage());
                recordProcessEvent(message.taskId(), "step",
                        "用例 " + caseId + " 含「需人工精化」标注的无据步骤，置信度顶格下调回写被并发编辑"
                                + "拒绝（乐观锁），当前值保持不变，请在评审时人工复核该标注步骤");
            }
        }
    }

    /**
     * generate-then-repair at the save door. The core static validator
     * stays the single write gate — a rejection is NEVER bypassed: each repair
     * round re-submits through the SAME door. The validator error text feeds the
     * existing stage-2 revision machinery (REPAIR_LOOP system head, no second
     * proposal path), bounded by ai.repair.rounds (K8 hot read, same parser and
     * default as the fold-back cap; 0 = legacy single-attempt behaviour). Every
     * round is a milestone on the shared process ledger (steps-gen,
     * recordProcessEvent single source); an exhausted budget fails the whole
     * order with the residual rejection named, so the markFailed path keeps
     * its plain-language cause unchanged.
     */
    private String saveWithValidationRepairRounds(Stage2Scope scope, GeneratedCase view,
                                                  long caseId, String draft) {
        int cap = repairFoldCap(coreGateway.getSysConfig("ai.repair.rounds"));
        String current = draft;
        String rejection = null;
        for (int attempt = 0; attempt <= cap; attempt++) {
            if (rejection != null) {
                recordProcessEvent(scope.message().taskId(), "step",
                        "落库被脚本静态校验拒，自动进入修复轮（第 " + attempt + "/" + cap
                                + " 轮）：" + rejection);
                current = JsonOutputExtractor.extractJson(askStage2(scope, view,
                        List.of("落库被脚本静态校验拒：" + rejection)));
            }
            try {
                coreGateway.saveScript(caseId, "v2", current, 1, scope.message().operatorId());
                if (attempt > 0) {
                    log.info("修复轮回写成功 caseId={} 轮次={}", caseId, attempt);
                }
                return current;
            } catch (ServiceException rejected) {
                if (attempt >= cap) {
                    recordProcessEvent(scope.message().taskId(), "error",
                            "脚本修复轮用尽（" + cap + " 轮）仍被校验拒，转人工："
                                    + rejected.getMessage());
                    throw rejected;
                }
                rejection = rejected.getMessage();
            }
        }
        return current;
    }

    /**
 * Runs the stage-2 deliberation ring for one case view (the whole case, or a
 * single step group under segment C) and returns its CONVERGED draft, naming
 * the outcome on the process ledger. {@code groupTag} is the whole-case tag
 * on the legacy pass and "step group k/n" on a sliced pass, so one
 * milestone/rejection wording serves both shapes (no second copy).
 * {@code allowFormatRepair} gates the script-channel stop-loss: the
 * whole-order (legacy single-pass) shape has no other stop-loss and rides
 * the budgeted re-fill; the sliced shape already owns its per-group
 * re-dispatch stop-loss (segment C), so a second rescue per attempt would
 * double the burn and rewrite the asserted dispatch semantics.
 */
    private String convergedStage2Draft(Stage2Scope scope, GeneratedCase view, String groupTag,
                                        boolean allowFormatRepair) {
        String brief = aggregateText(view);
        // Mount record for the script channel (V615 family, gap closed on the
        // proof moment): the wire here is tool-free, so a mounted schema block
        // plus a later format-class dispute is the accept-but-not-enforced
        // proof for the schema_only form — the capability row used to stay
        // blind on this channel. One notice per ring pass (per group under the
        // sliced shape), mirroring the stage-1 discipline.
        final String[] stage2MountedForm = new String[1];
        final boolean[] stage2NoticeDone = {false};
        com.sixbean.testpike.aiservice.llm.pipeline.DeliberationOutcome ring =
                com.sixbean.testpike.aiservice.llm.pipeline.DeliberationLoop.deliberate(
                        brief,
                        stage2Evidence(brief, scope.inspectSegment(), scope.appSegment(),
                                scope.lastDdl(), scope.originalSegment()),
                        coreGateway.getSysConfig(com.sixbean.testpike.aiservice.llm.pipeline
                                .DeliberationLoop.GOVERNANCE_KEY),
                        ACTION_RETRY + 1,
                        draftBrief -> askStage2(scope, view, List.of(), stage2MountedForm),
                        (draft, ev) -> {
                            List<String> disputes = verdictOnStage2Draft(draft, ev);
                            if (!stage2NoticeDone[0] && stage2MountedForm[0] != null
                                    && GenStructuredOutput.hasFormatResidual(disputes)) {
                                stage2NoticeDone[0] = true;
                                log.warn("结构化输出已挂载但端点未强制（脚本通道）"
                                                + " taskId={} model={} 分歧={}",
                                        scope.message().taskId(), scope.primary().modelName(),
                                        disputes);
                                if (capabilityLedger != null) {
                                    capabilityLedger.observeNotEnforced(
                                            scope.primary().modelName(), stage2MountedForm[0]);
                                }
                            }
                            return disputes;
                        },
                        (draft, disputes) -> askStage2(scope, view, disputes, stage2MountedForm));
        log.info("阶段 2 推理确认环 {} taskId={} roundsUsed={}/{} modelCalls={} converged={} stalled={}",
                groupTag, scope.message().taskId(), ring.roundsUsed(), ring.roundsPlanned(),
                ring.modelCalls(), ring.converged(), ring.stalled());
        // S4 segment B: the ring outcome is a process milestone for the
        // job drawer ledger — named rounds/model calls, converged or not.
        // The store is fail-soft by contract; a null door is a silent no-op.
        recordProcessEvent(scope.message().taskId(), "step",
                groupTag + " 推理确认环结束：共 " + ring.roundsUsed() + " 轮、模型调用 "
                        + ring.modelCalls() + " 次，"
                        + (ring.converged() ? "已收敛" : "未收敛"));
        if (ring.converged()) {
            return ring.draft();
        }
        // Script-channel stop-loss for format-class residuals (V615 family,
        // mirrors the stage-1 precedent the live FORMAT_ERROR signatures kept
        // proving missing here): the named gaps re-ride a budgeted schema-only
        // re-fill instead of failing the whole order. Posture/coverage residuals
        // keep the legacy rejection byte-for-byte; budget 0 or a still-broken
        // answer falls back to the ORIGINAL judgement (never a false green).
        if (allowFormatRepair
                && GenStructuredOutput.hasFormatResidual(ring.residualClaims())) {
            String repaired = tryStage2FormatRepair(scope, ring.draft(), groupTag);
            if (repaired != null) {
                return repaired;
            }
        }
        String rejectCause = stage2RejectCause(ring);
        // The red event names the residual claims before the throw — the
        // exception still propagates, the event never swallows it.
        recordProcessEvent(scope.message().taskId(), "error",
                groupTag + " 拒收：" + rejectCause);
        throw new ServiceException(rejectCause, BizErrorCode.AI_FORMAT_ERROR);
    }

    /** Plain-language rejection of a never-converged stage-2 ring (legacy wording). */
    private String stage2RejectCause(
            com.sixbean.testpike.aiservice.llm.pipeline.DeliberationOutcome ring) {
        boolean styleTracked = ring.residualClaims().stream().anyMatch(f -> f.contains("姿势"));
        return styleTracked
                ? "AI 脚本姿势不符合团队规范（推理确认环 " + ring.roundsUsed()
                        + " 轮残余：" + String.join("；", ring.residualClaims())
                        + "，违规步已点名请人工整改）"
                : "AI 脚本格式校验失败（推理确认环 " + ring.roundsUsed()
                        + " 轮残余：" + String.join("；", ring.residualClaims()) + "）";
    }

    /**
 * segment C dispatch: walk the step groups in order behind a cursor that only
 * moves forward. A group whose ring never converges is RE-DISPATCHED alone up
 * to {@code ai.script.slice-retry} times — already-converged groups are never
 * asked again (no whole-order redo, no surplus spend). A group that stays
 * broken fails the whole order: no partial script reaches the case library,
 * and the red event plus the rejection name the group. Only after every group
 * converges are the per-group DSLs merged into the one whole script.
 */
    private String dispatchStage2Groups(Stage2Scope frozen, List<List<GeneratedStep>> groups) {
        int retryLimit = scriptLimitResolver.sliceRetry();
        int total = groups.size();
        long taskId = frozen.message().taskId();
        List<String> groupDsls = new ArrayList<>();
        for (int g = 0; g < total; g++) {
            String tag = "步骤组 " + (g + 1) + "/" + total;
            Stage2Scope groupScope = frozen.forGroup(
                    "（本轮只生成" + tag + "共 " + groups.get(g).size()
                            + " 个步骤的动作，序号沿用下列步骤原有编号，禁输出其它步骤）\n");
            GeneratedCase view = new GeneratedCase(frozen.fullCase().title(),
                    frozen.fullCase().precondition(), frozen.fullCase().expectedResult(),
                    groups.get(g), frozen.fullCase().priority(), frozen.fullCase().confidence(),
                    frozen.fullCase().endType());
            for (int attempt = 1; ; attempt++) {
                try {
                    groupDsls.add(convergedStage2Draft(groupScope, view, tag, false));
                    break;
                } catch (ServiceException rejected) {
                    if (attempt > retryLimit) {
                        recordProcessEvent(taskId, "error",
                                tag + " 重试 " + attempt + " 次仍未收敛，整单不落库："
                                        + rejected.getMessage());
                        throw new ServiceException("步骤生成失败的组：" + tag + "（"
                                + rejected.getMessage() + "），其余组的产物未写入",
                                BizErrorCode.AI_FORMAT_ERROR);
                    }
                    recordProcessEvent(taskId, "step", tag + " 未收敛，按重试从该组续跑（第 "
                            + (attempt + 1) + " 次，共允许 " + (retryLimit + 1) + " 次）");
                }
            }
        }
        return ScriptSliceMerger.merge(groupDsls);
    }

    /**
 * segment3: one stage2 model pass (proposal or targeted revision) for one
 * case view (whole case, or one step group). The B11 sql-gen gate, the DDL
 * truth gate and the DDL dictionary segment are re-read per call inside the
 * proposer/reviser closure (K8 hot-update discipline preserved); disputes land
 * in the feedback slot of buildStage2Prompt with the exact legacy wording, so
 * the first pass carries none and every revision names the previous defects.
 * All per-task frozen inputs ride {@code scope} (segment C), including the
 * group note that keeps a sliced pass inside its own step range.
 */
    private String askStage2(Stage2Scope scope, GeneratedCase caseView, List<String> disputes) {
        return askStage2(scope, caseView, disputes, new String[1]);
    }

    /**
     * Same pass with an optional mount-form holder (stage-1 discipline): the
     * ring wiring records which wire form actually carried the schema block so
     * the accept-but-not-enforced proof can correct the capability row; other
     * callers pass the throwaway holder.
     */
    private String askStage2(Stage2Scope scope, GeneratedCase caseView, List<String> disputes,
                             String[] mountedFormOut) {
        AiGenMessage message = scope.message();
        GeneratedCase generated = scope.fullCase();
        LlmEndpointConfig primary = scope.primary();
        LlmFallbackNotice notice = scope.notice();
        AtomicReference<String> markedModel = scope.markedModel();
        String inspectSegment = scope.inspectSegment();
        String appSegment = scope.appSegment();
        String scriptGenSystem = scope.scriptGenSystem();
        String[] repairLoopSystem = scope.repairLoopSystem();
        List<String> lastDdl = scope.lastDdl();
        boolean[] sqlGenOn = scope.sqlGenOn();
        String priorRepairSegment = scope.priorRepairSegment();
        String stage2Fewshot = scope.stage2Fewshot();
        String resourceSegment = scope.resourceSegment();
        // B11: the ai.sql-gen.enabled gate is re-read on every model call (K8
        // hot update, DdlDictBridge single source); the DDL dictionary segment is
        // fetched only while the gate is on and the bridge is present
        // (fail-soft empty segment; revision rounds re-read just the same).
        sqlGenOn[0] = com.sixbean.testpike.aiservice.knowledge.domain.component
                .DdlDictBridge.sqlGenEnabled(coreGateway);
        // the table-structure segment rides its own truth gate
        // (factory ON, hot-read per call) as a superset of the legacy sql-gen
        // gate; sqlGenOn[0] alone still drives the SQL-action whitelist.
        boolean ddlInjectionOn = com.sixbean.testpike.aiservice.knowledge.domain.component
                .DdlDictBridge.injectionEnabled(coreGateway);
        String ddlSegment = ddlInjectionOn && ddlBridge != null
                ? ddlBridge.ddlSegment(message.taskId(), message.projectId(),
                        aggregateText(generated), primary)
                : "";
        lastDdl.clear();
        lastDdl.add(ddlSegment);
        // S4 segment C: a group pass is addressed first, so the model sees
        // which step range it owns before the case block (group note only — the
        // frozen system prefix keeps its bytes for the prompt-cache economy).
        String prompt = scope.sliceNote()
                + buildStage2Prompt(caseView, new ArrayList<>(disputes), sqlGenOn[0],
                ddlSegment, ddlInjectionOn, message.projectId(), message.taskId(),
                scope.ledgerSummarySegment(), scope.originalSegment())
                // G4 (P2): template and built-in paths share one suffix splice (empty = no change)
                + inspectSegment
                // A5: template and built-in paths share one suffix splice for APP steps
                + appSegment
                // S2 segment A: prior failed-repair notes ride the same shared
                // suffix splice for every ring round (empty = legacy bytes)
                // segment B: case-level style few-shot (single retrieval source,
                // query = stored case body) and the referable resource pool ride
                // the same splice ahead of the repair fold-back
                + stage2Fewshot
                + resourceSegment
                + priorRepairSegment;
        // Consumption points ③/④ — proposal rides the SCRIPT_GEN prefix, revision
        // rounds share one lazily-built REPAIR_LOOP prefix (built at most once/task).
        String system;
        if (disputes.isEmpty()) {
            system = scriptGenSystem;
        } else {
            if (repairLoopSystem[0] == null) {
                repairLoopSystem[0] = composeStage2System(message, primary, SkillStage.REPAIR_LOOP);
            }
            system = repairLoopSystem[0];
        }
        // : same structured-output gate for the action-JSON envelope;
        // refusal degrades once to the legacy shape (parse-and-revise unchanged).
        ChatCompletionRequest shaped = GenStructuredOutput.decorate(
                new ChatCompletionRequest(
                        primary.modelName(), List.of(
                        new ChatMessage("system", system),
                        new ChatMessage("user", prompt)), null, null),
                primary, GenStructuredOutput.gateOn(coreGateway),
                GenStructuredOutput.SCRIPT_CONTRACT);
        // Mount record: this wire carries no function-calling tools, so the
        // form that rode the schema block is the enforceable schema-only one
        // (the same constant the stage-1 shape rule writes).
        if (shaped.responseFormat() != null) {
            mountedFormOut[0] = com.sixbean.testpike.aiservice.llm.domain.component
                    .LlmOutputCapabilityLedger.FORM_SCHEMA_ONLY;
        }
        LlmChatHandle handle;
        try {
            // S3: every stage2 ring pass (proposal + revisions + the
            // degraded resend) rides the shared budget fitting send seam.
            handle = com.sixbean.testpike.aiservice.llm.domain.component
                    .LoopContextFitting.send(loopFitting, llmFallbackChain, shaped,
                            message.projectId(), notice, null);
        } catch (ServiceException refused) {
            if (shaped.responseFormat() == null) {
                throw refused;
            }
            log.warn("阶段 2 端点拒绝结构化输出，回退旧解析路径 taskId={} caseId={} model={} err={}",
                    message.taskId(), message.projectId(), primary.modelName(),
                    refused.getMessage());
            handle = com.sixbean.testpike.aiservice.llm.domain.component
                    .LoopContextFitting.send(loopFitting, llmFallbackChain,
                            shaped.withoutResponseFormat(), message.projectId(), notice, null);
        }
        backfillModelOnFallback(message.taskId(), primary, handle.activeEndpoint(), markedModel);
        // Closing-window diagnostic: both stalled tasks proved the
        // provider response landed (call-log row + recorder line) yet the stage-2
        // ring never logged its tail, with no exception anywhere on the stack.
        // These boundary INFO lines localise the silence to one seam for the
        // next minimal family window; behaviour-neutral, no second source.
        log.info("阶段 2 模型调用返回 taskId={} 内容长度={} 端点={}",
                message.taskId(),
                handle.result().content() == null ? -1 : handle.result().content().length(),
                handle.activeEndpoint().modelName());
        return handle.result().content();
    }

    /** segment3: SKILL composition for one stage2 prefix family (single assembly per task each). */
    private String composeStage2System(AiGenMessage message, LlmEndpointConfig primary,
                                       SkillStage stage) {
        String scriptSystemBase = "你是测试脚本专家，请按动作 JSON v2 格式输出可执行脚本（标记 "
                + LlmExtractMarkers.STAGE2_ACTION + "；元素定位用 CSS 单策略，04 契约）。";
        return skillBridge == null ? scriptSystemBase
                : skillBridge.composeSystem(scriptSystemBase, stage,
                        message.projectId(), message.taskId(), primary);
    }

    /** segment3: evidence blocks for the stage2 ring (case brief + per-run segments). */
    private List<String> stage2Evidence(String brief, String inspectSegment,
                                        String appSegment, List<String> lastDdl,
                                        String originalSegment) {
        List<String> evidence = new ArrayList<>();
        evidence.add(brief);
        if (!inspectSegment.isBlank()) {
            evidence.add(inspectSegment);
        }
        if (!appSegment.isBlank()) {
            evidence.add(appSegment);
        }
        evidence.addAll(lastDdl);
        // (A1): the frozen stage-1 original block joins the ring
        // evidence so the shared numeric-provenance rule can trace figures
        // back to the source document (no second judgement source, R40);
        // no anchor / empty pull = "" = the legacy evidence shape.
        if (originalSegment != null && !originalSegment.isBlank()) {
            evidence.add(originalSegment);
        }
        return evidence;
    }

    /**
 * segment3 ring checker for stage2: composes the EXISTING judgement sources
 * only (action JSON schema validator, B6 style contract, shared evidence-anchor
 * gate) — no second rule source (R40). The B6 posture gate stays live-read per
 * round exactly as the old retry loop kept it (passing the schema is not
 * passing the posture rule).
 */
    private List<String> verdictOnStage2Draft(String draft, List<String> evidence) {
        // Closing-window diagnostic: verifier seam entry/exit, so the
        // next minimal family window names the exact step that goes silent after
        // the provider response. Behaviour-neutral.
        log.info("阶段 2 复核开始 草稿长度={} 证据块={}",
                draft == null ? -1 : draft.length(), evidence.size());
        boolean styleGateOn = com.sixbean.testpike.common.util.ScriptStyleContract
                .isSwitchOn(coreGateway.getSysConfig(
                        com.sixbean.testpike.common.util.ScriptStyleContract.CONFIG_KEY_ENABLED));
        var check = actionJsonValidator.validate(draft);
        if (!check.passed()) {
            return List.of("上次脚本不合格：" + String.join("；", check.errors()));
        }
        List<String> disputes = new ArrayList<>();
        if (styleGateOn) {
            // The core save gate only audits generatedBy=2, so generation-chain
            // governance lands on this path; posture violations enter the ring as
            // a plain-language list for the targeted revision.
            var styleViolations = com.sixbean.testpike.common.util.ScriptStyleContract
                    .check(JsonOutputExtractor.extractJson(draft));
            if (!styleViolations.isEmpty()) {
                disputes.add("上次脚本姿势不符合团队规范："
                        + com.sixbean.testpike.common.util.ScriptStyleContract
                                .describe(styleViolations));
                log.warn("B6 姿势闸未通过 违例={} 条", styleViolations.size());
            }
        }
        disputes.addAll(com.sixbean.testpike.aiservice.llm.pipeline.DeliberationLoop
                .verdictAgainstEvidence(draft, evidence));
        // segment: control-reference family of the SAME faithfulness
        // gate (no second loop component, R40). The existence evidence surface
        // is the stage2 candidate segment (SEGMENT_ANCHOR-headed block among
        // the ring evidence). : with the honest-marking rule on, a blank
        // segment is no longer an audit-free pass — unbacked URL / locator /
        // mirrored to UI/HTTP steps). Master switch = ai.gen.fidelity-gate
        // (hot read per round, K8); disputes ride the existing revision loop.
        if (fidelityGate != null && com.sixbean.testpike.aiservice.llm.domain.component
                .SysConfigBooleans.parse(coreGateway.getSysConfig(FIDELITY_GATE_KEY), true)) {
            boolean honestMarkRule = com.sixbean.testpike.aiservice.llm.domain.component
                    .SysConfigBooleans.parse(
                            coreGateway.getSysConfig(NO_TRUTH_HONEST_MARK_KEY), true);
            String candidates = evidence.stream()
                    .filter(b -> b != null && b.startsWith(PageInspectBridge.SEGMENT_ANCHOR))
                    .findFirst().orElse("");
            var refs = fidelityGate.controlRefs(draft, candidates, honestMarkRule);
            if (!refs.passed()) {
                disputes.addAll(refs.disputes());
                log.warn("控件引用忠实核对检出 {} 条（候选清单/诚实标注为准），随定向修订轮反馈",
                        refs.disputes().size());
            }
        }
        return disputes;
    }

    
    private void backfillModelOnFallback(long taskId, LlmEndpointConfig primary,
                                         LlmEndpointConfig active,
                                         AtomicReference<String> markedModel) {
        if (active.baseUrl() == null || active.baseUrl().equals(primary.baseUrl())
                || active.modelName() == null) {
            return;
        }
        String previous = markedModel.getAndSet(active.modelName());
        if (!active.modelName().equals(previous)) {
            coreGateway.markModel(taskId, active.modelName());
            log.info("LLM 降级后模型标识回填 taskId={} from={} to={}",
                    taskId, primary.modelName(), active.modelName());
        }
    }

    private long persistCase(AiGenMessage message, GeneratedCase generated,
                             CoreGateway.RequirementBrief requirement) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("projectId", message.projectId());
        input.put("title", generated.title());
        input.put("precondition", generated.precondition());
        input.put("expectedResult", generated.expectedResult());
        input.put("priority", generated.priority());
        input.put("confidence", generated.confidence());
        input.put("endType", generated.endType());
        input.put("status", 1);
        input.put("source", 1);
        // chain ①: generated steps inherit the requirement point's
        // evidence anchor (document/chunks already stored on tp_requirement).
        // Null stamp = legacy payload shape, byte-for-byte unchanged. The core
        // single-source normalizer validates the shape on landing (R40).
        CoreGateway.SourceAnchor src = requirement == null ? null : requirement.sourceAnchor();
        String evidenceAnchor = com.sixbean.testpike.aiservice.gen.domain.component
                .GenStepEvidenceAnchor.sourceAnchorOf(message.requirementId(),
                        src == null ? null : src.documentId(),
                        src == null ? null : src.chunkIds());
        List<Map<String, Object>> steps = new ArrayList<>();
        for (GeneratedStep step : generated.steps()) {
            Map<String, Object> stepMap = new LinkedHashMap<>();
            stepMap.put("stepNo", step.stepNo());
            stepMap.put("description", step.description());
            stepMap.put("isAssert", step.isAssert());
            if (evidenceAnchor != null) {
                stepMap.put("anchor", evidenceAnchor);
            }
            steps.add(stepMap);
        }
        input.put("steps", steps);
        Map<String, Object> created = coreGateway.createCase(message.operatorId(), input);
        return ((Number) created.get("id")).longValue();
    }

    private String buildStage1Prompt(String requirementText, String lang,
                                     String acceptanceCriteria, List<String> feedback,
                                     String knowledgeSegment, String fewshotSegment,
                                     String wordingSegment, String contractSegment,
                                     String originalSegment,
                                     long projectId, long taskId) {
        // Pre-send redaction (behaviour kernel round-2 item 2, R40 single
        // source): the outbound copies of the requirement and acceptance
        // text pass the masking gate before they are spliced into the
        // prompt. In-system retrieval (knowledge/few-shot segments) keeps
        // querying with the raw text because those reads never leave the
        // deployment — only the send payload is transformed.
        requirementText = RedactGate.redactOutbound(
                coreGateway.getSysConfig(RedactGate.KEY_ENABLED),
                coreGateway.getSysConfig(RedactGate.KEY_PATTERNS), requirementText);
        acceptanceCriteria = RedactGate.redactOutbound(
                coreGateway.getSysConfig(RedactGate.KEY_ENABLED),
                coreGateway.getSysConfig(RedactGate.KEY_PATTERNS), acceptanceCriteria);
        CoreGateway.PromptTemplateBrief stage1Template =
                templateRowOrNull(PromptTemplateResolver.STAGE1_TEMPLATE_NAME, projectId, taskId, true);
        if (stage1Template != null) {
            return promptTemplateResolver.render(stage1Template.content(), Map.of(
                    "requirement", requirementText,
                    "acceptanceCriteria", acceptanceCriteria == null || acceptanceCriteria.isBlank()
                            ? "" : "验收标准（必须逐条覆盖，生成相应步骤）：\n" + acceptanceCriteria + "\n",
                    "knowledge", knowledgeSegment == null ? "" : knowledgeSegment,
                    "fewshot", fewshotSegment == null ? "" : fewshotSegment,
                    "wording", wordingSegment == null ? "" : wordingSegment,
                    // contract truth-source brief; template
                    // authors opt in with {{contract}} - rows without it unchanged
                    "contract", contractSegment == null ? "" : contractSegment,
                    // isolated source-text section ; template authors
                    // opt in with {{originalBlock}} - rows without it are unchanged
                    "originalBlock", originalSegment == null ? "" : originalSegment,
                    "lang", lang,
                    "feedback", feedbackBlock(feedback, "上次生成反馈（请修正）：")));
        }
        StringBuilder sb = new StringBuilder();
        sb.append("请针对以下需求生成 1 条结构化测试用例：\n").append(requirementText).append('\n');
        sb.append("输出字段：title（测试目标）、precondition（前置条件）、steps（步骤数组，元素含 "
                + "description 与 isAssert 0=操作 1=断言，编号从 1 连续）、expectedResult（预期结果）、"
                + "priority（0=P0 1=P1 2=P2）、confidence（1=高 2=中 3=低）、endType（web/mobile/pos/multi）。\n");
        if (acceptanceCriteria != null && !acceptanceCriteria.isBlank()) {
            sb.append("验收标准（必须逐条覆盖，生成相应步骤）：\n").append(acceptanceCriteria).append('\n');
        }
        if (originalSegment != null && !originalSegment.isBlank()) {
            sb.append(originalSegment).append('\n');
        }
        if (knowledgeSegment != null && !knowledgeSegment.isBlank()) {
            sb.append(knowledgeSegment).append('\n');
        }
        // contract truth-source segment (assertion fields
        // cite real contract keys + measured samples); empty = zero injection
        // = the legacy prompt bytes (V428 honesty contract).
        if (contractSegment != null && !contractSegment.isBlank()) {
            sb.append(contractSegment).append('\n');
        }
        if (fewshotSegment != null && !fewshotSegment.isBlank()) {
            sb.append(fewshotSegment).append('\n');
        }
        if (wordingSegment != null && !wordingSegment.isBlank()) {
            sb.append(wordingSegment).append('\n');
        }
        sb.append("内容语言：").append(lang).append('\n');
        if (!feedback.isEmpty()) {
            sb.append("上次生成反馈（请修正）：\n");
            for (String f : feedback) {
                sb.append(" - ").append(f).append('\n');
            }
        }
        return sb.toString();
    }

    private String buildStage2Prompt(GeneratedCase generated, List<String> feedback,
                                     boolean sqlGenOn, String ddlSegment,
                                     boolean ddlInjectionOn,
                                     long projectId, long taskId,
                                     String ledgerSummarySegment,
                                     String originalSegment) {
        CoreGateway.PromptTemplateBrief stage2Template =
                templateRowOrNull(PromptTemplateResolver.STAGE2_TEMPLATE_NAME, projectId, taskId, false);
        if (stage2Template != null) {
            Map<String, String> stage2Vars = new LinkedHashMap<>();
            stage2Vars.put("caseBlock", caseBlock(generated));
            stage2Vars.put("scriptLimits", scriptLimitResolver.promptConstraintSegment());
            stage2Vars.put("feedback",
                    feedbackBlock(feedback, "上次脚本不合格（请逐条修正后重新输出）："));
            // : template authors opt in with {{originalBlock}} — rows
            // without the placeholder are unchanged (render is content-driven).
            stage2Vars.put("originalBlock", originalSegment == null ? "" : originalSegment);
            return promptTemplateResolver.render(stage2Template.content(), stage2Vars);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("请将以下测试用例转换为动作 JSON v2：\n");
        sb.append(caseBlock(generated));
        // (A1): the stage-1 frozen original block rides through as an
        // isolated section (same bytes, no second assembly, R40); no anchor /
        // failed pull / empty pull = "" = the exact legacy prompt bytes.
        if (originalSegment != null && !originalSegment.isBlank()) {
            sb.append(originalSegment).append('\n');
        }
        sb.append("要求：version=v2、caseId、setup（前置脚本化，setup 为步骤数组（动作对象列表，"
                + "禁对象/字符串）；登录态 URL 用 {{ENV:键名}} 占位——键名只能是字母/数字/"
                + "下划线，如 {{ENV:BASE_URL}}，禁省略号等其它写法）、"
                + "steps（线性，target 为 locator 对象、env 必填；动作仅限 goto/click/input/"
                + "expect/wait/screenshot/if/loop"
                + "/console.assert/net.assert/http.request"
                + (sqlGenOn ? "/sql.assert/sql.query" : "") + "；"
                + scriptLimitResolver.promptConstraintSegment() + "\n"
                + "locator 结构示例（04 契约 §2.3，定位类动作 chain 至少 1 项——缺 chain 判不合格）：\n"
                + "\"target\":{\"css\":\"#login-btn\",\"chain\":[\"css\"]}\n"
                + "顶层 env 结构示例（04 契约规则 2，env 与 caseId 并列必填、三键缺一不可）：\n"
                + "\"env\":{\"ENV\":\"<环境code>\",\"PACK\":\"<行业包>\",\"DATA\":\"<数据池>\"}\n"
                + "goto 黄金样例（04 契约 §2.1，goto 的 url 必填——写顶层 \"url\" 或 target.url，"
                + "地址取需求给出的完整 URL 或 {{ENV:键名}} 占位，禁放进 value/description）：\n"
                + "{\"action\":\"goto\",\"url\":\"{{ENV:BASE_URL}}/login\"}\n"
                + "断言映射规则（04 契约 §2.1.1 matchType，按预期结果语义选择）：\n"
                + "- 单字段区间语义（大于X小于Y/在X~Y之间/不低于X）→ matchType=range，"
                + "bounds 开闭映射：严格不等（大于/小于）=open、含等（不低于/不超过）=closed、"
                + "\"X以上Y以下\"混用=left-open/right-open；金额页带 ¥/千分位时 strip 按页面单位惯例\n"
                + "- 格式/形态语义（单号形如/以…开头）→ matchType=regex，pattern 受限"
                + "（≤200 字符、禁嵌套量词如 (a+)+，超权限或非法产物将被拒绝）\n"
                + "- regex 无法可靠构造时回退 matchType=contains，并在该步 description 末尾"
                + "标注「需人工精化」——不硬撑假精确断言\n"
                + "- 跨字段运算（金额=单价×数量-优惠）→ 输出 code.step 候选并在 description "
                + "标注「待 B8」，禁用 range 凑合\n"
                + "断言黄金样例（输入→期望产物骨架对照）：\n"
                + "样例1 区间双开：预期「应付金额大于100且小于200元」→ "
                + "{\"action\":\"expect\",\"target\":{\"css\":\"#amount\",\"chain\":[\"css\"]},"
                + "\"matchType\":\"range\",\"min\":100,\"max\":200,\"bounds\":\"open\",\"strip\":[\"¥\",\"￥\",\",\"]}\n"
                + "样例2 单开区间：预期「库存不低于50」→ "
                + "{\"action\":\"expect\",\"target\":{\"css\":\"#stock\",\"chain\":[\"css\"]},"
                + "\"matchType\":\"range\",\"min\":50,\"bounds\":\"left-open\"}\n"
                + "样例3 regex 单号：预期「订单号形如 PO 开头 8 位数字」→ "
                + "{\"action\":\"expect\",\"target\":{\"css\":\"#orderNo\",\"chain\":[\"css\"]},"
                + "\"matchType\":\"regex\",\"pattern\":\"^PO\\\\d{8}$\"}\n"
                + "样例4 退化 contains：预期「单号形如当天日期+流水号」regex 无把握时 → "
                + "{\"action\":\"expect\",\"target\":{\"css\":\"#orderNo\",\"chain\":[\"css\"]},"
                + "\"matchType\":\"contains\",\"description\":\"断言单号形态（需人工精化）\"}"
                + "（跨字段运算语义则改出 code.step 候选+description 标注「待 B8」，禁 range 凑合）\n");
        // Honest-marking prompt clause: mirrors the SQL-step "no retrieval
        // evidence" discipline onto UI/HTTP steps. Gated by BOTH the gate
        // master and the rule key (same read shape as the stage2 audit call
        // site, so the prompt never promises an enforcement that is off);
        // off = byte-identical legacy prompt (R32 factory-on, absent row on).
        if (com.sixbean.testpike.aiservice.llm.domain.component.SysConfigBooleans.parse(
                    coreGateway.getSysConfig(FIDELITY_GATE_KEY), true)
                && com.sixbean.testpike.aiservice.llm.domain.component.SysConfigBooleans.parse(
                    coreGateway.getSysConfig(NO_TRUTH_HONEST_MARK_KEY), true)) {
            sb.append("无据诚实标注纪律：若无页面探查候选段且接口契约/元素台账未命中，步骤中的 URL、"
                    + "定位符与期望值字面均无真值依据——必须在该步 description 末尾标注「需人工精化」"
                    + "（与 SQL 步「检索无据标注」同一纪律）；未标注的无据字面值会被忠实核对检出并退回"
                    + "定向修订。候选段在场时定位以候选原文为准，禁另造。\n");
        }
        sb.append("四层联合校验映射（04 契约 §2.1.2，步骤/预期含界面/接口/落库语义时按此选动作）：\n");
        sb.append("- 界面报错语义（控制台不应有错误/无 JS 报错）→ console.assert，"
                + "level 默认 [error]，已知无害噪音进 ignorePatterns（glob，禁滥用白名单掩盖真错）\n");
        sb.append("- 请求观测语义（应发请求/不该发请求/接口返回码）→ net.assert，"
                + "absent=true 断言「不该发请求」（负向一等公民），expect.body 仅 JsonPath 子集 "
                + "$.a.b[0].c（禁 filter/script）\n");
        sb.append("- 异步等待/旁路调用语义（触发后台任务后等终态）→ http.request + poll"
                + "（interval≥1s 下限钳制、timeout 吃 K8），期望不符=product_bug、连接失败=env\n");
        sb.append("变量链纪律（v2.22，04 §2.1.2 全链变量贯通）：仅 saveAs 与 UI expect capture "
                + "可声明新变量；引用写 {{VAR:步名.路径}}（如 {{VAR:resp1.data.id}}），"
                + "只许引用前序已声明变量——未定义引用校验即拒（生成端自检，禁先引用后声明）；"
                + "可出现在任意动作任意字符串字段（input value=UI 填值主场景）\n");
        sb.append("时序三分表（13 §10，禁混用）：console.assert / net.assert=即时一次判定"
                + "（窗口归因到步、不重试，异步结果由显式 wait/http poll 步前置保证）；"
                + "http.request(poll)=轮询等待；B10 expect+matchType=取值判定不等待"
                + "（元素出现即判一次，值不稳定先加 wait 步）\n");
        if (sqlGenOn) {
            sb.append("- 落库核对语义（数据写入表/状态落库）→ sql.assert + retry（本族=轮询等待，"
                    + "UI 操作后接落库校验必须用本族，禁取值判定凑）；conn=连接名 "
                    + "{{ENV:DB_SIT}} 形态，sql 首关键字只读 SELECT，参数 :name 具名绑定强制"
                    + "（禁把占位文本硬编进 SQL）；表名列名以下方「表结构参考」检索段为据，"
                    + "禁凭记忆猜——检索无据时该步 description 标注「需人工精化」\n");
            sb.append("- setup 造数取回自增 id → sql.query + saveAs（只读取值入变量域）\n");
            sb.append("- sql.exec（造数/清理 DML）AI 禁自动产：只许人工显式编写，产物中出现即校验拒\n");
        } else {
            sb.append("- 落库核对语义：本系统 SQL 生成开关当前关闭——禁硬编 SQL 产物，"
                    + "该步输出 expect 界面断言并在 description 末尾标注"
                    + "「落库校验需人工补充 SQL 模板通道」；sql.exec AI 禁自动产（恒拒）\n");
        }
        sb.append("非 UI 断言黄金样例（输入→期望产物骨架对照）：\n");
        sb.append("样例5 console 噪音白名单：预期「下单流程无 JS 错误，第三方埋点报错除外」→ "
                + "{\"action\":\"console.assert\",\"level\":[\"error\"],"
                + "\"ignorePatterns\":[\"*analytics*\"],\"mode\":\"fail\",\"scope\":\"sinceLast\"}\n");
        sb.append("样例6 net 负向：预期「未勾选协议时点击提交不应发出提交请求」→ "
                + "{\"action\":\"net.assert\",\"match\":{\"urlPattern\":\"*/order/submit\","
                + "\"method\":\"POST\"},\"absent\":true}\n");
        sb.append("样例7 http+变量链：setup 造单回填表单 → "
                + "{\"action\":\"http.request\",\"method\":\"POST\",\"url\":\"{{ENV:API_BASE}}/order\","
                + "\"body\":{\"sku\":\"TEST-01\"},\"expect\":{\"status\":200},\"saveAs\":\"resp1\"}，"
                + "后续步骤值写 \"value\":\"{{VAR:resp1.data.orderNo}}\"（先声明后引用）\n");
        if (sqlGenOn) {
            sb.append("样例8 sql 轮询区间：预期「订单状态 30s 内落库为 PAID」→ ")
                    .append("{\"action\":\"sql.assert\",\"conn\":\"{{ENV:DB_SIT}}\",\"sql\":")
                    .append("\"SELECT status FROM t_order WHERE order_no = :orderNo\",")
                    .append("\"params\":{\"orderNo\":\"{{VAR:resp1.data.orderNo}}\"},")
                    .append("\"expect\":{\"type\":\"scalar\",\"equals\":\"PAID\"},")
                    .append("\"retry\":{\"intervalMs\":2000,\"timeoutMs\":30000}}\n")
                    .append("样例9 全链（造数→取值→填 UI→回读断言）：sql.query 取回 id saveAs=row → ")
                    .append("input \"value\":\"{{VAR:row.id}}\" → expect capture 回读；")
                    .append("造数/清理属 DML 由人工通道准备，禁产 sql.exec 步骤\n");
        }
        // the table-structure segment rides its own truth gate
        // (decoupled from the SQL-action whitelist above) — grounding is always
        // welcome; with the gate off ddlSegment is "" so the legacy bytes hold.
        if (ddlInjectionOn && ddlSegment != null && !ddlSegment.isBlank()) {
            sb.append(ddlSegment).append('\n');
        }
        if (!feedback.isEmpty()) {
            sb.append("上次脚本不合格（请逐条修正后重新输出）：\n");
            for (String f : feedback) {
                sb.append(" - ").append(f).append('\n');
            }
        }
        // WE piece C: the confirmed page-element summary rides the built-in
        // branch tail only — the template path keeps its contract slots
        // untouched (rendering stays content-driven); gate off / empty ledger
        // hand back "" so the legacy bytes hold byte for byte.
        if (ledgerSummarySegment != null && !ledgerSummarySegment.isBlank()) {
            sb.append(ledgerSummarySegment);
        }
        return sb.toString();
    }

 
    private CoreGateway.PromptTemplateBrief templateRowOrNull(String name, long projectId,
                                                              long taskId, boolean pin) {
        if (promptTemplateResolver == null) {
            return null;
        }
        return promptTemplateResolver.selectTemplate(name, projectId, taskId, pin).orElse(null);
    }

    /**
 * assembly, budget face (fail-soft: piece not
 * wired / no anchor / core unreachable / empty pull = "", the stage-1
 * prompt then stays byte-identical to the legacy shape). The injection
 * budget reads its OWN hot key ai.gen.original-block-max-tokens (factory
 * 8,000 tokens, window 256~100,000 shared with the core write side)
 * instead of borrowing the extraction summary envelope; a garbage or
 * out-of-window raw value fails closed to the factory and names the key
 * in a WARN. A pull that yields chunks yet injects zero bytes raises the
 * counted injection-gap WARN — the acceptance anchor that must stay at
 * zero (ruling 8 live incident: 642 chunks pulled, nothing injected).
 */
    private String originalSegmentFor(CoreGateway.RequirementBrief requirement) {
        if (originalTextBlockAssembler == null || requirement == null
                || requirement.sourceAnchor() == null) {
            return "";
        }
        CoreGateway.SourceAnchor stored = requirement.sourceAnchor();
        OriginalTextBlockAssembler.Anchor anchor = new OriginalTextBlockAssembler.Anchor(
                stored.documentId(), stored.chunkIds(), stored.evidence());
        if (anchor.empty()) {
            return "";
        }
        List<Map<String, Object>> chunks;
        try {
            chunks = coreGateway.getDocumentChunks(anchor.documentId());
        } catch (Exception e) {
            log.warn("原文区块反查取块失败 requirementId={} documentId={}（本轮零注入，生成不中断）: {}",
                    requirement.id(), anchor.documentId(), e.getMessage());
            return "";
        }
        if (chunks == null || chunks.isEmpty()) {
            log.info("原文区块反查 documentId={} requirementId={} 取块=0 本轮零注入",
                    anchor.documentId(), requirement.id());
            return "";
        }
        String rawBudget = coreGateway.getSysConfig(
                OriginalTextBlockAssembler.KEY_ORIGINAL_BLOCK_MAX_TOKENS);
        Integer budgetTokens = OriginalTextBlockAssembler.tokensOrNull(rawBudget);
        if (budgetTokens == null && rawBudget != null && !rawBudget.isBlank()) {
            log.warn("原文区块注入预算键 {} 值非法或越界（当前 {}），按出厂 {} 回退（热更即生效）",
                    OriginalTextBlockAssembler.KEY_ORIGINAL_BLOCK_MAX_TOKENS, rawBudget,
                    OriginalTextBlockAssembler.DEFAULT_ORIGINAL_BLOCK_MAX_TOKENS);
        }
        int budgetChars = OriginalTextBlockAssembler.budgetCharsOf(
                budgetTokens != null ? budgetTokens
                        : OriginalTextBlockAssembler.DEFAULT_ORIGINAL_BLOCK_MAX_TOKENS);
        // : the widened chapter-aligned window rides its own hot key
        // (factory off = legacy ±1 bytes); absent/dirty rows read off via the
        // shared boolean reader, so the zero-drift face never depends on the
        // row existing at all.
        boolean chapterAligned = com.sixbean.testpike.aiservice.llm.domain.component
                .SysConfigBooleans.on(coreGateway.getSysConfig(
                        OriginalTextBlockAssembler.KEY_CHAPTER_ALIGNED_WINDOW));
        String block = originalTextBlockAssembler.assemble(anchor, chunks, budgetChars,
                chapterAligned);
        if (block.isEmpty()) {
            log.warn("原文区块注入缺口 requirementId={} documentId={} 取块={} 注入=0 预算chars={} 锚chunkIds={}（命中块缺失或陈旧，本轮零注入，提示词不变更）",
                    requirement.id(), anchor.documentId(), chunks.size(),
                    budgetChars, anchor.chunkIds());
        } else {
            log.info("原文区块反查 requirementId={} documentId={} 取块={} 注入=1 预算chars={} 锚chunkIds={}",
                    requirement.id(), anchor.documentId(), chunks.size(),
                    budgetChars, anchor.chunkIds());
        }
        return block;
    }

    
    private String feedbackBlock(List<String> feedback, String header) {
        // AP-4 single source: identical rendering lives in RegenerationFeedback
        // (shared with the F2 extraction regeneration loop — per-chain copies drift).
        return com.sixbean.testpike.aiservice.llm.domain.component
                .RegenerationFeedback.render(feedback, header);
    }

    
    private String caseBlock(GeneratedCase generated) {
        StringBuilder sb = new StringBuilder();
        sb.append("用例标题：").append(generated.title()).append('\n');
        sb.append("前置条件：").append(generated.precondition()).append('\n');
        sb.append("步骤：\n");
        for (GeneratedStep step : generated.steps()) {
            sb.append(step.stepNo()).append(". ").append(step.description())
                    .append(step.isAssert() == 1 ? "（断言）" : "").append('\n');
        }
        sb.append("预期结果：").append(generated.expectedResult()).append('\n');
        return sb.toString();
    }

    /** Governance key: case-generation stage-1 behaviour directives (factory
     *  default on, hot-reloaded; a published template row must retain the
     *  STAGE1_CASE marker and the JSON output contract). */
    public static final String KEY_STAGE1_DIRECTIVES_ENABLED = "ai.gen.stage1-directives.enabled";
    /** Template row name of the behaviour-directive family consumed by stage 1. */
    public static final String STAGE1_TEMPLATE_NAME = "case-gen-stage1-directives";
    /** Template row domain of the behaviour-directive family consumed by stage 1. */
    public static final String STAGE1_TEMPLATE_DOMAIN = "case-gen";

    /**
 * Behaviour-kernel head for case-generation stage 1 (generation-pipeline
 * generalisation): the gate / published-row / degrade sequence is
 * single-sourced in BehaviorKernelAssembly (R40). The per-task
 * output-language instruction is re-attached after assembly because an
 * operator-published template row is language-neutral; the fallback path
 * keeps the same final head shape the domain had before this segment
 * (contract + kernel invariant + language line).
 */
    String stage1DirectivesHead(String lang) {
        String contract = "你是资深测试工程师，请按 JSON 格式输出结构化测试用例（标记 "
                + LlmExtractMarkers.STAGE1_CASE + "）。";
        return BehaviorKernelAssembly.assembleHead(
                coreGateway.getSysConfig(KEY_STAGE1_DIRECTIVES_ENABLED),
                () -> coreGateway.promptTemplateLatestByStatus(STAGE1_TEMPLATE_NAME,
                        STAGE1_TEMPLATE_DOMAIN, ChatBehaviorDirectives.PUBLISHED_STATUS),
                contract,
                "用例生成 stage1 行为指令模板读路径不可用，本轮生成回退内置契约")
                + "\n" + langInstruction(lang);
    }

    private String langInstruction(String lang) {
        return "zh-TW".equals(lang) ? "请使用繁体中文输出用例内容。" : "请使用简体中文输出用例内容。";
    }

    private String aggregateText(GeneratedCase c) {
        StringBuilder sb = new StringBuilder(c.title()).append(' ').append(c.expectedResult()).append(' ');
        for (GeneratedStep step : c.steps()) {
            sb.append(step.description()).append(' ');
        }
        return sb.toString();
    }

 
    private void handleFailure(long taskId, long projectId, long operatorId,
                               String requirementText, Exception e) {
        String code;
        String msg;
        if (e instanceof ServiceException se) {
            int bizCode = se.getCode();
            if (bizCode == BizErrorCode.AI_TIMEOUT) {
                code = "MODEL_TIMEOUT";
                msg = "模型调用超时，请稍后重试";
            } else if (bizCode == BizErrorCode.AI_FORMAT_ERROR || bizCode == BizErrorCode.AI_PROVIDER_ERROR) {
                code = "FORMAT_ERROR";
                msg = se.getMessage();
            } else {
                code = "SERVICE_ERROR";
                msg = "AI 生成服务异常：" + se.getMessage();
            }
        } else {
            code = "SERVICE_ERROR";
            msg = "AI 生成服务异常：" + e.getMessage();
        }
        try {
            coreGateway.markFailed(taskId, code, truncate(msg, 495));
            coreGateway.createNotification(operatorId, projectId, "AI_GEN_FAILED",
                    "AI 用例生成失败",
                    "需求「" + truncate(requirementText, 40) + "」生成失败：" + msg + "，可一键重试",
                    "ai-gen-fail:" + taskId);
        } catch (Exception notifyEx) {
            // R53 noise fix (defect ledger, 2026-11-07): a task deleted while
            // the generation was in flight makes the failure write-back land on
            // a row that no longer exists — core's not-found rejection is the
            // expected race outcome, so the shared entity-gone vocabulary
            // (common EntityGoneSignals, the one source, R40) demotes it to a
            // WARN naming the skip. The in-app notice is skipped with it: its
            // retry anchor "ai-gen-fail:taskId" has no host any more. Any other
            // write-back outage keeps its ERROR signature — a real persistence
            // failure must never be folded into the race noise (same posture
            // as DocExtractServiceImpl.failExtraction).
            if (EntityGoneSignals.matches(notifyEx)) {
                log.warn("AI 生成失败回写跳过（任务已被删除，失败通知无宿主） taskId={} reason={}",
                        taskId, notifyEx.getMessage());
            } else {
                log.error("AI 失败通知写入异常 taskId={}", taskId, notifyEx);
            }
        }
        log.warn("AI 生成任务失败 taskId={} code={} msg={}", taskId, code, msg);
        // The terminal failure event lands through the same seam as before
        // failure event goes through the single-source formatter so the drawer
        // always ends with category + suggestion + retry entry, never a bare
        // "failed" line. The same fail-soft seam posture as recordProcessEvent:
        // legacy wiring without the store is a silent zero-touch no-op.
        com.sixbean.testpike.aiservice.taskstream.domain.component.TaskFailureEvent terminal =
                com.sixbean.testpike.aiservice.taskstream.domain.component
                        .TaskFailureEventFormatter.format(e, "任务详情页「重试生成」按钮");
        if (processEvents != null) {
            processEvents.record(PROCESS_JOB_KIND, String.valueOf(taskId),
                    String.valueOf(taskId), "error", terminal.summary(), terminal.detailJson());
        }
    }

    /**
 * Appends one process event of a steps-gen run into the shared job
 * ledger (tp_ai_task_event, single-source door). The keys mirror
 * the core read window exactly: job_kind = the frozen literal,
 * job_ref = run_token = the task id. An uninstalled door (legacy
 * wiring without the seam) is a silent zero-touch no-op; the store
 * itself swallows write outages, so bookkeeping never fails the
 * generation run and no extra try/catch layer is added here (R40).
 */
    private void recordProcessEvent(long taskId, String level, String summary) {
        if (processEvents == null) {
            return;
        }
        processEvents.record(PROCESS_JOB_KIND, String.valueOf(taskId),
                String.valueOf(taskId), level, summary, null);
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
