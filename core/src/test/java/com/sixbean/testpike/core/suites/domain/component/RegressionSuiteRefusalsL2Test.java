package com.sixbean.testpike.core.suites.domain.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R63 three-element contract for the regression suite refusals: each sentence
 * echoes the offending submitted value, quotes the on-screen repair entry and
 * keeps the legacy capability clause contiguous for existing assertions.
 */
class RegressionSuiteRefusalsL2Test {

    private static void assertGuided(String sentence, String echoedValue, String entry) {
        assertTrue(sentence.contains(echoedValue), "must echo value: " + sentence);
        assertTrue(sentence.contains(entry), "must name entry " + entry + ": " + sentence);
    }

    @Test
    void suiteMissingEchoesIdAndKeepsLegacyClause() {
        String s = RegressionSuiteRefusals.suiteMissing(4501);
        assertGuided(s, "4501", "「回归集」");
        assertTrue(s.contains("回归集不存在"), s);
    }

    @Test
    void suiteDisabledEchoesId() {
        String s = RegressionSuiteRefusals.suiteDisabled(4501);
        assertGuided(s, "4501", "「回归集」");
        assertTrue(s.contains("回归集已停用，请先启用后再一键生成"), s);
    }

    @Test
    void noCasesEchoesIdAndKeepsLegacyClause() {
        String s = RegressionSuiteRefusals.noCases(4501);
        assertGuided(s, "4501", "「回归集」");
        assertTrue(s.contains("回归集内无用例，请先添加用例再一键生成"), s);
    }

    @Test
    void noEnvEchoesId() {
        String s = RegressionSuiteRefusals.noEnv(4501);
        assertGuided(s, "4501", "「回归集」");
        assertTrue(s.contains("回归集未配置环境，请先选择环境再一键生成"), s);
    }

    @Test
    void noAssigneeEchoesId() {
        String s = RegressionSuiteRefusals.noAssignee(4501);
        assertGuided(s, "4501", "「回归集」");
        assertTrue(s.contains("回归集未配置默认执行人，请先配置后再一键生成"), s);
    }

    @Test
    void allCasesArchivedEchoesCount() {
        String s = RegressionSuiteRefusals.allCasesArchived(12);
        assertGuided(s, "12", "「回归集」");
        assertTrue(s.contains("条用例均已归档/删除"), s);
    }

    @Test
    void nameBlankQuotesFieldAndLimit() {
        String s = RegressionSuiteRefusals.nameBlank();
        assertTrue(s.contains("回归集名称不能为空"), s);
        assertTrue(s.contains("「名称」"), s);
        assertTrue(s.contains("100"), s);
    }

    @Test
    void nameTooLongEchoesSubmittedLength() {
        String s = RegressionSuiteRefusals.nameTooLong(137);
        assertTrue(s.contains("137"), s);
        assertGuided(s, "137", "「回归集」");
        assertTrue(s.contains("回归集名称长度不能超过 100 个字符"), s);
    }

    @Test
    void casesEmptyKeepsLegacyClause() {
        String s = RegressionSuiteRefusals.casesEmpty();
        assertTrue(s.contains("「用例圈选」"), s);
        assertGuided(s, "保存", "「回归集」");
        assertTrue(s.contains("请选择回归集用例（至少 1 条）"), s);
    }

    @Test
    void envEmptyKeepsLegacyClause() {
        String s = RegressionSuiteRefusals.envEmpty();
        assertTrue(s.contains("请选择回归集执行环境"), s);
        assertGuided(s, "「执行环境」", "「回归集」");
    }

    @Test
    void foreignCasesEchoesMismatchCounts() {
        String s = RegressionSuiteRefusals.foreignCases(2, 8);
        assertTrue(s.contains("2") && s.contains("8"), s);
        assertGuided(s, "2", "「用例管理」");
        assertTrue(s.contains("存在不属于该项目的用例"), s);
    }

    @Test
    void assigneeNotMemberEchoesUserId() {
        String s = RegressionSuiteRefusals.assigneeNotMember(70055);
        assertGuided(s, "70055", "「项目成员」");
        assertTrue(s.contains("不是本项目成员"), s);
    }

    @Test
    void serializeFailedNamesNextStep() {
        String s = RegressionSuiteRefusals.serializeFailed();
        assertTrue(s.contains("参数序列化失败"), s);
        assertTrue(s.contains("「回归集」"), s);
    }
}
