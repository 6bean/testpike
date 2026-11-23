package com.sixbean.testpike.core.tool.domain.component;

import com.sixbean.testpike.common.genkit.GenFunction;
import com.sixbean.testpike.core.tool.api.dto.ToolCreateInput;
import com.sixbean.testpike.core.tool.domain.service.ToolService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Registers the built-in read-only analysis tools : the
 * coverage-sufficiency and assertion-density digests the conversation answers
 * "which requirement points are still untested" with.
 * <p>They are registered as ToolDefinition rows on purpose — approval, the
 * marketplace, the assembly vocabulary and invocation auditing stay the one
 * place the platform already has, instead of a second code-only tool family.
 * The approval chain is not bypassed: create → submit → approve all run through
 * {@link ToolService} (seed operator and approver kept apart as 1/2, same table
 * and same lifecycle as a hand-registered tool).
 * <p>The callable set is read off the function registry (every entry the
 * registry marks read-only analysis gets a row), so adding a third analysis
 * function to the registry needs no edit here.
 * <p>Idempotency anchor = {@code exec_spec->>'function'} with exec_kind=5, never
 * the display name (renaming a tool must not re-create it). A name squatted by a
 * foreign row is skipped with a warning.
 * <p>Read semantics: the input schema carries no {@code x-write}, so the dialog
 * runs these tools without a confirm ticket (AI features ship enabled by
 * default, AGENTS rule); the project segment is carried per
 * call by the analysis door (the rows are system-level, project_id NULL).
 */
