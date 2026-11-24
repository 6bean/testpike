package com.sixbean.testpike.core.webelement.api.controller;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Anti-regression guard for the process-event tail window wording.
 * The adjudication behind the business three-element rule lives in
 * docs/change-eval (task ledger maps it); this file carries no task tokens.
 *
 * Frames written through TaskEventWriter are rendered verbatim in the tail
 * drawer, so their literals must read as business language (object + action +
 * progress) and must never carry implementer narration. This test scans the
 * call-site window of every taskEvents.step / taskEvents.error producer in the
 * core module for three violation shapes:
 *   1. closed set of known engineering phrases (see ENGINEERING_PHRASES),
 *   2. translated-tone English prose runs (4+ consecutive words, no CJK),
 *   3. internal bookkeeping verbs on screen (see INTERNAL_BOOKKEEPING_WORDS).
 *   The Chinese sample terms live in the word lists below, never in comments.
 * Machine-readable key=value details are exempt by design: each such segment
 * is one token, never a four-word run.
 *
 * Bidirectional self-test (R43): the detector must flag a planted violation
 * and stay silent on the accepted BatchFinalizeSupport frame shape.
 */
class ProcessEventWordingGuardTest {

    private static final List<String> ENGINEERING_PHRASES = List.of(
            "dispatch door", "tail follows", "cursor contract", "viewport tiers");

    /** Frame texts assembled above the producer call: an assignment whose
     *  right side opens a string literal, or an append/add/format feeding a
     *  frame buffer. Only such lines ahead of the call enter the window;
     *  ordinary statements there belong to the implementation face. */
    private static final Pattern FRAME_ASSIGN = Pattern.compile(
            "\\b\\w+\\s*(\\+?=)\\s*(\"|String\\.format\\()|\\b\\w+\\.(append|add)\\((\"|String\\.format\\()");

    /** Developer-log statement heads (head line plus its continuation lines
     *  are exempt below): the log face is written for the engineer and is
     *  never rendered into operator frames. */
    private static final Pattern DEV_LOG_CALL = Pattern.compile(
            "\\b(?:log|LOG|logger|LOGGER)\\s*\\.\\s*(?:info|warn|error|debug|trace)\\s*\\(");

    /** Producer call markers per module face: core writes through taskEvents,
     *  the exploration kernel through recordProcessEvent / the shared store. */
    private static final List<String> PRODUCER_MARKERS = List.of(
            "taskEvents.step(", "taskEvents.error(",
            "recordProcessEvent(", "processEvents.record(",
            "recordProcessEventQuiet(");

    /** Validator throw/return sites write straight into an on-screen message
     *  (form hint or tail-frame error line), so their literals follow the same
     *  business-wording contract as producer frames (core script-domain sweep). */
    private static final List<String> VALIDATOR_MESSAGE_MARKERS = List.of(
            "new ServiceException(", "return \"");

    private static final List<String> INTERNAL_BOOKKEEPING_WORDS = List.of(
            "落账", "游标", "封账", "零出站", "拒止",
            "循环", "预算", "黑名单", "派发",
            "契约", "落库", "收敛", "机械缺项", "环用尽", "乐观锁", "确认环",
            // doc-extract / spec faces join the ratchet as their wording lands:
            "入库", "回指", "直提", "最终桶", "封组", "复核轮", "挂账", "可见输出片段",
            // survey frames landed last: the ledger verbs read as bookkeeping, not
            // as what happened to the elements the operator is watching.
            "入账", "候选池",
            // shared gap-filler notes render through the gen tail frames (W8):
            // the removal verb must stay phrased as what the operator sees.
            "机械摘除",
            // tool-catalog and blank-step residues joined with the W10a sweep:
            // container jargon for deferred tools, and the implementer-view
            // placeholder wording for a step that returned nothing.
            "未常驻", "无可见输出",
            // validator message faces joined the ratchet with the core
            // script-domain sweep: the translated-tone must-give verb reads
            // like a compiler error, not a fix instruction (sample terms stay
            // in the word list, never in comments).
            "必须给定");

    /** Four or more consecutive English words inside one literal = implementer narration. */
    private static final Pattern ENGLISH_PROSE_RUN =
            Pattern.compile("[A-Za-z][A-Za-z0-9_\\-]*(\\s+[A-Za-z][A-Za-z0-9_\\-]*){3,}");

