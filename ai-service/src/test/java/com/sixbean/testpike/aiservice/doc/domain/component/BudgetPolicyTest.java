package com.sixbean.testpike.aiservice.doc.domain.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


class BudgetPolicyTest {

    
    @Test
    void 缺省值等于拍板组() {
        BudgetPolicy p = BudgetPolicy.defaults();
        assertEquals(0.20, p.summaryRatio(), 1e-9);
        assertEquals(300, p.summaryMinTokens());
        assertEquals(1500, p.summaryMaxTokens());
        assertEquals(6, p.reduceTriggerGroups());
        assertEquals(2, p.reduceMaxLevels());
    }

    
    @Test
    void summaryBudgetTokens_先比例后上下限夹逼() {
        BudgetPolicy p = BudgetPolicy.defaults(); // min300 max1500 ratio .2
        assertEquals(300, p.summaryBudgetTokens(1000));
        assertEquals(600, p.summaryBudgetTokens(3000));
        assertEquals(1500, p.summaryBudgetTokens(10000));
    }

    
    @Test
    void summaryBudgetTokens_边界恰值() {
        BudgetPolicy p = new BudgetPolicy(0.05, 50, 50, 2, 1);
        assertEquals(50, p.summaryBudgetTokens(10));
        assertEquals(50, p.summaryBudgetTokens(100000)); // 5000 → max 50
        BudgetPolicy q = BudgetPolicy.defaults();
        assertEquals(300, q.summaryBudgetTokens(1500));
        assertEquals(1500, q.summaryBudgetTokens(7500));
    }

 
    @Test
    void fromConfig_逐键独立回退缺省() {
        BudgetPolicy p = BudgetPolicy.fromConfig("abc", "", "  ", "99999", null);
        assertEquals(BudgetPolicy.defaults().summaryRatio(), p.summaryRatio(), 1e-9);
        assertEquals(300, p.summaryMinTokens());
        assertEquals(1500, p.summaryMaxTokens());
        assertEquals(6, p.reduceTriggerGroups());
        assertEquals(2, p.reduceMaxLevels());
        BudgetPolicy ok = BudgetPolicy.fromConfig("0.4", "200", "2000", "8", "3");
        assertEquals(0.4, ok.summaryRatio(), 1e-9);
        assertEquals(8, ok.reduceTriggerGroups());
    }

    
    @Test
    void fromConfig_min大于max_双回退() {
        BudgetPolicy p = BudgetPolicy.fromConfig("0.2", "2000", "100", "6", "2");
        assertEquals(300, p.summaryMinTokens());
        assertEquals(1500, p.summaryMaxTokens());
    }

    
    @Test
    void 摘要字符近似换算同源() {
        BudgetPolicy p = BudgetPolicy.defaults();
        assertEquals(300 * 480 / 512, ContextBudgetPlanner.tokensToChars(300));
        assertEquals(1406, ContextBudgetPlanner.tokensToChars(1500));
        assertEquals(1406, ContextBudgetPlanner.tokensToChars(p.summaryBudgetTokens(99999)));
    }

    
    @Test
    void 构造面非法值快速失败() {
        assertThrows(IllegalArgumentException.class, () -> new BudgetPolicy(1.5, 300, 1500, 6, 2));
        assertThrows(IllegalArgumentException.class, () -> new BudgetPolicy(0.2, 300, 1500, 1, 2));
        assertThrows(IllegalArgumentException.class, () -> new BudgetPolicy(0.2, 300, 1500, 6, 4));
    }
}
