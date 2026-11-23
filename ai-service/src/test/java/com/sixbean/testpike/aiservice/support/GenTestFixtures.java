package com.sixbean.testpike.aiservice.support;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.common.mq.AiGenMessage;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * Single-source fixtures for the generation-chain tests (R40: the same stub
 * payload appeared in four sibling test classes, so any contract change had to
 * be written four times). Deterministic stage-1/stage-2 LLM answers, the routed
 * endpoint stub, document chunk rows and the stage-1 prompt capture all live
 * here; test classes consume these and never copy them.
 */
public final class GenTestFixtures {

    private GenTestFixtures() {
    }

    /** Routed endpoint without a catalog input limit (legacy stub shape). */
    public static LlmEndpointConfig endpoint() {
        return new LlmEndpointConfig("openai", "routed-model", "dummy-key",
                "http://localhost:4000/v1", LlmEndpointConfig.SOURCE_CONFIG);
    }

    /** Routed endpoint carrying a catalog input limit (budget-clamp assertions). */
    public static LlmEndpointConfig endpointWithInputLimit(int promptLimitTokens) {
        return new LlmEndpointConfig("openai", "routed-model", "dummy-key",
                "http://localhost:4000/v1", LlmEndpointConfig.SOURCE_CONFIG, promptLimitTokens);
    }

    /** Generation message for the shared project/operator ids. */
    public static AiGenMessage message(long taskId, long projectId, long operatorId,
                                       int taskType, Long requirementId, Long caseId,
                                       String requirementText) {
        return new AiGenMessage(taskId, projectId, operatorId, taskType,
                requirementText, requirementId, caseId);
    }

    /** Deterministic stage-1 answer: a structured case that passes the self-check. */
    public static String stage1Json() {
        return """
                {"title":"正确账号密码登录成功","precondition":"系统登录页可访问",
                 "steps":[{"stepNo":1,"description":"输入正确的账号和密码","isAssert":0},
                          {"stepNo":2,"description":"点击登录按钮","isAssert":0},
                          {"stepNo":3,"description":"断言跳转首页","isAssert":1}],
                 "expectedResult":"登录成功跳转首页","priority":0,"confidence":1,"endType":"web"}""";
    }

    /** Deterministic stage-2 answer: action JSON v2 on the whitelisted actions. */
    public static String stage2Json() {
        return "{\"version\":\"v2\",\"caseId\":\"TC-REG-001\",\"endType\":\"web\","
                + "\"setup\":[{\"action\":\"goto\",\"target\":{\"url\":\"{{ENV:URL}}/login\"}}],"
                + "\"steps\":[{\"action\":\"input\",\"target\":{\"css\":\"#username\",\"chain\":[\"css\"]},"
                + "\"value\":\"{{DATA:testUser}}\"},"
                + "{\"action\":\"click\",\"target\":{\"css\":\"#login-btn\",\"chain\":[\"css\"]}},"
                + "{\"action\":\"expect\",\"target\":{\"text\":\"登录成功跳转首页\"}}],"
                + "\"env\":{\"ENV\":\"dev\"}}";
    }

    /** One document chunk row in the CoreGateway pull shape. */
    public static Map<String, Object> chunkRow(long id, int seq, String chapter, String content) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("seq", seq);
        row.put("chapter", chapter);
        row.put("content", content);
        return row;
    }

    /** Three-chunk login document used by the original-text assertions. */
    public static List<Map<String, Object>> loginDocumentChunks() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(chunkRow(11L, 1, "2.1 登录", "登录页提供账号与密码两个输入框"));
        rows.add(chunkRow(12L, 2, "2.1 登录", "连续三次密码错误账号锁定十分钟"));
        rows.add(chunkRow(13L, 3, "2.2 首页", "登录成功后跳转首页并展示用户名"));
        return rows;
    }

    /** User prompt of the first chat call made on this chain mock. */
    public static String firstStage1Prompt(LlmFallbackChain chain, long projectId) {
        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(chain, atLeastOnce())
                .chatHandle(captor.capture(), eq(projectId), any(LlmFallbackNotice.class));
        return captor.getAllValues().get(0).messages().get(1).content();
    }

    /** Requirement brief in the pre- wire shape (no anchor field at all). */
    public static CoreGateway.RequirementBrief legacyBrief(long id, String title,
                                                           String acceptanceCriteria) {
        return new CoreGateway.RequirementBrief(id, title, acceptanceCriteria);
    }
}
