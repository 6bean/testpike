package com.sixbean.testpike.aiservice.aictx.domain.service.impl;

import com.sixbean.testpike.aiservice.aictx.infrastructure.remote.SystemDraftGateway;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.pipeline.BehaviorKernelHeadProbe;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.mockito.Mockito.mock;

/**
 * Behaviour-kernel wiring proof for the correction-distillation leg
 * : the system head must come from the
 * shared L0 assembly single source, keeping the output contract and the
 * input-isolation fence on the built-in contract side. Assertion bodies
 * live in BehaviorKernelHeadProbe (R40 — the three-path contract is proven
 * once, consumed per leg).
 */
class CtxDistillDirectivesHeadTest {

    private static final String BUILTIN_HEAD_PREFIX = "你是测试工程约定归纳助手";

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final CtxDistillServiceImpl service = new CtxDistillServiceImpl(
            mock(NamedParameterJdbcTemplate.class), mock(LlmFallbackChain.class),
            mock(SystemDraftGateway.class), coreGateway);

    @Test
    void publishedTemplateRowReplacesBuiltinContractAndKernelClauseAppearsOnce() {
        BehaviorKernelHeadProbe.publishedRowBecomesHeadAndClauseAppearsOnce(
                coreGateway, CtxDistillServiceImpl.KEY_DIRECTIVES_ENABLED,
                CtxDistillServiceImpl.TEMPLATE_NAME, CtxDistillServiceImpl.TEMPLATE_DOMAIN,
                service::behaviorDirectivesHead, BUILTIN_HEAD_PREFIX);
    }

    @Test
    void governanceKeyClosedSkipsTemplateReadAndKeepsBuiltinContract() {
        BehaviorKernelHeadProbe.closedGateSkipsTemplateReadAndKeepsContract(
                coreGateway, CtxDistillServiceImpl.KEY_DIRECTIVES_ENABLED,
                service::behaviorDirectivesHead, BUILTIN_HEAD_PREFIX);
    }

    @Test
    void templateReadFailureDegradesToBuiltinContractWithoutBreaking() {
        BehaviorKernelHeadProbe.failingReadDegradesToContractWithoutBreaking(
                coreGateway, CtxDistillServiceImpl.KEY_DIRECTIVES_ENABLED,
                service::behaviorDirectivesHead, BUILTIN_HEAD_PREFIX);
    }
}