    /**
     * Statement-shaped SQL is sample data sent to the model, not operator-facing
     * narration; it must stay silent under the English-prose rule (a prompt's
     * golden sample like SELECT ... FROM ... WHERE ... is code, not prose).
     */
    private static final Pattern SQL_STATEMENT_SHAPE =
            Pattern.compile("(?i)\\b(SELECT|INSERT|UPDATE|DELETE)\\b.{0,200}\\b(FROM|INTO|SET|WHERE)\\b");

    /**
     * Clause-headed SQL fragments (an ORDER BY builder returning " ORDER BY
     * id DESC") are assembly-face code strings that never reach an operator
     * frame; they stay silent under the English-prose rule just like full
     * golden-sample statements.
     */
    private static final Pattern SQL_CLAUSE_SHAPE =
            Pattern.compile("(?i)^\\s*(ORDER BY|GROUP BY|HAVING|LIMIT|OFFSET|WHERE \\S|SET \\S|JOIN \\S)");

    @Test
    void processEventProducersCarryNoImplementerWording() throws IOException {
        Path repo = locateRepoRoot();
        // Faces joined this list as their wording packages landed (ledger ratchet):
        // survey at W1, explore at W2, gen at W3, doc-extract/spec at W4,
        // qa-gen (judge) + perfinsight at W5; the remaining step-consumer
        // faces join at the W6 full sweep.
        List<Path> faces = List.of(
                repo.resolve("core/src/main/java"),
                repo.resolve("ai-service/src/main/java/com/sixbean/testpike/aiservice/explore"),
                repo.resolve("ai-service/src/main/java/com/sixbean/testpike/aiservice/gen"),
                repo.resolve("ai-service/src/main/java/com/sixbean/testpike/aiservice/doc"),
                repo.resolve("ai-service/src/main/java/com/sixbean/testpike/aiservice/spec"),
                repo.resolve("ai-service/src/main/java/com/sixbean/testpike/aiservice/judge"),
                repo.resolve("ai-service/src/main/java/com/sixbean/testpike/aiservice/perfinsight"),
                // The shared failure-event formatter renders on-screen too
                // (taskstream face joins the full sweep at W6).
                repo.resolve("ai-service/src/main/java/com/sixbean/testpike/aiservice/taskstream"),
                // tool face joins the ratchet with W10a: the catalog note and the
                // search-tool description render through function-call frames.
                repo.resolve("ai-service/src/main/java/com/sixbean/testpike/aiservice/tool"));
        List<String> violations = new ArrayList<>();
        for (Path face : faces) {
            try (var walk = Files.walk(face)) {
                List<Path> producers = walk
                        .filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> {
                            try {
                                String src = Files.readString(p);
                                return PRODUCER_MARKERS.stream().anyMatch(src::contains);
                            } catch (IOException e) {
                                throw new IllegalStateException(e);
                            }
                        })
                        .toList();
                for (Path file : producers) {
                    violations.addAll(detect(face.relativize(file).toString() + ":",
                            Files.readString(file)));
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "过程事件上屏文案含实现者话术（业务三要素缺失或禁词命中）:\n" + String.join("\n", violations));
    }

    @Test
    void coreScriptDomainValidatorMessagesCarryNoImplementerWording() throws IOException {
        Path repo = locateRepoRoot();
        // Faces joined this ratchet as their wording landed (W19): the three
        // validators that write hints and tail-frame error lines directly.
        List<Path> files = List.of(
                repo.resolve("core/src/main/java/com/sixbean/testpike/core/script"
                        + "/domain/component/NonUiStepValidator.java"),
                repo.resolve("core/src/main/java/com/sixbean/testpike/core/apitest"
                        + "/domain/component/ApiAssertionFormContract.java"),
                repo.resolve("core/src/main/java/com/sixbean/testpike/core/perf"
                        + "/domain/component/PerfStepActionGate.java"),
                // W20 sweep: the shared action-contract refusals render through
                // the same step forms and tail frames, so the six common files
                // that carried must-give style wording join the ratchet pinned
                // on this face list.
                repo.resolve("common/src/main/java/com/sixbean/testpike/common"
                        + "/util/ActionJsonValidator.java"),
                repo.resolve("common/src/main/java/com/sixbean/testpike/common"
                        + "/util/CodeStepContract.java"),
                repo.resolve("common/src/main/java/com/sixbean/testpike/common"
                        + "/util/SqlStatementContract.java"),
                repo.resolve("common/src/main/java/com/sixbean/testpike/common"
                        + "/util/ExpectMatchContract.java"),
                repo.resolve("common/src/main/java/com/sixbean/testpike/common"
                        + "/util/NonUiActionContract.java"),
                repo.resolve("common/src/main/java/com/sixbean/testpike/common"
                        + "/mock/MockMatchEngine.java"),
                // Perf dispatch family and the script-limit / promotion
                // refusals join here: their ServiceException texts render as
                // operator-facing refusals and tail-frame error lines too.
                repo.resolve("core/src/main/java/com/sixbean/testpike/core/perf"
                        + "/domain/service/impl/PerfScenarioServiceImpl.java"),
                repo.resolve("core/src/main/java/com/sixbean/testpike/core/perf"
                        + "/domain/component/PerfCapacityPreflight.java"),
                repo.resolve("core/src/main/java/com/sixbean/testpike/core/perf"
                        + "/domain/component/PerfEngineRoutingSupport.java"),
                repo.resolve("core/src/main/java/com/sixbean/testpike/core/perf"
                        + "/domain/component/PerfRunDispatchSupport.java"),
                repo.resolve("core/src/main/java/com/sixbean/testpike/core/perf"
                        + "/domain/component/PerfRunDispatcher.java"),
                repo.resolve("core/src/main/java/com/sixbean/testpike/core/perf"
                        + "/domain/component/PerfDatasetHealthCheck.java"),
                repo.resolve("core/src/main/java/com/sixbean/testpike/core/perf"
                        + "/domain/component/GatlingAdapter.java"),
                repo.resolve("core/src/main/java/com/sixbean/testpike/core/perf"
                        + "/api/controller/PerfArtifactInternalController.java"),
                repo.resolve("core/src/main/java/com/sixbean/testpike/core/script"
                        + "/domain/component/ScriptLimitGuard.java"),
                repo.resolve("core/src/main/java/com/sixbean/testpike/core/apitest"
                        + "/domain/component/PromotionRefusals.java"));
        List<String> violations = new ArrayList<>();
        for (Path file : files) {
            violations.addAll(detect(file.getFileName() + ":",
                    Files.readString(file), VALIDATOR_MESSAGE_MARKERS));
        }
        assertTrue(violations.isEmpty(),
                "校验器上屏文案含实现者话术（直译腔/禁词命中，缺业务三要素）:\n"
                        + String.join("\n", violations));
    }

    @Test
    void siteSurveyKickoffFrameNamesProfileAndAction() throws IOException {
        String src = Files.readString(locateRepoRoot()
                .resolve("core/src/main/java/com/sixbean/testpike/core/webelement"
                        + "/api/controller/SiteSurveyRunInternalController.java"));
        assertTrue(src.contains("已发起｜站点档案 "),
                "整站测绘发起帧缺少业务锚点（站点档案 + 动作语义）");
        assertTrue(src.contains("逐页采集"),
                "整站测绘发起帧缺少后续动作语义");
        assertTrue(src.contains("\"profileId="),
                "整站测绘发起帧的机读明细应收回 key=value 形态");
    }

    @Test
    void pageSurveyKickoffFrameNamesUrlAndAction() throws IOException {
        String src = Files.readString(locateRepoRoot()
                .resolve("core/src/main/java/com/sixbean/testpike/core/webelement"
                        + "/api/controller/WebElementInternalController.java"));
        assertTrue(src.contains("已发起｜"),
                "整页测绘发起帧缺少动作语义");
        assertTrue(src.contains("采集页面元素"),
                "整页测绘发起帧缺少用户可懂的采集动作说明");
        assertTrue(src.contains("\"url="),
                "整页测绘发起帧的机读明细应收回 key=value 形态");
    }

    @Test
    void siteSurveyStepFrameCarriesPageProgress() throws IOException {
        String src = Files.readString(locateRepoRoot().resolve(
                "core/src/main/java/com/sixbean/testpike/core/explore"
                        + "/domain/service/impl/ExploreServiceImpl.java"));
        assertTrue(src.contains("surveyPageCounter"),
                "整站测绘步骤帧必须带页进度分母件（缺件即红）");
        assertTrue(src.contains("\"｜第 \"") && src.contains("maxPages"),
                "步骤帧分母形态=第 已采页/页数上限（读派发快照，禁猜数）");
    }

    @Test
    void docExtractStartFrameNamesTheDocument() throws IOException {
        String src = Files.readString(locateRepoRoot().resolve(
                "ai-service/src/main/java/com/sixbean/testpike/aiservice/doc"
                        + "/domain/service/impl/DocExtractServiceImpl.java"));
        assertTrue(src.contains("提取启动｜文档「"),
                "文档提取启动帧必须点名对象（哪个页面+动作语义+对哪份文档）");
        assertTrue(src.contains("extractDocumentTitle"),
                "启动帧文档名必须经单源读取件，读不到时落编号标签禁裸泛称");
    }

    @Test
    void exploreKickoffAndExitFramesCarryBusinessThreeElements() throws IOException {
        String src = Files.readString(locateRepoRoot().resolve(
                "ai-service/src/main/java/com/sixbean/testpike/aiservice/explore"
                        + "/domain/component/ExploreLoopKernel.java"));
        assertTrue(src.contains("已发起自动浏览"),
                "探索发起帧缺业务动作语义（应点名对象+动作+进度上限）");
        assertTrue(src.contains("步数上限"),
                "探索过程帧缺进度分母");
        assertTrue(src.contains("自动浏览结束"),
                "探索出口帧缺业务收口语义");
        assertTrue(src.contains("ExploreActionNaming.humanLabel"),
                "步骤帧必须经人话动作名单源（禁裸工具名上屏）");
    }

    @Test
    void genFramesCarryBusinessWordingMapping() throws IOException {
        String src = Files.readString(locateRepoRoot().resolve(
                "ai-service/src/main/java/com/sixbean/testpike/aiservice/gen"
                        + "/domain/service/impl/AiGenServiceImpl.java"));
        assertTrue(src.contains("按缺项清单补写："),
                "gen 面缺项补写帧必须点名动作语义（按缺项清单补写）");
        assertTrue(src.contains("通过脚本校验"),
                "gen 面补写成功帧应使用业务说法（脚本校验）");
        assertTrue(src.contains("未完成核验"),
                "gen 面核验状态帧应使用业务说法（未完成核验）");
        assertFalse(src.contains("落库被脚本静态校验拒"),
                "gen 面修复轮帧仍带内部落库话术");
    }

    @Test
    void docExtractFramesCarryBusinessWordingMapping() throws IOException {
        String src = Files.readString(locateRepoRoot().resolve(
                "ai-service/src/main/java/com/sixbean/testpike/aiservice/doc"
                        + "/domain/service/impl/DocExtractServiceImpl.java"));
        assertTrue(src.contains("逐组原文提取并保存"),
                "doc 面概览缺位帧应使用业务说法（提取并保存）");
        assertTrue(src.contains("证据校验拦截"),
                "doc 面证据校验帧应使用业务说法（证据校验拦截）");
        assertTrue(src.contains("在原文中找不到对应段落"),
                "doc 面证据校验帧应点名用户可懂的原因（找不到对应段落）");
        assertFalse(src.contains("落库闸"),
                "doc 面仍带内部落库闸话术");
        assertFalse(src.contains("最终桶"),
                "doc 面进度帧仍带容器黑话（桶）");
    }

    @Test
    void qaGenGroupFrameKeepsProgressAndMovesTokenTotalsToDetail() throws IOException {
        String src = Files.readString(locateRepoRoot().resolve(
                "ai-service/src/main/java/com/sixbean/testpike/aiservice/judge"
                        + "/domain/service/impl/EvalQuestionGenServiceImpl.java"));
        assertTrue(src.contains("组完成，累计问答对 "),
                "qa-gen 组进度帧应保留 N/M 进度与问答对计数");
        assertFalse(src.contains("（tokens "),
                "qa-gen 组进度帧的 token 合计应收回机读 detail，不再堆在 summary 上屏行");
        assertTrue(src.contains("promptTokens="),
                "qa-gen 组进度帧的 token 合计应以 key=value 形态落 detail");
    }

    @Test
    void perfInsightThinkingFramesNameTheRunAndDropRawTotals() throws IOException {
        String src = Files.readString(locateRepoRoot().resolve(
                "ai-service/src/main/java/com/sixbean/testpike/aiservice/perfinsight"
                        + "/domain/service/impl/PerfInsightServiceImpl.java"));
        assertTrue(src.contains("正在写归因解读"),
                "perfinsight 应答帧缺动作语义（应说明正在写归因解读）");
        assertFalse(src.contains("输入 \" + handle.result().promptTokens()"),
                "perfinsight 应答帧的 token 明细应收回 detailJson，不留在上屏行");
        assertFalse(src.contains("契约"),
                "perfinsight 上屏错误句仍带内部契约话术");
    }

    @Test
    void failureSuggestionFramesCarryNoEngineeringVerb() throws IOException {
        String src = Files.readString(locateRepoRoot().resolve(
                "ai-service/src/main/java/com/sixbean/testpike/aiservice/taskstream"
                        + "/domain/component/TaskFailureEventFormatter.java"));
        assertTrue(src.contains("仍未通过校验"),
                "失败建议帧应使用业务说法（校验未通过），用户才看得懂下一步");
        assertFalse(src.contains("收敛"),
                "失败建议帧仍带内部工程说法（收敛）");
        assertFalse(src.contains("落库"),
                "失败建议帧仍带内部落库话术");
    }

    @Test
    void surveyOutcomeCountsReadAsBusinessLabels() throws IOException {
        Path collector = locateRepoRoot().resolve("core/src/main/java/com/sixbean/testpike/core"
                + "/webelement/domain/component/WebElementSurveyCollector.java");
        String enumSrc = Files.readString(collector);
        for (String label : List.of("新增元素", "补充定位", "重复跳过", "保留已有", "跳过已下线")) {
            assertTrue(enumSrc.contains("\"" + label + "\""),
                    "测绘台账结果的屏显标签缺失: " + label);
        }
        String frameSrc = Files.readString(locateRepoRoot().resolve(
                "core/src/main/java/com/sixbean/testpike/core/explore/domain/service/impl"
                        + "/ExploreServiceImpl.java"));
        assertTrue(frameSrc.contains("action().screenLabel()"),
                "测绘步骤帧应读取业务标签");
        assertFalse(frameSrc.contains("action().name()"),
                "测绘步骤帧不得把枚举常量名送上屏");
    }

    @Test
    void sharedGapFillerAndValidatorNotesCarryNoInternalWording() throws IOException {
        Path repo = locateRepoRoot();
        String gapFiller = Files.readString(repo.resolve("common/src/main/java/com/sixbean"
                + "/testpike/common/genkit/ScriptMechanicalGapFiller.java"));
        String validator = Files.readString(repo.resolve("common/src/main/java/com/sixbean"
                + "/testpike/common/util/ActionJsonValidator.java"));
        // These notes reach the on-screen tail frames through the shared
        // gen formatter, so they follow the same business-wording contract.
        for (String src : List.of(gapFiller, validator)) {
            assertFalse(src.contains("机械摘除"),
                    "共享缺项补写/校验件仍带内部摘除话术");
            assertFalse(src.contains("契约违例"),
                    "共享校验件仍带内部契约话术");
            assertFalse(src.contains("落库校验"),
                    "共享缺项补写件仍带内部落库话术");
        }
        assertTrue(gapFiller.contains("本步数据库校验已跳过"),
                "数据库缺项提示应使用业务说法（本步已跳过）并给处理办法");
        assertTrue(gapFiller.contains("环境管理"),
                "数据库缺项提示必须点名配置入口（R63 三要素）");
        assertTrue(validator.contains("格式不符"),
                "校验拒件句应使用业务说法（格式不符）");
        assertFalse(validator.contains("回退 contains"),
                "拒件建议句仍带内部回退话术，应改说改用");
    }

    @Test
    void toolCatalogFramesCarryNoContainerJargon() throws IOException {
        String src = Files.readString(locateRepoRoot().resolve(
                "ai-service/src/main/java/com/sixbean/testpike/aiservice/tool"
                        + "/domain/component/FcToolCatalog.java"));
        assertFalse(src.contains("未常驻"),
                "工具目录帧仍带请求容器黑话（常驻是装载形态，用户只需知道怎么用）");
        assertTrue(src.contains("当前会话没有的能力"),
                "工具检索说明应点名能力边界（当前会话没有的能力先检索）");
        assertTrue(src.contains("需先检索后使用"),
                "工具目录注记必须给业务动作语义（需先检索后使用）");
    }

    @Test
    void blankStepDigestIsSharedAndReadsAsBusiness() throws IOException {
        Path repo = locateRepoRoot();
        String shared = Files.readString(repo.resolve("ai-service/src/main/java/com/sixbean"
                + "/testpike/aiservice/taskstream/domain/component/ProcessEventDigest.java"));
        assertTrue(shared.contains("本步骤没有返回内容"),
                "空步骤占位行应使用业务说法（本步骤没有返回内容）");
        assertFalse(shared.contains("无可见输出"),
                "占位行仍带实现者视角说法（可见与否属内部渲染形态）");
        for (String face : List.of(
                "ai-service/src/main/java/com/sixbean/testpike/aiservice/explore"
                        + "/domain/component/ExploreLoopKernel.java",
                "ai-service/src/main/java/com/sixbean/testpike/aiservice/doc"
                        + "/domain/service/impl/DocExtractServiceImpl.java")) {
            String src = Files.readString(repo.resolve(face));
            assertTrue(src.contains("ProcessEventDigest.digest("),
                    "摘要帧必须消费共享单源件（同逻辑第二份实现=复制即漂移）");
            assertFalse(src.contains("无可见输出"),
                    "过程事件摘要行仍带实现者视角占位说法");
        }
    }

    @Test
    void guardFlagsPlantedViolationAndPassesAcceptedBaseline() {
        // Positive direction: planted violation (the retired door narration plus
        // a Chinese bookkeeping verb) must be detected.
        String planted = """
                taskEvents.step("web-element-survey", ref, ref,
                        "whole-site survey dispatch door; tail follows the one cursor contract",
                        "游标已对齐后落账");
                taskEvents.step(surveyStreamKind(session), ref, ref,
                        "步骤 3｜候选 4｜入账 REPLAYED=1", null);
                """;
        List<String> hits = detect("Planted:", planted);
        assertFalse(hits.isEmpty(), "检出器对植入违例句零命中=静默空转（R43③）");
        assertTrue(hits.stream().anyMatch(h -> h.contains("dispatch door")));
        assertTrue(hits.stream().anyMatch(h -> h.contains("落账")));
        assertTrue(hits.stream().anyMatch(h -> h.contains("入账")),
                "测绘步骤帧的记账说法未入禁词族=防回潮缺口");
        String plantedGapNote = "taskEvents.step(kind, ref, ref,\n"
                + "        \"第 3 步 sql.assert 缺 conn，落库校验已机械摘除\", null);";
        assertTrue(detect("PlantedGap:", plantedGapNote).stream()
                        .anyMatch(h -> h.contains("机械摘除")),
                "共享缺项补写件的旧话术未入禁词族=防回潮缺口（W8）");
        String plantedCatalogNote = "taskEvents.step(kind, ref, ref,\n"
                + "        \"工具目录（未常驻，需要时先检索）：\", null);";
        assertTrue(detect("PlantedCatalog:", plantedCatalogNote).stream()
                        .anyMatch(h -> h.contains("未常驻")),
                "工具目录的容器黑话未入禁词族=防回潮缺口（W10a）");
        String plantedBlankStep = "processEvents.record(kind, ref, ref, \"step\",\n"
                + "        \"(无可见输出)\", null);";
        assertTrue(detect("PlantedBlank:", plantedBlankStep).stream()
                        .anyMatch(h -> h.contains("无可见输出")),
                "空步骤的旧占位说法未入禁词族=防回潮缺口（W10a）");

        // Negative direction: the accepted batch frame shape (Chinese business
        // three elements + short key=value machine detail) must stay silent.
        String accepted = """
                taskEvents.step(kind, ref, ref,
                        "用例执行失败｜任务 " + task.id() + "｜耗时 " + ms + "ms",
                        "batchId=" + batchId + " taskId=" + task.id() + " result=fail");
                """;
        assertTrue(detect("Accepted:", accepted).isEmpty(),
                "已验收基准帧形态被误拦=检出器过宽，夹具正例失效");

        // Blind-spot direction (the retired +-10-line window): a frame text
        // assembled into a variable twelve lines above the producer call
        // must still be flagged by the widened method-body window.
        String assembled = """
                class Sample {
                    void run() {
                        String frame = "步骤 3｜候选 4｜入账 REPLAYED=1";
                        int pad0 = 0;
                        int pad1 = 0;
                        int pad2 = 0;
                        int pad3 = 0;
                        int pad4 = 0;
                        int pad5 = 0;
                        int pad6 = 0;
                        int pad7 = 0;
                        int pad8 = 0;
                        int pad9 = 0;
                        int pad10 = 0;
                        int pad11 = 0;
                        taskEvents.step(kind, ref, ref, frame, null);
                    }
                }
                """;
        assertTrue(detect("Assembled:", assembled).stream().anyMatch(h -> h.contains("入账")),
                "先组装进变量再传入产生点的帧文=旧窗口盲区，方法体整段窗必须命中");

        // SQL-shape exemption direction: a golden-sample SQL statement inside a
        // model prompt is code, not operator-facing narration, and must stay
        // silent; plain English prose of the same shape must still be flagged.
        String sqlSample = """
                class Sample2 {
                    void run() {
                        String sql = "SELECT status FROM t_order WHERE order_no = :orderNo";
                        taskEvents.step(kind, ref, ref, sql, null);
                    }
                }
                """;
        assertTrue(detect("SqlSample:", sqlSample).isEmpty(),
                "SQL 样例语句被英文叙述判据误拦=检出器过宽，豁免分支失效");
        String proseSample = """
                class Sample3 {
                    void run() {
                        String text = "please keep the highest value content inside the window";
                        taskEvents.step(kind, ref, ref, text, null);
                    }
                }
                """;
        assertTrue(detect("ProseSample:", proseSample).stream().anyMatch(h -> h.contains("直译腔英文整句")),
                "普通英文整句未命中直译腔判据=豁免分支吞掉了真违例");

        // Validator-face direction (W19): a planted translated-tone validator
        // throw must be flagged by the widened marker set, and the accepted
        // business three-element hint must stay silent.
        String plantedThrow = "throw new ServiceException(\"http.request 必须给定 url\", "
                + "BizErrorCode.VALIDATION_ERROR);";
        assertTrue(detect("PlantedValidator:", plantedThrow, VALIDATOR_MESSAGE_MARKERS)
                        .stream().anyMatch(h -> h.contains("必须给定")),
                "校验器直译腔上屏串未入检出面=防回潮缺口（W19）");
        String acceptedThrow = "throw new ServiceException(\"HTTP 请求步骤还差访问地址："
                + "请在该步骤的地址栏填写完整网址（含 http:// 前缀）\", BizErrorCode.VALIDATION_ERROR);";
        assertTrue(detect("AcceptedValidator:", acceptedThrow, VALIDATOR_MESSAGE_MARKERS).isEmpty(),
                "已验收的业务提示形态被误拦=检出器过宽，校验器面夹具失效");

        // Product-name exemption direction (W20b): the bare engineering use
        // of a list word must still be flagged, and the same word inside
        // corner brackets (a product name the operator reads) must stay
        // silent. Bidirectional fixtures so the exemption branch can never
        // run empty (R43③).
        String plantedBareLoop = "throw new ServiceException(\"脚本循环超限：45 个，上限 40\", "
                + "BizErrorCode.SCRIPT_LIMIT_EXCEEDED);";
        assertTrue(detect("PlantedBareLoop:", plantedBareLoop, VALIDATOR_MESSAGE_MARKERS)
                        .stream().anyMatch(h -> h.contains("循环")),
                "裸工程话术被品名豁免分支吞掉=检出器出现新盲区");
        String plantedQuotedLoop = "throw new ServiceException(\"请在「压力形态」步骤填写「循环次数」\" , "
                + "BizErrorCode.VALIDATION_ERROR);";
        assertTrue(detect("PlantedQuotedLoop:", plantedQuotedLoop, VALIDATOR_MESSAGE_MARKERS).isEmpty(),
                "括注品名形态被误拦=豁免分支失效，用户可见功能名会被错判");

        // Clause-headed SQL exemption direction (W20b): an ORDER BY builder
        // fragment stays silent, while four-word English prose keeps flagging
        // (the proseSample above). Exemption branch proven by the paired
        // direction fixtures, never left running blind (R43③).
        String sqlClause = "private static String orderClause() {\n"
                + "        return \" ORDER BY id DESC\";\n}";
        assertTrue(detect("SqlClause:", sqlClause, VALIDATOR_MESSAGE_MARKERS).isEmpty(),
                "SQL 子句拼装件被直译腔判据误拦=子句豁免分支失效");
    }

    /** Scan source text of one producer; report every forbidden shape.
     *  The window is the whole enclosing member body (falling back to the
     *  full snippet when the text carries no member structure), not just the
     *  call-site lines: frames assembled into a variable far above the
     *  producer call are exactly what the old +-10-line window let slip.
     *  Developer-log statements inside the window are exempt (log face,
     *  never rendered to the operator). */
    private List<String> detect(String label, String source) {
        return detect(label, source, PRODUCER_MARKERS);
    }

    private List<String> detect(String label, String source, List<String> markers) {
        List<String> violations = new ArrayList<>();
        String[] lines = source.split("\n", -1);
        int[] depthBefore = braceDepthBefore(lines);
        boolean[] devLog = devLogLines(lines);
        for (int i = 0; i < lines.length; i++) {
            if (markers.stream().noneMatch(lines[i]::contains)) {
                continue;
            }
            int start;
            int end;
            boolean structured = depthBefore[i] >= 2;
            if (structured) {
                start = i;
                while (start > 0 && depthBefore[start - 1] >= 2) {
                    start--;
                }
                end = i;
                int callEnd = Math.min(lines.length - 1, i + 10);
                for (int j = i; j <= callEnd; j++) {
                    end = j;
                    if (lines[j].stripTrailing().endsWith(");")) {
                        break;
                    }
                }
            } else {
                start = 0;
                end = lines.length - 1;
            }
            for (int j = start; j <= end; j++) {
                if (isDeveloperLogLine(lines, devLog, j)) {
                    continue;
                }
                if (structured && j < i && !FRAME_ASSIGN.matcher(lines[j]).find()) {
                    continue;
                }
                for (String literal : literalsOf(lines[j])) {
                    for (String phrase : ENGINEERING_PHRASES) {
                        if (literal.contains(phrase)) {
                            violations.add(label + (j + 1) + " 禁词族命中[" + phrase + "]: " + literal);
                        }
                    }
                    // Product-name exemption: a term quoted with corner
                    // brackets names an on-screen feature the operator sees
                    // (flow-chart node, form field, allocation strategy) and
                    // must stay silent; the bare engineering use still flags.
                    String unbracketed = literal.replaceAll("「[^」]*」", "");
                    for (String word : INTERNAL_BOOKKEEPING_WORDS) {
                        if (unbracketed.contains(word)) {
                            violations.add(label + (j + 1) + " 内部簿记词[" + word + "]: " + literal);
                        }
                    }
                    if (!hasCjk(literal) && ENGLISH_PROSE_RUN.matcher(literal).find()
                            && !SQL_STATEMENT_SHAPE.matcher(literal).find()
                            && !SQL_CLAUSE_SHAPE.matcher(literal).find()) {
                        violations.add(label + (j + 1) + " 直译腔英文整句: " + literal);
                    }
                }
            }
        }
        return violations;
    }

    /** Brace depth in force before each line, counted on code-only text so
     *  braces inside string literals, char literals and comments never skew
     *  the member-body walk. */
    private int[] braceDepthBefore(String[] lines) {
        int[] depthBefore = new int[lines.length];
        int depth = 0;
        boolean inBlockComment = false;
        for (int i = 0; i < lines.length; i++) {
            depthBefore[i] = depth;
            String l = lines[i];
            boolean inString = false;
            for (int c = 0; c < l.length(); c++) {
                char ch = l.charAt(c);
                if (inBlockComment) {
                    if (ch == '*' && c + 1 < l.length() && l.charAt(c + 1) == '/') {
                        inBlockComment = false;
                        c++;
                    }
                    continue;
                }
                if (inString) {
                    if (ch == '\\') {
                        c++;
                    } else if (ch == '"') {
                        inString = false;
                    }
                    continue;
                }
                if (ch == '/' && c + 1 < l.length()) {
                    if (l.charAt(c + 1) == '/') {
                        break;
                    }
                    if (l.charAt(c + 1) == '*') {
                        inBlockComment = true;
                        c++;
                        continue;
                    }
                }
                if (ch == '"') {
                    inString = true;
                    continue;
                }
                if (ch == '\'') {
                    c++;
                    while (c < l.length()) {
                        if (l.charAt(c) == '\\') {
                            c++;
                        } else if (l.charAt(c) == '\'') {
                            break;
                        }
                        c++;
                    }
                    continue;
                }
                if (ch == '{') {
                    depth++;
                } else if (ch == '}') {
                    depth--;
                }
            }
        }
        return depthBefore;
    }

    /** Mark every line belonging to a developer-log statement: the head line
     *  and its continuation lines until the statement's closing ';'. */
    private boolean[] devLogLines(String[] lines) {
        boolean[] flagged = new boolean[lines.length];
        boolean inLog = false;
        for (int i = 0; i < lines.length; i++) {
            if (!inLog && DEV_LOG_CALL.matcher(lines[i]).find()) {
                inLog = true;
            }
            flagged[i] = inLog;
            if (inLog && lines[i].stripTrailing().endsWith(";")) {
                inLog = false;
            }
        }
        return flagged;
    }

    /** A window line is exempt when it belongs to a developer-log statement. */
    private boolean isDeveloperLogLine(String[] lines, boolean[] devLog, int j) {
        return devLog[j];
    }

    private List<String> literalsOf(String line) {
        List<String> out = new ArrayList<>();
        String t = line.strip();
        if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) {
            return out;
        }
        Matcher m = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(line);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private boolean hasCjk(String literal) {
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                return true;
            }
        }
        return false;
    }

    private Path locateRepoRoot() {
        return com.sixbean.testpike.core.support.RepoRootLocator.locate();
    }
}
