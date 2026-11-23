package com.sixbean.testpike.common.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ScriptStyleContractTest {

    private static String wrap(String stepsJson) {
        return """
                {"version":"v2","caseId":"TC-B6-001","endType":"web",
                 "steps":[%s],"env":{"ENV":"t"}}
                """.formatted(stepsJson);
    }

    @Test
    void check_规范脚本_过闸零违规() {
        String json = wrap("""
                {"action":"goto","target":{"url":"{{ENV:URL}}/login"},"description":"打开登录页"},
                {"action":"input","target":{"css":"#user","chain":["css","text"]},"value":"{{DATA:testUser}}","description":"输入测试账号"},
                {"action":"input","target":{"css":"#pwd","chain":["css"]},"value":"{{ENV:PWD}}","description":"输入登录密码"},
                {"action":"click","target":{"css":"#login-btn","chain":["css","role"]},"description":"点击登录按钮"},
                {"action":"wait","target":{"css":".dashboard"},"state":"visible","description":"等待仪表盘可见"},
                {"action":"expect","target":{"text":"欢迎回来"},"description":"断言登录成功提示"}
                """);
        List<ScriptStyleViolation> violations = ScriptStyleContract.check(json);
        assertTrue(violations.isEmpty(), "规范脚本不应有违规: " + violations);
    }

    @Test
    void check_nthChild脆弱定位链_点名() {
        String json = wrap("""
                {"action":"click","target":{"css":"div > ul > li:nth-child(3) > a","chain":["css"]},"description":"点击第三个菜单"},
                {"action":"expect","target":{"text":"ok"},"description":"断言结果"}
                """);
        List<ScriptStyleViolation> violations = ScriptStyleContract.check(json);
        assertEquals(1, violations.size());
        ScriptStyleViolation v = violations.get(0);
        assertEquals(1, v.stepNo());
        assertEquals(ScriptStyleContract.CATEGORY_FRAGILE_SELECTOR, v.category());
        assertTrue(v.suggestion().contains("稳定语义定位"), "建议须指向规范定位姿势: " + v.suggestion());
        assertTrue(v.problem().contains("nth-child"), "违例描述须点名脆弱链形态: " + v.problem());
        assertTrue(v.problem().contains("第 1 步"), "人话须带步号: " + v.problem());
    }

    @Test
    void check_wait毫秒硬等待_点名且带定位符等待合法() {
        String json = wrap("""
                {"action":"wait","ms":5000,"description":"等5秒"},
                {"action":"wait","target":{"css":"#result"},"state":"visible","description":"等结果出现"},
                {"action":"expect","target":{"text":"done"},"description":"断言完成"}
                """);
        List<ScriptStyleViolation> violations = ScriptStyleContract.check(json);
        assertEquals(1, violations.size(), "仅无定位符的 ms 硬等待被点名: " + violations);
        ScriptStyleViolation v = violations.get(0);
        assertEquals(1, v.stepNo());
        assertEquals(ScriptStyleContract.CATEGORY_HARD_WAIT, v.category());
        assertTrue(v.suggestion().contains("定位"), "建议须指向定位等待姿势: " + v.suggestion());
    }

    @Test
    void check_零断言操作流_脚本级点名() {
        String json = wrap("""
                {"action":"goto","target":{"url":"{{ENV:URL}}"},"description":"打开首页"},
                {"action":"click","target":{"css":"#buy","chain":["css"]},"description":"点击购买"},
                {"action":"input","target":{"css":"#qty","chain":["css"]},"value":"2","description":"填写数量"}
                """);
        List<ScriptStyleViolation> violations = ScriptStyleContract.check(json);
        assertEquals(1, violations.size());
        assertEquals(ScriptStyleContract.CATEGORY_NO_ASSERTION, violations.get(0).category());
        assertEquals(0, violations.get(0).stepNo(), "脚本级违例步号为 0");
        assertTrue(violations.get(0).suggestion().contains("expect"), "建议须指向断言动作: " + violations.get(0).suggestion());
    }

    @Test
    void check_明文口令入value_点名() {
        String json = wrap("""
                {"action":"input","target":{"css":"#password","chain":["css"]},"value":"Admin@123","description":"输入密码"},
                {"action":"expect","target":{"text":"ok"},"description":"断言"}
                """);
        List<ScriptStyleViolation> violations = ScriptStyleContract.check(json);
        assertEquals(1, violations.size());
        assertEquals(ScriptStyleContract.CATEGORY_PLAINTEXT_CREDENTIAL, violations.get(0).category());
        assertEquals(1, violations.get(0).stepNo());
        assertTrue(violations.get(0).suggestion().contains("{{ENV:"), "建议须指向占位符注入: " + violations.get(0).suggestion());
    }

    @Test
    void check_占位符与登录态value_不误报口令() {
        String json = wrap("""
                {"action":"input","target":{"css":"#password","chain":["css"]},"value":"{{ENV:PWD}}","description":"输入环境密码"},
                {"action":"input","target":{"css":"#username","chain":["css"]},"value":"{{DATA:user}}","description":"输入数据池账号"},
                {"action":"expect","target":{"text":"ok"},"description":"断言"}
                """);
        assertTrue(ScriptStyleContract.check(json).isEmpty(), "占位符注入合法: " + ScriptStyleContract.check(json));
    }

    @Test
    void check_描述缺失或非中文_点名() {
        String json = wrap("""
                {"action":"click","target":{"css":"#a","chain":["css"]}},
                {"action":"click","target":{"css":"#b","chain":["css"]},"description":"click button b"},
                {"action":"expect","target":{"text":"ok"},"description":"断言成功"}
                """);
        List<ScriptStyleViolation> violations = ScriptStyleContract.check(json);
        assertEquals(2, violations.size());
        assertEquals(List.of(1, 2), violations.stream().map(ScriptStyleViolation::stepNo).toList());
        assertTrue(violations.stream().allMatch(
                v -> ScriptStyleContract.CATEGORY_STEP_DESC.equals(v.category())));
        assertTrue(violations.get(0).problem().contains("缺少"), "无描述=缺失口径: " + violations.get(0).problem());
        assertTrue(violations.get(1).problem().contains("非中文"), "英文描述=非中文口径: " + violations.get(1).problem());
    }

    @Test
    void check_loop嵌套子步_同扫违规定位() {
        String json = wrap("""
                {"action":"loop","count":3,"description":"循环三次",
                 "steps":[{"action":"click","target":{"xpath":"//ul/li[2]","chain":["xpath"]},"description":"点第二项子步"}]},
                {"action":"expect","target":{"text":"ok"},"description":"断言"}
                """);
        List<ScriptStyleViolation> violations = ScriptStyleContract.check(json);
        assertEquals(1, violations.size());
        assertEquals(ScriptStyleContract.CATEGORY_FRAGILE_SELECTOR, violations.get(0).category());
        assertEquals(2, violations.get(0).stepNo(), "深度优先全局编号：loop=1，子步=2");
    }

    @Test
    void check_setup段_同扫硬等待与缺降级链() {
        String json = """
                {"version":"v2","caseId":"TC-B6-002","endType":"web",
                 "setup":[{"action":"wait","ms":3000,"description":"前置硬等3秒"},
                          {"action":"click","target":{"css":"#go"},"description":"点击跳转"}],
                 "steps":[{"action":"expect","target":{"text":"ok"},"description":"断言就绪"}],
                 "env":{"ENV":"t"}}
                """;
        List<ScriptStyleViolation> violations = ScriptStyleContract.check(json);
        assertEquals(2, violations.size(), "setup 链路同扫: " + violations);
        assertEquals(ScriptStyleContract.CATEGORY_HARD_WAIT, violations.get(0).category());
        assertEquals(ScriptStyleContract.CATEGORY_MISSING_CHAIN, violations.get(1).category());
    }

    @Test
    void check_非法JSON_返回人话违例不抛栈() {
        List<ScriptStyleViolation> violations = ScriptStyleContract.check("{not-json");
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).problem().contains("非法"), "非法 JSON 人话点名: " + violations);
    }

    @Test
    void check_每条违例_建议文案非空() {
        String json = wrap("""
                {"action":"wait","ms":9000,"description":"硬等"},
                {"action":"click","target":{"css":"li:nth-of-type(4)"},"description":"点第四"}
                """);
        List<ScriptStyleViolation> violations = ScriptStyleContract.check(json);
        assertTrue(violations.size() >= 2);
        assertTrue(violations.stream().allMatch(
                v -> v.suggestion() != null && !v.suggestion().isBlank()));
        assertTrue(violations.stream().allMatch(
                v -> v.problem() != null && !v.problem().isBlank()));
    }
}
