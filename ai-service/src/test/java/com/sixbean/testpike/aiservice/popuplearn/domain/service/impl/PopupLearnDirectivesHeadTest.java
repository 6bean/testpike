package com.sixbean.testpike.aiservice.popuplearn.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.AiCallLogWriter;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.pipeline.BehaviorKernelHeadProbe;
import com.sixbean.testpike.aiservice.popuplearn.domain.component.PopupLearnPromptTemplate;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

/**
 * Behaviour-kernel wiring proof for the popup-learning vision leg (
 * per-domain upgrade, group 1); three-path assertions consumed from the
 * shared BehaviorKernelHeadProbe single source.
 */
class PopupLearnDirectivesHeadTest {

    private static final String BUILTIN_HEAD_PREFIX = "你是移动端自动化测试的弹窗模式分析助手";

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final PopupLearnServiceImpl service = new PopupLearnServiceImpl(
            coreGateway, mock(LlmFallbackChain.class), mock(AiCallLogWriter.class),
            new ObjectMapper(), new PopupLearnPromptTemplate());

    @Test
    void publishedTemplateRowReplacesBuiltinContractAndKernelClauseAppearsOnce() {
        BehaviorKernelHeadProbe.publishedRowBecomesHeadAndClauseAppearsOnce(
                coreGateway, PopupLearnServiceImpl.KEY_DIRECTIVES_ENABLED,
                PopupLearnServiceImpl.TEMPLATE_NAME, PopupLearnServiceImpl.TEMPLATE_DOMAIN,
                service::behaviorDirectivesHead, BUILTIN_HEAD_PREFIX);
    }

    @Test
    void governanceKeyClosedSkipsTemplateReadAndKeepsBuiltinContract() {
        BehaviorKernelHeadProbe.closedGateSkipsTemplateReadAndKeepsContract(
                coreGateway, PopupLearnServiceImpl.KEY_DIRECTIVES_ENABLED,
                service::behaviorDirectivesHead, BUILTIN_HEAD_PREFIX);
    }

    @Test
    void templateReadFailureDegradesToBuiltinContractWithoutBreaking() {
        BehaviorKernelHeadProbe.failingReadDegradesToContractWithoutBreaking(
                coreGateway, PopupLearnServiceImpl.KEY_DIRECTIVES_ENABLED,
                service::behaviorDirectivesHead, BUILTIN_HEAD_PREFIX);
    }
}
