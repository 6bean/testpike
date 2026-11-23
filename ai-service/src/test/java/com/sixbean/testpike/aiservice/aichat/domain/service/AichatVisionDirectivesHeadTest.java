package com.sixbean.testpike.aiservice.aichat.domain.service;

import com.sixbean.testpike.aiservice.aichat.domain.component.ChatUserImagePromptTemplate;
import com.sixbean.testpike.aiservice.aichat.domain.component.FailureScenePromptTemplate;
import com.sixbean.testpike.aiservice.aichat.domain.component.FailureScreenshotBytesFetcher;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.AiCallLogWriter;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.pipeline.BehaviorKernelHeadProbe;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

/**
 * Behaviour-kernel wiring proof for the two dialog vision observation legs
 * : user-image reading and failure-screenshot
 * reading each resolve their system head through the shared L0 assembly —
 * published row of their own template family while the governance gate is
 * open, the built-in observation discipline otherwise, kernel invariant
 * appended exactly once either way. The per-call anchors (file name, failure
 * detail) stay code-side in the user turn, so an operator rewording the
 * template can never drop what the model is being asked to look at.
 * Assertion bodies live in BehaviorKernelHeadProbe (R40 single source).
 */
class AichatVisionDirectivesHeadTest {

    private static final String USER_IMAGE_PREFIX = "你是测试报障读图助手";
    private static final String FAILURE_SCENE_PREFIX = "你是测试失败现场读图助手";

    private final CoreGateway coreGateway = mock(CoreGateway.class);

    private final ChatUserImageResolver userImageResolver = new ChatUserImageResolver(
            coreGateway, mock(LlmFallbackChain.class), mock(AiCallLogWriter.class),
            new ChatUserImagePromptTemplate(), mock(FailureScreenshotBytesFetcher.class));

    private final FailureScreenshotResolver failureSceneResolver = new FailureScreenshotResolver(
            coreGateway, mock(LlmFallbackChain.class), mock(AiCallLogWriter.class),
            new FailureScenePromptTemplate(), mock(FailureScreenshotBytesFetcher.class));

    @Test
    void userImagePublishedRowBecomesHeadAndClauseAppearsOnce() {
        BehaviorKernelHeadProbe.publishedRowBecomesHeadAndClauseAppearsOnce(
                coreGateway, ChatUserImageResolver.KEY_DIRECTIVES_ENABLED,
                ChatUserImageResolver.TEMPLATE_NAME, ChatUserImageResolver.TEMPLATE_DOMAIN,
                userImageResolver::behaviorDirectivesHead, USER_IMAGE_PREFIX);
    }

    @Test
    void userImageClosedGateSkipsTemplateReadAndKeepsBuiltinContract() {
        BehaviorKernelHeadProbe.closedGateSkipsTemplateReadAndKeepsContract(
                coreGateway, ChatUserImageResolver.KEY_DIRECTIVES_ENABLED,
                userImageResolver::behaviorDirectivesHead, USER_IMAGE_PREFIX);
    }

    @Test
    void userImageReadFailureDegradesToBuiltinContractWithoutBreaking() {
        BehaviorKernelHeadProbe.failingReadDegradesToContractWithoutBreaking(
                coreGateway, ChatUserImageResolver.KEY_DIRECTIVES_ENABLED,
                userImageResolver::behaviorDirectivesHead, USER_IMAGE_PREFIX);
    }

    @Test
    void failureScenePublishedRowBecomesHeadAndClauseAppearsOnce() {
        BehaviorKernelHeadProbe.publishedRowBecomesHeadAndClauseAppearsOnce(
                coreGateway, FailureScreenshotResolver.KEY_DIRECTIVES_ENABLED,
                FailureScreenshotResolver.TEMPLATE_NAME, FailureScreenshotResolver.TEMPLATE_DOMAIN,
                failureSceneResolver::behaviorDirectivesHead, FAILURE_SCENE_PREFIX);
    }

    @Test
    void failureSceneClosedGateSkipsTemplateReadAndKeepsBuiltinContract() {
        BehaviorKernelHeadProbe.closedGateSkipsTemplateReadAndKeepsContract(
                coreGateway, FailureScreenshotResolver.KEY_DIRECTIVES_ENABLED,
                failureSceneResolver::behaviorDirectivesHead, FAILURE_SCENE_PREFIX);
    }

    @Test
    void failureSceneReadFailureDegradesToBuiltinContractWithoutBreaking() {
        BehaviorKernelHeadProbe.failingReadDegradesToContractWithoutBreaking(
                coreGateway, FailureScreenshotResolver.KEY_DIRECTIVES_ENABLED,
                failureSceneResolver::behaviorDirectivesHead, FAILURE_SCENE_PREFIX);
    }
}
