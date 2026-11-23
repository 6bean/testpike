package com.sixbean.testpike.aiservice.explore.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;


class ExploreBudgetPolicyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ExploreBudgetPolicy parse(String snapshot) {
        return ExploreBudgetPolicy.parse(snapshot, MAPPER);
    }

    
    @Test
    void 解析快照_全键逐字段() {
        ExploreBudgetPolicy policy =
                parse("{\"maxSteps\":30,\"maxPages\":20,\"noProgressLimit\":3}");
        assertEquals(30, policy.maxSteps());
        assertEquals(20, policy.maxPages());
        assertEquals(3, policy.noProgressLimit());
    }

    
    @Test
    void 解析快照_缺键与坏输入回退默认() {
        ExploreBudgetPolicy empty = parse("{}");
        assertEquals(30, empty.maxSteps(), "缺 maxSteps 回退写侧默认 30");
        assertEquals(10, empty.maxPages(), "缺 maxPages 回退写侧默认 10");
        assertEquals(3, empty.noProgressLimit(), "缺 noProgressLimit 回退常量 3");
        ExploreBudgetPolicy broken = parse("not-a-json");
        assertEquals(30, broken.maxSteps(), "坏 JSON 静默回退（人话告警归内核）");
        ExploreBudgetPolicy nullish = parse(null);
        assertEquals(30, nullish.maxSteps());
    }

    
    @Test
    void 出口判型_达成优先done() {
        ExploreBudgetPolicy policy = parse("{\"maxSteps\":2,\"maxPages\":2,\"noProgressLimit\":3}");
        assertEquals(ExploreBudgetPolicy.OUTCOME_DONE,
                policy.classify(2, 2, true, 0, false), "触线但达成=done");
    }

    
    @Test
    void 出口判型_步数触线预算停() {
        ExploreBudgetPolicy policy = parse("{\"maxSteps\":5,\"maxPages\":20,\"noProgressLimit\":3}");
        assertEquals(ExploreBudgetPolicy.OUTCOME_BUDGET_STOP,
                policy.classify(5, 3, false, 0, false), "steps_used≥maxSteps 未达成=budget-stop");
    }

    
    @Test
    void 出口判型_页数触线预算停() {
        ExploreBudgetPolicy policy = parse("{\"maxSteps\":30,\"maxPages\":10,\"noProgressLimit\":3}");
        assertEquals(ExploreBudgetPolicy.OUTCOME_BUDGET_STOP,
                policy.classify(8, 10, false, 0, false), "pages≥maxPages 未达成=budget-stop");
    }

    
    @Test
    void 出口判型_无进展部分完成() {
        ExploreBudgetPolicy policy = parse("{\"maxSteps\":30,\"maxPages\":10,\"noProgressLimit\":3}");
        assertEquals(ExploreBudgetPolicy.OUTCOME_PARTIAL,
                policy.classify(8, 3, false, 3, false), "无进展连续 N=partial");
        assertEquals(ExploreBudgetPolicy.OUTCOME_PARTIAL,
                policy.classify(8, 3, false, 0, true), "LLM 无可行动作=partial");
    }

    
    @Test
    void 出口判型_预算内继续() {
        ExploreBudgetPolicy policy = parse("{\"maxSteps\":30,\"maxPages\":10,\"noProgressLimit\":3}");
        assertEquals(ExploreBudgetPolicy.OUTCOME_RUNNING,
                policy.classify(8, 3, false, 2, false), "未触任何线=继续");
    }

 
    @Test
    void 解析appauto段_全键逐字段() {
        ExploreBudgetPolicy policy = parse("{\"maxSteps\":30,\"maxPages\":10,"
                + "\"noProgressLimit\":3,\"appauto\":{\"maxSteps\":60,\"maxTokens\":5000,"
                + "\"maxDurationSeconds\":900}}");
        assertEquals(60, policy.appautoMaxSteps());
        assertEquals(5000L, policy.appautoMaxTokens());
        assertEquals(900L, policy.appautoMaxDurationSeconds());
    }

    
    @Test
    void 解析appauto段_缺段回退默认() {
        ExploreBudgetPolicy webOnly = parse("{\"maxSteps\":30,\"maxPages\":10}");
        assertEquals(60, webOnly.appautoMaxSteps());
        assertEquals(1_000_000L, webOnly.appautoMaxTokens());
        assertEquals(3600L, webOnly.appautoMaxDurationSeconds());
        ExploreBudgetPolicy broken = parse("not-a-json");
        assertEquals(60, broken.appautoMaxSteps(), "坏 JSON 全段回退默认");
    }

    
    @Test
    void 移动三重预算_三线任一触线() {
        ExploreBudgetPolicy policy = parse("{\"appauto\":{\"maxSteps\":10,\"maxTokens\":1000,"
                + "\"maxDurationSeconds\":60,\"noProgressLimit\":3}}");
        assertEquals(ExploreBudgetPolicy.OUTCOME_BUDGET_STOP,
                policy.classifyMobile(10, 10, 1, false, 0, false), "步数线触线");
        assertEquals(ExploreBudgetPolicy.OUTCOME_BUDGET_STOP,
                policy.classifyMobile(3, 1000, 1, false, 0, false), "token 线触线");
        assertEquals(ExploreBudgetPolicy.OUTCOME_BUDGET_STOP,
                policy.classifyMobile(3, 10, 60, false, 0, false), "时长线触线");
        assertEquals(ExploreBudgetPolicy.OUTCOME_DONE,
                policy.classifyMobile(10, 1000, 60, true, 0, false), "三线全触仍达成优先");
        assertEquals(ExploreBudgetPolicy.OUTCOME_PARTIAL,
                policy.classifyMobile(3, 10, 1, false, 3, false), "无进展位同 web 口径");
        assertEquals(ExploreBudgetPolicy.OUTCOME_RUNNING,
                policy.classifyMobile(3, 10, 1, false, 0, false), "未触任何线=继续");
    }
}
