package com.sixbean.testpike.aiservice.gen.domain.service.impl;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.domain.component.ScenarioListParser;
import com.sixbean.testpike.aiservice.gen.domain.component.ScenarioStrategy;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * EF batch piece 2: the stage-0 scenario enumeration return point
 * consumes the single-source RecallDensityGate verdict so its hot key
 * ai.gen.scenario-recall-density-floor-per-ktoken has a real consumer. The
 * V503 calibration booked this plane at 0 (a row of scenarios is not
 * comparable to per-item planes on the same denominator), and 0 is the
 * check-off state — so the shipped posture must stay byte-silent while an
 * explicitly set floor still names the shortfall on WARN. The measurement
 * is the scenario-item count (not lines), the denominator the gate's
 * char→estimated-token helper over the requirement text the run was shown.
 * <p>L2 posture: infrastructure mocks only, same face as
 * ScenarioEnumerationServiceTest.
 */
class ScenarioEnumerationDensityFloorTest {

    private static final long TASK_ID = 79L;
    private static final long PROJECT_ID = 3L;
    private static final String KEY_FLOOR =
            "ai.gen.scenario-recall-density-floor-per-ktoken";
    private static final String WARN_MARKER = "场景枚举密度下限闸";

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);
    private final ScenarioEnumerationServiceImpl service = new ScenarioEnumerationServiceImpl(
            llmChain, llmRouter, coreGateway, new ScenarioListParser(new ObjectMapper()));

    private ListAppender<ILoggingEvent> appender;

    private static final String HEAD = "用户可以使用正确的账号密码登录系统，密码长度 8-20 位。";
    private static final String PAD = "本系统部署于内网环境，接口响应时间不超过两秒。";

    private static final String COMPLETE_DRAFT = """
            {"scenarios":[
            {"kind":"main-flow","title":"正确账号密码登录成功","anchor":"正确的账号密码登录系统"},
            {"kind":"equivalence","title":"合法邮箱账号登录","anchor":"账号"},
            {"kind":"counter-example","title":"错误密码被拒绝","anchor":"正确的账号密码"},
            {"kind":"boundary","title":"密码长度取 8 与 20 边界","anchor":"密码长度 8-20 位"}]}""";

    @BeforeEach
    void attachLogCapture() {
        appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(ScenarioEnumerationServiceImpl.class))
                .addAppender(appender);
    }

    @AfterEach
    void detachLogCapture() {
        ((Logger) LoggerFactory.getLogger(ScenarioEnumerationServiceImpl.class))
                .detachAppender(appender);
    }

    private void stubRun() {
        when(coreGateway.getSysConfig(ScenarioStrategy.KEY_EXPAND_ENABLED)).thenReturn("1");
        when(coreGateway.getSysConfig(ScenarioStrategy.KEY_MAX_PER_REQUIREMENT))
                .thenReturn("8");
        when(coreGateway.getSysConfig(
                com.sixbean.testpike.aiservice.llm.pipeline.DeliberationLoop.GOVERNANCE_KEY))
                        .thenReturn("2");
        when(llmRouter.route(PROJECT_ID)).thenReturn(
                com.sixbean.testpike.aiservice.support.GenTestFixtures.endpoint());
        when(llmChain.chat(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(new LlmChatResult(COMPLETE_DRAFT, "mock", "mock-model", 10, 20, 5));
    }

    private boolean warnSeen() {
        return appender.list.stream().anyMatch(e -> e.getFormattedMessage()
                .startsWith(WARN_MARKER));
    }

    /** Shipped posture: the factory row is 0, so even a long requirement with
     *  four scenarios stays silent (check-off is an honest state, not an
     *  absent consumer). */
    @Test
    void factoryZeroFloor_checkOff_staysSilent() {
        stubRun();
        when(coreGateway.getSysConfig(KEY_FLOOR)).thenReturn("0");

        service.enumerate(HEAD + PAD.repeat(300), 0, PROJECT_ID, 11L, TASK_ID);

        assertFalse(warnSeen(), "the booked 0 must keep the floor fully out");
    }

    /** With an explicit calibrated floor the consumer is really wired: 9046
     *  chars -> 6785 estimated tokens -> floor 20 demands 136 items, the 4
     *  enumerated scenarios trip the WARN face. */
    @Test
    void positiveFloor_underFloorScenarios_warnsNumbers() {
        stubRun();
        when(coreGateway.getSysConfig(KEY_FLOOR)).thenReturn("20");

        service.enumerate(HEAD + PAD.repeat(300), 0, PROJECT_ID, 11L, TASK_ID);

        org.junit.jupiter.api.Assertions.assertTrue(warnSeen(),
                "an explicitly enabled floor must name the shortfall on WARN");
    }
}
