package com.sixbean.testpike.aiservice.gen.domain.service.impl;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.common.mq.AiGenMessage;
import com.sixbean.testpike.common.webelement.PageWriteRiskView;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stage-1 injection contract for the write-risk ledger feedback: pages the
 * explore guard already booked as "typing saves directly" must steer the case
 * designer toward save-idempotency and input-debounce coverage. The door is
 * the single existing risk-page read (same gate the explore kernel rides), so
 * this class asserts wiring only — anchor presence, clean-project zero ripple,
 * fail-soft on outage and the honest capped listing.
 */
class AiGenServiceImplPageWriteRiskWiringTest {

    private static final long TASK_ID = 97L;
    private static final long PROJECT_ID = 3L;
    private static final long OPERATOR_ID = 11L;
    private static final long NEW_CASE_ID = 971L;
    private static final String ANCHOR = "写入风险页清单";

    private final CoreGateway coreGateway = GenWiringHarness.mockCoreGateway();
    private final LlmFallbackChain llmChain = GenWiringHarness.mockLlmChain();
    private final ProjectLlmRouter llmRouter = GenWiringHarness.mockLlmRouter();

    private final AiGenServiceImpl service = GenWiringHarness.assembleWithPromptAdoption(
            coreGateway, llmChain, llmRouter);

    private final List<String> userMessages = new ArrayList<>();

    private static final String STAGE1_JSON = """
            {"title":"备注自动保存","precondition":"列表页可访问",
             "steps":[{"stepNo":1,"description":"连续输入备注","isAssert":0},
                      {"stepNo":2,"description":"断言仅一次保存","isAssert":1}],
             "expectedResult":"保存一次","priority":1,"confidence":1,"endType":"web"}""";

    private static final String STAGE2_JSON = "{\"version\":\"v2\",\"caseId\":\"TC-REG-001\",\"endType\":\"web\","
            + "\"setup\":[{\"action\":\"goto\",\"target\":{\"url\":\"{{ENV:URL}}/notes\"}}],"
            + "\"steps\":[{\"action\":\"input\",\"target\":{\"css\":\"#note\",\"chain\":[\"css\"]},\"value\":\"a\"},"
            + "{\"action\":\"expect\",\"target\":{\"text\":\"已保存\"}}],"
            + "\"env\":{\"ENV\":\"dev\"}}";

    private void stubBase() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(GenWiringHarness.ENDPOINT);
        when(coreGateway.getProject(PROJECT_ID)).thenReturn(
                new CoreGateway.ProjectBrief(PROJECT_ID, "reg", "zh-CN", 0));
        when(coreGateway.createCase(eq(OPERATOR_ID), any())).thenReturn(
                Map.of("id", NEW_CASE_ID, "caseNo", "TC-reg-971"));
        when(coreGateway.getSysConfig(any())).thenReturn(null);
    }

    private void stubLlm() {
        when(llmChain.chatHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class)))
                .thenAnswer(inv -> {
                    ChatCompletionRequest r = inv.getArgument(0);
                    userMessages.add(r.messages().get(1).content());
                    String content = r.messages().get(0).content().contains("STAGE1_CASE_JSON")
                            ? STAGE1_JSON : STAGE2_JSON;
                    return new LlmChatHandle(
                            new LlmChatResult(content, "openai", "m", 100, 50, 20L),
                            GenWiringHarness.ENDPOINT);
                });
    }

    @Test
    void 风险页清单回灌stage1提示并点名幂等防抖要求() {
        stubBase();
        when(coreGateway.listPageWriteRiskPages(PROJECT_ID)).thenReturn(List.of(
                new PageWriteRiskView("k-notes", "http://t.local/notes")));
        stubLlm();

        service.generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 1, "备注需求", null, null));

        String stage1User = userMessages.get(0);
        assertTrue(stage1User.contains(ANCHOR), "风险页锚未注入 stage1：\n" + stage1User);
        assertTrue(stage1User.contains("http://t.local/notes"), "风险页 URL 未列出");
        assertTrue(stage1User.contains("保存幂等"), "保存幂等要求未点名");
        assertTrue(stage1User.contains("防抖"), "输入防抖要求未点名");
    }

    @Test
    void 零风险页提示词零涟漪() {
        stubBase();
        when(coreGateway.listPageWriteRiskPages(PROJECT_ID)).thenReturn(List.of());
        stubLlm();

        service.generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 1, "备注需求", null, null));

        assertTrue(userMessages.stream().noneMatch(u -> u.contains(ANCHOR)),
                "干净项目禁悬空锚：风险账为空时不得出现风险段");
        verify(coreGateway).createCase(eq(OPERATOR_ID), any());
    }

    @Test
    void 风险门故障生成不失败failsoft() {
        stubBase();
        when(coreGateway.listPageWriteRiskPages(anyLong()))
                .thenThrow(new ResourceAccessException("core down",
                        new java.io.IOException("connect refused")));
        stubLlm();

        service.generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 1, "备注需求", null, null));

        assertTrue(userMessages.stream().noneMatch(u -> u.contains(ANCHOR)),
                "门故障时不得注入半截风险段");
        verify(coreGateway).createCase(eq(OPERATOR_ID), any());
    }

    @Test
    void 超上限尾剪后诚实报剩余数() {
        stubBase();
        List<PageWriteRiskView> many = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            many.add(new PageWriteRiskView("k" + i, "http://t.local/p" + i));
        }
        when(coreGateway.listPageWriteRiskPages(PROJECT_ID)).thenReturn(many);
        stubLlm();

        service.generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 1, "备注需求", null, null));

        String stage1User = userMessages.get(0);
        assertTrue(stage1User.contains("http://t.local/p20"), "前 20 页应全部列出");
        assertTrue(!stage1User.contains("http://t.local/p21"), "第 21 页起应尾剪");
        assertTrue(stage1User.contains("另有 5 个"), "被尾剪的页须诚实报剩余数：\n" + stage1User);
    }

}
