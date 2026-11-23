package com.sixbean.testpike.aiservice.gen.domain.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;


class PriorityRuleTest {

    private final PriorityRule rule = new PriorityRule();

    @Test
    void suggest_含登录_建议P0() {
        assertEquals(0, rule.suggest("用户登录功能"));
    }

    @Test
    void suggest_含支付_建议P0() {
        assertEquals(0, rule.suggest("订单支付流程"));
    }

    @Test
    void suggest_含结算_建议P0() {
        assertEquals(0, rule.suggest("购物车结算"));
    }

    @Test
    void suggest_边缘功能_建议P2() {
        assertEquals(2, rule.suggest("页面样式展示调整"));
    }

    @Test
    void suggest_普通功能_建议P1() {
        assertEquals(1, rule.suggest("商品列表排序"));
    }

    @Test
    void suggest_需求为空_默认P1() {
        assertEquals(1, rule.suggest(""));
    }
}
