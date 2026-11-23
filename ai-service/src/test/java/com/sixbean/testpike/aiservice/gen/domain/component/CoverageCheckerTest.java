package com.sixbean.testpike.aiservice.gen.domain.component;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class CoverageCheckerTest {

    /** legacy shape: null gateway = no config reads,
 * null matcher = no semantics — every verdict here stays the M1 rule. */
    private final CoverageChecker checker = new CoverageChecker(null, null);

    @Test
    void check_3条验收标准全覆盖_全部覆盖() {
        String criteria = "账号密码正确可登录\n错误密码有提示\n登录成功进入首页";
        String caseText = "账号密码正确可登录，错误密码有提示，登录成功进入首页";

        CoverageResult result = checker.check(criteria, caseText, 3L, 1L);

        assertTrue(result.passed());
        assertEquals(3, result.coveredCount());
        assertEquals(0, result.uncovered().size());
    }

    @Test
    void check_含错误提示关键词_生成负向场景() {
        String criteria = "错误密码时出现错误提示";
        String caseText = "输入错误密码，点击登录，页面出现错误提示信息";

        CoverageResult result = checker.check(criteria, caseText, 3L, 1L);
        assertTrue(result.passed());
    }

    @Test
    void check_空验收标准_不阻断标记无验收标准() {
        CoverageResult result = checker.check("", "任何用例内容", 3L, 1L);

        assertTrue(result.passed());
        assertTrue(result.noCriteria());
        assertEquals(0, result.coveredCount());
    }

    @Test
    void check_标准未被覆盖_列出未覆盖项() {
        String criteria = "账号密码正确登录\n优惠券满减校验";
        String caseText = "输入账号密码正确登录";

        CoverageResult result = checker.check(criteria, caseText, 3L, 1L);

        assertFalse(result.passed());
        assertEquals(1, result.coveredCount());
        assertEquals(1, result.uncovered().size());
        assertTrue(result.uncovered().get(0).contains("优惠券"));
    }

    @Test
    void check_标准全英文关键词_按原文子串匹配() {
        String criteria = "payment success page shown";
        String caseText = "After payment, success page shown with order number";

        CoverageResult result = checker.check(criteria, caseText, 3L, 1L);
        assertTrue(result.passed());
    }

    @Test
    void check_分号分隔多条标准() {
        String criteria = "登录成功;退出登录;会话保持";
        String caseText = "登录成功后页面跳转，可退出登录，会话保持 30 分钟";

        CoverageResult result = checker.check(criteria, caseText, 3L, 1L);
        assertTrue(result.passed());
        assertEquals(3, result.coveredCount());
    }
}
