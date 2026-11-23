package com.sixbean.testpike.aiservice.llm.domain.service.impl;

import com.sixbean.testpike.common.util.ActionJsonValidator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatToolDef;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatToolFunction;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.LlmToolCall;
import com.sixbean.testpike.common.util.LlmExtractMarkers;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class MockLlmProviderTest {

    private final MockLlmProvider provider = new MockLlmProvider("mock-model");
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 供应商名为mock() {
        assertEquals("mock", provider.providerName());
    }

    @Test
    void 输出确定性_提取最后一条用户消息() {
        ChatCompletionRequest req = new ChatCompletionRequest("mock-model", List.of(
                new ChatMessage("system", "你是测试专家"),
                new ChatMessage("user", "登录功能生成用例")), null, null);
        LlmChatResult r1 = provider.chat(req);
        LlmChatResult r2 = provider.chat(req);
        assertTrue(r1.content().startsWith("【mock 生成】"));
        assertTrue(r1.content().contains("登录功能生成用例"));
        assertEquals(r1.content(), r2.content());
        assertEquals("mock", r1.provider());
        assertEquals("mock-model", r1.model());
        assertEquals(10, r1.promptTokens());
    }

    @Test
    void 阶段1标记_返回结构化用例JSON() throws Exception {
        ChatCompletionRequest req = new ChatCompletionRequest("mock-model", List.of(
                new ChatMessage("system", "输出 " + MockLlmProvider.STAGE1_MARKER),
                new ChatMessage("user", "用户登录功能：输入账号密码点击登录")), null, null);

        JsonNode root = mapper.readTree(provider.chat(req).content());

        assertTrue(root.has("title"));
        assertTrue(root.has("precondition"));
        assertTrue(root.has("expectedResult"));
        assertTrue(root.path("steps").isArray() && !root.path("steps").isEmpty());
        assertEquals("0", root.path("priority").asText());
        assertEquals(2, root.path("confidence").asInt());
        assertEquals("web", root.path("endType").asText());
    }

    @Test
    void 阶段2标记_返回动作JSONv2() throws Exception {
        ChatCompletionRequest req = new ChatCompletionRequest("mock-model", List.of(
                new ChatMessage("system", "输出 " + MockLlmProvider.STAGE2_MARKER),
                new ChatMessage("user", "用户登录功能")), null, null);

        JsonNode root = mapper.readTree(provider.chat(req).content());

        assertEquals("v2", root.path("version").asText());
        assertTrue(root.path("setup").isArray() && !root.path("setup").isEmpty());
        assertTrue(root.path("steps").isArray() && !root.path("steps").isEmpty());
        assertTrue(root.path("env").has("ENV"));
        assertTrue(root.path("steps").toString().contains("\"css\""));
    }

    /** Drafting marker: deterministic stub-bundle JSON with one rule per requirement line . */
    @Test
    void mockDraftMarker_returnsDeterministicStubBundle() throws Exception {
        ChatCompletionRequest req = new ChatCompletionRequest("mock-model", List.of(
                new ChatMessage("system", "输出 " + MockLlmProvider.MOCK_DRAFT_GEN_MARKER),
                new ChatMessage("user", "需求描述：POST /api/orders 创建订单\nmock 空间：9")), null, null);

        JsonNode root = mapper.readTree(provider.chat(req).content());

        assertTrue(root.path("items").isArray() && !root.path("items").isEmpty());
        JsonNode item = root.path("items").get(0);
        assertEquals("POST", item.path("method").asText(), "leading verb token honored");
        assertTrue(item.path("path").asText().startsWith("/mock/endpoint-"));
        assertTrue(item.path("name").asText().contains("创建订单"));
        assertTrue(item.path("responseJson").asText().contains("200"));
    }

    /**
 * Edge-scenarios application point : the drafting marker plus
 * the job-type token in the system prompt must switch the stub branch to the
 * full fault playbook — 400/401/403/429/500 business shapes plus a delay
 * fault and a disconnect fault, all on the drafted endpoint.
 */
    @Test
    void mockDraftEdgeScenariosMarker_returnsFullFaultPlaybook() throws Exception {
        ChatCompletionRequest req = new ChatCompletionRequest("mock-model", List.of(
                new ChatMessage("system", "你是 API Mock 桩规则起草器（"
                        + MockLlmProvider.MOCK_DRAFT_GEN_MARKER + "，应用点=edge-scenarios）。"),
                new ChatMessage("user", "需求描述：GET /api/orders 查询订单\nmock 空间：9")), null, null);

        JsonNode items = mapper.readTree(provider.chat(req).content()).path("items");

        assertTrue(items.isArray() && items.size() >= 7, "edge pack must cover the full playbook, got " + items.size());
        java.util.Set<Integer> statuses = new java.util.HashSet<>();
        boolean hasDelay = false;
        boolean hasDisconnect = false;
        for (JsonNode item : items) {
            assertEquals("GET", item.path("method").asText());
            assertTrue(item.path("path").asText().startsWith("/mock/endpoint-"));
            assertTrue(item.path("name").asText().length() > 0);
            JsonNode builder = mapper.readTree(item.path("responseJson").asText());
            JsonNode fault = builder.path("fault");
            if (fault.isObject()) {
                String type = fault.path("type").asText();
                if ("delay".equals(type)) {
                    hasDelay = true;
                } else if ("disconnect".equals(type)) {
                    hasDisconnect = true;
                }
            } else {
                statuses.add(builder.path("status").asInt());
            }
        }
        assertTrue(statuses.containsAll(java.util.Set.of(400, 401, 403, 429, 500)),
                "business-shape items must cover 400/401/403/429/500, got " + statuses);
        assertTrue(hasDelay, "playbook must carry a slow-response delay fault");
        assertTrue(hasDisconnect, "playbook must carry a disconnect fault");
    }

    /**
 * Spec-sync application point : the marker plus the spec-sync
 * job-type token answers contract-sync stub proposals (200 builders carrying
 * a sync body) instead of the nl-stub single-rule shape.
 */
    @Test
    void mockDraftSpecSyncMarker_returnsContractSyncEntries() throws Exception {
        ChatCompletionRequest req = new ChatCompletionRequest("mock-model", List.of(
                new ChatMessage("system", "你是 API Mock 桩规则起草器（"
                        + MockLlmProvider.MOCK_DRAFT_GEN_MARKER + "，应用点=spec-sync）。"),
                new ChatMessage("user", "需求描述：POST /api/orders 创建订单\nmock 空间：9")), null, null);

        JsonNode items = mapper.readTree(provider.chat(req).content()).path("items");

        assertTrue(items.isArray() && !items.isEmpty());
        JsonNode item = items.get(0);
        assertEquals("POST", item.path("method").asText());
        JsonNode builder = mapper.readTree(item.path("responseJson").asText());
        assertEquals(200, builder.path("status").asInt(), "sync proposals answer the happy contract shape");
        assertTrue(builder.path("body").has("contractSync"), "sync body must be identifiable");
        assertTrue(item.path("name").asText().contains("契约同步"), "spec-sync rule name states its purpose");
    }


    
    private String realStage1Prompt(String requirement) {
        return "请针对以下需求生成 1 条结构化测试用例：\n" + requirement + "\n"
                + "输出字段：title（测试目标）、precondition（前置条件）、steps（步骤数组，元素含 "
                + "description 与 isAssert 0=操作 1=断言，编号从 1 连续）、expectedResult（预期结果）、"
                + "priority（0=P0 1=P1 2=P2）、confidence（1=高 2=中 3=低）、endType（web/mobile/pos/multi）。\n"
                + "内容语言：zh-CN";
    }

    @Test
    void 阶段1真实prompt_输出必须为可解析JSON且提取真实需求() throws Exception {
        ChatCompletionRequest req = new ChatCompletionRequest("mock-model", List.of(
                new ChatMessage("system", "你是资深测试工程师，请按 JSON 格式输出结构化测试用例（标记 "
                        + MockLlmProvider.STAGE1_MARKER + "）。请使用简体中文输出用例内容。"),
                new ChatMessage("user", realStage1Prompt("用户登录功能：输入用户名和密码，点击登录按钮，验证登录成功后进入首页"))), null, null);

        JsonNode root = mapper.readTree(provider.chat(req).content());

        assertTrue(root.path("title").asText().contains("用户登录功能"), "title 应来自需求文本而非指令: "
                + root.path("title").asText());
        assertTrue(root.path("steps").isArray() && !root.path("steps").isEmpty());
        String stepsText = root.path("steps").toString();
        assertTrue(!stepsText.contains("输出字段"), "步骤混入输出指令: " + stepsText);
        assertTrue(!stepsText.contains("内容语言"), "步骤混入语言指令: " + stepsText);
        assertEquals("0", root.path("priority").asText());
    }

    @Test
    void 阶段1真实prompt含验收标准_输出可解析() throws Exception {
        String prompt = realStage1Prompt("用户注册功能")
                + "\n验收标准（必须逐条覆盖，生成相应步骤）：\n1. 手机号格式校验；2. 注册成功进入首页";
        ChatCompletionRequest req = new ChatCompletionRequest("mock-model", List.of(
                new ChatMessage("system", "输出 " + MockLlmProvider.STAGE1_MARKER),
                new ChatMessage("user", prompt)), null, null);
        JsonNode root = mapper.readTree(provider.chat(req).content());
        assertTrue(root.path("title").asText().contains("用户注册功能"));
    }

    @Test
    void 阶段2真实prompt_输出可解析且过动作JSON校验() throws Exception {
        String prompt = "请将以下测试用例转换为动作 JSON v2：\n用例标题：用户登录功能\n前置条件：系统登录页可访问\n"
                + "步骤：\n1. 输入用户名密码（操作）\n2. 点击登录按钮（操作）\n3. 验证进入首页（断言）\n"
                + "预期结果：登录成功进入首页\n要求：version=v2、caseId、setup（前置脚本化，登录态用 {{ENV:...}} 占位）、"
                + "steps（线性，target 含 css 定位与 chain）、env 必填；动作仅限 goto/click/input/expect/wait/screenshot/if/loop；"
                + "单用例步骤 ≤50、嵌套 ≤5。";
        ChatCompletionRequest req = new ChatCompletionRequest("mock-model", List.of(
                new ChatMessage("system", "你是测试脚本专家，请按动作 JSON v2 格式输出可执行脚本（标记 "
                        + MockLlmProvider.STAGE2_MARKER + "；元素定位用 CSS 单策略，04 契约）。"),
                new ChatMessage("user", prompt)), null, null);

        String content = provider.chat(req).content();
        JsonNode root = mapper.readTree(content);
        assertEquals("v2", root.path("version").asText());
        com.sixbean.testpike.common.util.ActionJsonValidator validator =
                new com.sixbean.testpike.common.util.ActionJsonValidator();
        var check = validator.validate(content);
        assertTrue(check.passed(), "动作 JSON 校验应通过: " + check.errors());
    }


    @Test
    void 造题标记_返回带出处锚的QA对JSON() throws Exception {
        String chunk = "登录接口需要手机号与验证码，验证码有效期五分钟。";
        ChatCompletionRequest req = new ChatCompletionRequest("mock-model", List.of(
                new ChatMessage("system", "你是评测集造题器（" + LlmExtractMarkers.JUDGE_QA_GEN + "）。"),
                new ChatMessage("user", "请从以下文档分块中生成问答对：\n正文：" + chunk)), null, null);

        JsonNode root = mapper.readTree(provider.chat(req).content());
        JsonNode items = root.path("items");
        assertTrue(items.isArray() && items.size() >= 1, "造题分支须产出至少一条 QA 对");
        JsonNode item = items.get(0);
        assertTrue(item.path("question").asText().length() > 0);
        assertTrue(item.path("answer").asText().length() > 0);
        String anchor = item.path("sourceAnchor").asText();
        assertTrue(chunk.contains(anchor), "sourceAnchor 必须是正文逐字片段（接地锚）: " + anchor);
        assertEquals(provider.chat(req).content(), provider.chat(req).content());
    }

    @Test
    void 造题标记无正文_返回合法空items不炸链() throws Exception {
        ChatCompletionRequest req = new ChatCompletionRequest("mock-model", List.of(
                new ChatMessage("system", "输出 " + LlmExtractMarkers.JUDGE_QA_GEN),
                new ChatMessage("user", "请从以下文档分块中生成问答对：\n正文：")), null, null);
        JsonNode root = mapper.readTree(provider.chat(req).content());
        assertEquals(0, root.path("items").size(), "寒暄块合法零出题=items:[]，聚合兜底在编排层");
    }

    @Test
    void FC标记且带工具_返回首个工具的确定性tool_call() {
        ChatCompletionRequest req = new ChatCompletionRequest("mock-model",
                List.of(new ChatMessage("system", "按需调用工具（标记 "
                        + LlmExtractMarkers.FC_TOOL_CALL + "）"),
                        new ChatMessage("user", "查登录用例")),
                null, null,
                List.of(new ChatToolDef(new ChatToolFunction("kb_search", "检索", null)),
                        new ChatToolDef(new ChatToolFunction("other_tool", "其他", null))),
                "auto");
        LlmChatResult r = provider.chat(req);
        assertEquals(1, r.toolCalls().size(), "FC 标记下确定性调用清单首个工具");
        assertEquals("kb_search", r.toolCalls().get(0).name());
        assertEquals("{}", r.toolCalls().get(0).argumentsJson());
        assertTrue(r.toolCalls().get(0).id().startsWith("mock-call-"));
    }

    @Test
    void FC标记但无工具_回退普通输出toolCalls为null() {
        ChatCompletionRequest req = new ChatCompletionRequest("mock-model",
                List.of(new ChatMessage("system", "标记 " + LlmExtractMarkers.FC_TOOL_CALL),
                        new ChatMessage("user", "随便")),
                null, null);
        LlmChatResult r = provider.chat(req);
        assertEquals(null, r.toolCalls());
        assertTrue(r.content().startsWith("【mock 生成】"));
    }

    
    @Test
    void FC标记但已有工具回填_跳过FC出STAGE1用例JSON() {
        ChatCompletionRequest req = new ChatCompletionRequest("mock-model",
                List.of(new ChatMessage("system", "请按 JSON 格式输出结构化测试用例（标记 "
                                + LlmExtractMarkers.STAGE1_CASE + " 契约位 "
                                + LlmExtractMarkers.FC_TOOL_CALL + "）"),
                        new ChatMessage("user", "登录需求"),
                        ChatMessage.assistantWithToolCalls("",
                                List.of(new LlmToolCall("c1", "kb_search", "{}"))),
                        new ChatMessage("tool", "{\"rows\":1}", "c1", null)),
                null, null,
                List.of(new ChatToolDef(new ChatToolFunction("kb_search", "检索", null))),
                "auto");
        LlmChatResult r = provider.chat(req);
        assertEquals(null, r.toolCalls(), "回填在场=跳过 FC 不再发起 tool_call");
        JsonNode caseJson = null;
        try {
            caseJson = new ObjectMapper().readTree(r.content());
        } catch (Exception ignored) {
        }
        assertTrue(caseJson != null && caseJson.has("title"),
                "回落 STAGE1 用例 JSON 输出: " + r.content());
    }

    @Test
    void 无FC标记_toolCalls为null存量行为零漂移() {
        ChatCompletionRequest req = new ChatCompletionRequest("mock-model",
                List.of(new ChatMessage("user", "登录功能生成用例")), null, null,
                List.of(new ChatToolDef(new ChatToolFunction("kb_search", "检索", null))), "auto");
        LlmChatResult r = provider.chat(req);
        assertEquals(null, r.toolCalls());
        assertTrue(r.content().contains("登录功能生成用例"));
    }
}
