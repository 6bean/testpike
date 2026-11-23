package com.sixbean.testpike.common.report;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class CaliberDefinitionTest {

 
    private static final List<String> FIRST_BATCH_CODES = List.of(
            "pass-rate", "defect-density", "req-coverage", "ai-accept-rate",
            "exec-p95", "token-cost-month", "feedback-reject-type", "fix-adopt-rate");

 
    private static final List<String> COST_DIM_CODES = List.of(
            "token-cost-project", "token-cost-model", "token-cost-caller", "token-cost-day");

 
    private static final List<String> REPORT_WINDOW_CODES = List.of("token-cost-window");

 
    private static final List<String> PERF_CODES = List.of(
            "perf-p95", "perf-error-rate", "perf-throughput");

    @Test
    void registeredCalibersCoverFirstBatchAndCostDims() {
        List<String> codes = CaliberService.codes();
        assertEquals(FIRST_BATCH_CODES.size() + COST_DIM_CODES.size() + REPORT_WINDOW_CODES.size()
                        + PERF_CODES.size(),
                codes.size(),
                "注册口径必须恰为首批 8 + E3 四维 4 + 报表窗口 1 + 压测三口径 3，实际=" + codes);
        assertTrue(codes.containsAll(FIRST_BATCH_CODES),
                "缺少口径: " + FIRST_BATCH_CODES.stream().filter(c -> !codes.contains(c)).toList());
        assertTrue(codes.containsAll(COST_DIM_CODES),
                "缺少成本维度口径: " + COST_DIM_CODES.stream().filter(c -> !codes.contains(c)).toList());
        assertTrue(codes.containsAll(REPORT_WINDOW_CODES),
                "缺少报表窗口口径: " + REPORT_WINDOW_CODES.stream().filter(c -> !codes.contains(c)).toList());
        assertTrue(codes.containsAll(PERF_CODES),
                "缺少压测性能口径: " + PERF_CODES.stream().filter(c -> !codes.contains(c)).toList());
    }

    @Test
    void reportWindowCaliberSharesCostFamilySoftDeleteSemantics() {
        CaliberDefinition d = CaliberService.require("token-cost-window");
        assertTrue(d.namedParams().containsAll(List.of("fromTime", "toTime", "projectId")),
                "窗口口径须携带 fromTime/toTime/projectId，实际=" + d.namedParams());
        assertTrue(d.sqlTemplate().contains("p.status = 1 OR l.project_id = :projectId"),
                "窗口口径软删语义与月块漂移");
    }

    @Test
    void costDimCalibersShareScopeBlockAndOutputContract() {
        for (String code : COST_DIM_CODES) {
            CaliberDefinition d = CaliberService.require(code);
            List<String> params = d.namedParams();
            assertTrue(params.contains("month") && params.contains("projectId"),
                    code + " 须携带 month+projectId 范围参数，实际=" + params);
            String sql = d.sqlTemplate();
            assertTrue(sql.contains(CostSql.SCOPE_FROM), code + " 未复用共享作用域块");
            assertTrue(sql.contains("AS value") && sql.contains("AS amount") && sql.contains("AS calls"),
                    code + " 输出契约须为 value/amount/calls 三列");
            assertTrue(sql.contains("GROUP BY"), code + " 缺维度分组");
        }
        assertTrue(CaliberService.require("token-cost-month").sqlTemplate().contains(CostSql.SCOPE_FROM),
                "月合计与四维不同源=同名指标双口径风险（TC-O10-01 同源面）");
    }

    @Test
    void everyCaliberCarriesNameDescriptionSqlAndVersion() {
        for (CaliberDefinition d : CaliberService.listAll()) {
            assertFalse(d.displayName().isBlank(), d.code() + " 缺中文名");
            assertFalse(d.description().isBlank(), d.code() + " 缺一句话解释");
            assertFalse(d.sqlTemplate().isBlank(), d.code() + " 缺 SQL 模板");
            String head = d.sqlTemplate().strip().toUpperCase();
            assertTrue(head.startsWith("SELECT") || head.startsWith("WITH"),
                    d.code() + " SQL 模板必须是查询（SELECT/WITH 起）");
            assertTrue(d.version().matches("v\\d+\\.\\d+"),
                    d.code() + " 版本号须为 v<major>.<minor> 格式，实际=" + d.version());
        }
    }

    @Test
    void hoverTextShowsNameVersionAndExplanation() {
        CaliberDefinition passRate = CaliberService.require("pass-rate");
        String hover = passRate.hoverText();
        assertTrue(hover.contains(passRate.displayName()), hover);
        assertTrue(hover.contains(passRate.version()), hover);
        assertTrue(hover.contains(passRate.description()), hover);
    }

    @Test
    void feedbackRejectTypeAggregatesRealColumnWithUnclassifiedBucket() {
        CaliberDefinition d = CaliberService.require("feedback-reject-type");
        assertEquals("v1.1", d.version(), "口径版本须随取数语义变更 bump");
        String sql = d.sqlTemplate();
        assertTrue(sql.contains("unnest") && sql.contains("feedback_types"),
                "分布链路必须按 feedback_types 真列 unnest 聚合，实际=" + sql);
        assertTrue(sql.contains("未分类"), "存量 NULL 行须归未分类兜底桶");
        assertFalse(sql.contains("split_part"), "旧 remark 首段假口径必须移除");
    }

    @Test
    void sqlTemplateNamedParamsAreParseable() {
        for (CaliberDefinition d : CaliberService.listAll()) {
            List<String> params = d.namedParams();
            for (String p : params) {
                assertTrue(Pattern.matches("[a-zA-Z_][a-zA-Z0-9_]*", p),
                        d.code() + " 非法命名参数 :" + p);
            }
        }
        assertTrue(CaliberService.require("token-cost-month").namedParams().contains("month"),
                "token-cost-month 模板须含 :month 参数");
    }

    @Test
    void codesAreUniqueAndLookupIsExact() {
        long distinct = CaliberService.listAll().stream().map(CaliberDefinition::code).distinct().count();
        assertEquals(CaliberService.listAll().size(), distinct, "口径 code 重复=同名指标双口径，违反 TC-O10-01");
        assertEquals(CaliberDefinition.PASS_RATE, CaliberService.require("pass-rate"));
    }
}