@Component
public class AnalysisToolSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AnalysisToolSeedInitializer.class);

    private static final long SEED_OPERATOR_ID = 1L;
    private static final long SEED_APPROVER_ID = 2L;

    /** Optional chapter filter + optional end-type filter (coverage digest). */
    private static final String SCHEMA_COVERAGE =
            "{\"type\":\"object\",\"properties\":{"
                    + "\"chapter\":{\"type\":\"string\",\"description\":\"需求章节名称（缺省=项目全部章节）\"},"
                    + "\"endType\":{\"type\":\"string\",\"description\":\"终端类型（缺省=全部终端）\"}}"
                    + ",\"required\":[]}";

    /** Optional end-type filter only (density digest). */
    private static final String SCHEMA_DENSITY =
            "{\"type\":\"object\",\"properties\":{"
                    + "\"endType\":{\"type\":\"string\",\"description\":\"终端类型（缺省=全部终端用例）\"}}"
                    + ",\"required\":[]}";

    /** One source id, either a regression suite or an existing plan (rehearsal draft). */
    private static final String SCHEMA_REHEARSAL = """
            {"type":"object","properties":{"suiteId":{"type":"integer",\
            "description":"回归集 ID（与 planId 二选一）"},\
            "planId":{"type":"integer","description":"既有计划 ID（与 suiteId 二选一）"}},\
            "required":[]}""";

    /** Required execution batch id (rehearsal risk report): without this declaration
     *  the model has nowhere to put the batch number and the composer refuses. */
    private static final String SCHEMA_REHEARSAL_RISK = """
            {"type":"object","properties":{"batchId":{"type":"integer",\
            "description":"演练批次编号（必填，来自草案确认回执或演练启动回执，形如 512）"}},\
            "required":["batchId"]}""";

    private final ToolService toolService;
    private final JdbcTemplate jdbcTemplate;

    public AnalysisToolSeedInitializer(ToolService toolService, JdbcTemplate jdbcTemplate) {
        this.toolService = toolService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int created = seedMissing();
            if (created > 0) {
                log.info("已注册内置只读分析工具 {} 件（分析函数族，走审批链发布态）", created);
            }
        } catch (RuntimeException e) {
            // A seed failure must not block startup (the tool page can register
            // it by hand); the reason goes to the log in plain words.
            log.warn("内置只读分析工具种子注册失败（不影响启动，可在工具页手工注册）: {}", e.getMessage());
        }
    }

    /**
     * Register every registry analysis function that has no tool row yet;
     * returns how many rows this call created (0 = list complete, safe to re-run
     * on every start).
     */
    public int seedMissing() {
        List<String> present = jdbcTemplate.queryForList(
                "SELECT exec_spec ->> 'function' FROM tp_tool WHERE exec_kind = 5",
                String.class);
        List<String> wanted = analysisFunctionNames();
        int created = 0;
        for (String function : wanted) {
            if (present.contains(function)) {
                continue;
            }
            if (nameSquatted(function)) {
                log.warn("内置只读分析工具 {} 的展示名称已被人工工具占用，跳过种子注册（请人工核对）",
                        toolName(function));
                continue;
            }
            registerPublished(function);
            created++;
        }
        return created;
    }

    /** Callable analysis set, read off the registry single source. */
    static List<String> analysisFunctionNames() {
        List<String> names = new ArrayList<>();
        for (GenFunction candidate : GenFunction.values()) {
            if (candidate.isAnalysis()) {
                names.add(candidate.functionName());
            }
        }
        return names;
    }

    private boolean nameSquatted(String function) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_tool WHERE name = ? AND project_id IS NULL"
                        + " AND (exec_spec ->> 'function' IS NULL"
                        + " OR exec_spec ->> 'function' <> ?)",
                Integer.class, toolName(function), function);
        return n != null && n > 0;
    }

    private void registerPublished(String function) {
        GenFunction registryFunction = GenFunction.byName(function);
        if (registryFunction == null) {
            // Registry-renaming guard: a stale literal is skipped with a trace,
            // never a half-registered row that breaks startup.
            log.warn("只读分析函数 {} 不在函数注册表内，跳过种子注册", function);
            return;
        }
        String spec = "{\"function\":\"" + function + "\"}";
        var info = toolService.create(SEED_OPERATOR_ID, new ToolCreateInput(
                null, toolName(function), 2, "分析", description(function), null,
                "[\"分析\",\"内置\"]", inputSchema(function), null,
                ToolExecKind.ANALYSIS.code(), spec, 30000, null));
        var submitted = toolService.submit(SEED_OPERATOR_ID, info.id(), info.version());
        toolService.approve(SEED_APPROVER_ID, submitted.id(), submitted.version());
    }

    /** Tool name = the analysis prefix plus what the answer is about (user-facing wording, no code identifier). */
    private static String toolName(String function) {
        return switch (function) {
            case "coverageSummary" -> "分析·需求覆盖摘要";
            case "assertionDensity" -> "分析·断言密度";
            case "rehearsalDraft" -> "分析·回归演练草案";
            default -> "分析·" + function;
        };
    }

    /** Description the tool page and the dialog assembly show to the model. */
    private static String description(String function) {
        return switch (function) {
            case "coverageSummary" -> "汇总指定项目的需求点覆盖情况：已覆盖与未覆盖数量、未覆盖需求点清单（最多 50 条，"
                    + "超出时说明还有多少未列出），并给出契约覆盖率准出下限的实测结论；可按需求章节或终端类型收窄；"
                    + "纯查询，不修改任何数据";
            case "assertionDensity" -> "按项目内每个用例的当前版本实算步骤数与断言步骤数：输出平均断言密度、"
                    + "无任何断言步骤的用例清单及其名称；不含已归档用例；可按终端类型收窄；纯查询，不修改任何数据";
            case "rehearsalDraft" -> "按一个回归集或一个既有计划装配回归演练草案：给出计划名称、用例范围、"
                    + "执行环境与准出键，可直接用于创建执行计划；本函数只生成草案不写入数据，"
                    + "确认后才会创建草稿态计划，发布与启动仍在计划页人工操作";
            case "rehearsalRiskReport" -> "读取一次已结束演练批次的风险报告卡：按批次实算通过率、失败聚类与"
                    + "未归因项，给出准出结论与依据；批次编号来自草案确认回执或演练启动回执；"
                    + "纯查询，不修改任何数据";
            default -> "只读分析函数 " + function + "：纯查询，不修改任何数据";
        };
    }

    /** Filter vocabulary of one function (must match the dispatcher's set). */
    private static String inputSchema(String function) {
        return switch (function) {
            case "coverageSummary" -> SCHEMA_COVERAGE;
            case "rehearsalDraft" -> SCHEMA_REHEARSAL;
            case "rehearsalRiskReport" -> SCHEMA_REHEARSAL_RISK;
            default -> SCHEMA_DENSITY;
        };
    }
}
