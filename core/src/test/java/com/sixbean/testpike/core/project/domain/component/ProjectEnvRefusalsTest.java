package com.sixbean.testpike.core.project.domain.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R63 refusal-guidance contract for the project environment door.
 *
 * <p>Every refusal raised through {@link ProjectEnvRefusals} must echo the
 * offending input value and quote the on-screen repair entry with its exact
 * shipped wording (tab, dialog, field labels verified against
 * frontend/src/utils/locales/zh-CN.ts project group), so the reader can
 * actually follow the pointer. A pointer to a page the menu does not show
 * is itself a defect, hence the negative assertions.
 */
class ProjectEnvRefusalsTest {

    private static void assertHasActionWord(String message) {
        assertTrue(message.contains("请") || message.contains("点击") || message.contains("重新"),
                "refusal must carry a repair action word: " + message);
        assertTrue(message.contains("「") && message.contains("」"),
                "refusal must quote at least one on-screen entry or field: " + message);
    }

    @Test
    void duplicateEnvNameRefusalQuotesValueAndNamesFieldAndTab() {
        String message = ProjectEnvRefusals.duplicateEnvName("冒烟环境");
        assertTrue(message.contains("「冒烟环境」"), "offending name echoed: " + message);
        assertTrue(message.contains("「环境配置」"), "tab named: " + message);
        assertTrue(message.contains("「环境名称」"), "field named: " + message);
        assertHasActionWord(message);
    }

    @Test
    void envMissingRefusalNamesTheTabAndTheRebuildEntry() {
        String message = ProjectEnvRefusals.envMissing();
        assertTrue(message.contains("「环境配置」"), "tab named: " + message);
        assertTrue(message.contains("「新增环境」"), "rebuild door quoted: " + message);
        assertFalse(message.contains("环境管理"), "no pointer to a page the menu does not show: " + message);
        assertHasActionWord(message);
    }

    @Test
    void envGoneByIdRefusalQuotesTheIdAndNamesTheTab() {
        String message = ProjectEnvRefusals.envGoneById(7788L);
        assertTrue(message.contains("7788"), "offending id echoed: " + message);
        assertTrue(message.contains("「环境配置」"), "tab named: " + message);
        assertHasActionWord(message);
    }

    @Test
    void deleteBlockedRefusalKeepsReferencePlaceholderAndQuotesRealConfirmHint() {
        String message = ProjectEnvRefusals.deleteBlocked();
        assertTrue(message.contains("%s"), "reference list still filled by caller: " + message);
        assertTrue(message.contains("删除将清空相关执行批次的该环境引用"),
                "forced-delete hint quotes the shipped confirmation wording: " + message);
        assertTrue(message.contains("「删除」"), "row action door quoted: " + message);
        assertHasActionWord(message);
    }

    @Test
    void projectMissingRefusalQuotesTheIdAndPointsAtTheHeaderSelector() {
        String message = ProjectEnvRefusals.projectMissing(9012L);
        assertTrue(message.contains("9012"), "offending id echoed: " + message);
        assertTrue(message.contains("「环境配置」"), "landing tab quoted: " + message);
        assertTrue(message.contains("顶栏"), "slice repair door named: " + message);
        assertHasActionWord(message);
    }

    @Test
    void deployVersionBlankRefusalNamesFieldAndDialog() {
        String message = ProjectEnvRefusals.deployVersionBlank();
        assertTrue(message.contains("「部署版本」"), "field named: " + message);
        assertTrue(message.contains("「记录环境部署」"), "dialog title quoted: " + message);
        assertHasActionWord(message);
    }

    @Test
    void deployVersionTooLongRefusalEchoesLengthAndNamesTheDialog() {
        String message = ProjectEnvRefusals.deployVersionTooLong("v".repeat(60));
        assertTrue(message.contains("60"), "current length echoed: " + message);
        assertTrue(message.contains("「记录环境部署」"), "dialog title quoted: " + message);
        assertTrue(message.contains("「部署版本」"), "field named: " + message);
        assertHasActionWord(message);
    }

    @Test
    void nameInvalidRefusalQuotesTheInputAndNamesTheDialogField() {
        String message = ProjectEnvRefusals.nameInvalid("   ");
        assertTrue(message.contains("「环境名称」"), "field named: " + message);
        assertTrue(message.contains("「新增环境」") || message.contains("「编辑环境」"),
                "dialog door quoted: " + message);
        assertTrue(message.contains("为空") || message.contains("50 字"),
                "boundary consequence stated: " + message);
        assertHasActionWord(message);
    }

    @Test
    void typeInvalidRefusalEchoesValueAndQuotesOnScreenOptionWords() {
        String message = ProjectEnvRefusals.typeInvalid(9);
        assertTrue(message.contains("9"), "offending value echoed: " + message);
        assertTrue(message.contains("开发环境"), "option words match the dropdown: " + message);
        assertTrue(message.contains("预发环境"), "option words match the dropdown: " + message);
        assertTrue(message.contains("「环境类型」"), "field named: " + message);
        assertHasActionWord(message);
    }

    @Test
    void prodBaseUrlRefusalNamesFieldAndStatesWhy() {
        String message = ProjectEnvRefusals.prodBaseUrlMissing();
        assertTrue(message.contains("「访问地址」"), "field named: " + message);
        assertTrue(message.contains("生产域名黑名单"), "consequence stated: " + message);
        assertHasActionWord(message);
    }

    @Test
    void typeNotUniqueRefusalNamesTheLabelAndTheEditDoor() {
        String message = ProjectEnvRefusals.typeNotUnique(3);
        assertTrue(message.contains("预发环境"), "type uses the on-screen option word: " + message);
        assertTrue(message.contains("「环境类型」"), "constraint field named: " + message);
        assertTrue(message.contains("「编辑环境」"), "repair door quoted: " + message);
        assertHasActionWord(message);
    }
}
