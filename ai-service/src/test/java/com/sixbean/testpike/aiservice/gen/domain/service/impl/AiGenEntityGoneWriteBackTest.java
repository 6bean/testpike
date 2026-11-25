package com.sixbean.testpike.aiservice.gen.domain.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.domain.component.CaseJsonParser;
import com.sixbean.testpike.aiservice.gen.domain.component.CoverageChecker;
import com.sixbean.testpike.aiservice.gen.domain.component.PriorityRule;
import com.sixbean.testpike.aiservice.gen.domain.component.ScriptLimitResolver;
import com.sixbean.testpike.aiservice.gen.domain.component.SelfCheckValidator;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.support.GenTestFixtures;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.AiGenMessage;
import com.sixbean.testpike.common.util.ActionJsonValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Failure write-back noise posture of the generation task (defect ledger,
 * R53 log-noise gate): a task deleted while its generation is in flight can
 * never receive the markFailed write-back — core's not-found rejection is the
 * expected race outcome, not a persistence incident. Such rejections must be
 * recognised through the single entity-gone vocabulary (common
 * EntityGoneSignals, R40 — no second token list) and logged at WARN naming
 * from deleted-task retries tripped the log-noise ledger, 2026-11-07).
 * <p>The teeth case keeps the ERROR signature for genuine write-back outages
 * (core unreachable), so the demotion cannot swallow a real persistence
 * failure — same posture as DocExtractEntityGoneWriteBackTest.
 * <p>L2 posture: CoreGateway / router / chain are infrastructure mocks; the
 * real admin→core→MQ chain lives at L4.
 */
class AiGenEntityGoneWriteBackTest {

    private static final long TASK_ID = 41L;
    private static final long PROJECT_ID = 3L;
    private static final long OPERATOR_ID = 11L;

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);
    private final AiGenServiceImpl service = new AiGenServiceImpl(
            llmChain, llmRouter, coreGateway, new ObjectMapper(),
            new CaseJsonParser(new ObjectMapper()), new SelfCheckValidator(),
            new CoverageChecker(), new PriorityRule(), new ActionJsonValidator(),
            new ScriptLimitResolver(coreGateway));

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogCapture() {
        Logger logger = (Logger) LoggerFactory.getLogger(AiGenServiceImpl.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogCapture() {
        ((Logger) LoggerFactory.getLogger(AiGenServiceImpl.class)).detachAppender(logAppender);
        logAppender.stop();
    }

    /** Drives the failure path: the LLM leg rejects, so handleFailure writes back. */
    private void stubGenerationFailure() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(GenTestFixtures.endpoint());
        when(coreGateway.getProject(PROJECT_ID)).thenReturn(
                new CoreGateway.ProjectBrief(PROJECT_ID, "reg", "zh-CN", 1));
        when(llmChain.chatHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class)))
                .thenThrow(new ServiceException("AI 供应商调用失败", 50201));
    }

    private List<String> errorsContaining(String marker) {
        return logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains(marker))
                .collect(Collectors.toList());
    }

    private List<String> warnsContaining(String marker) {
        return logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains(marker))
                .collect(Collectors.toList());
    }

    /**
 * Deleted task (core not-found on markFailed): demoted to a WARN naming
 * skipped honestly — its retry anchor ("ai-gen-fail:taskId") has no host.
 */
    @Test
    void generate_任务已删除_失败回写降WARN不误报写入异常() {
        stubGenerationFailure();
        doThrow(new ServiceException("AI 生成任务不存在", BizErrorCode.NOT_FOUND))
                .when(coreGateway).markFailed(anyLong(), any(), any());

        assertThrows(ServiceException.class,
                () -> service.generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID,
                        1, "用户可以使用正确的账号密码登录系统", null, null)));

        assertEquals(List.of(), errorsContaining("AI failure notification write error"),
                "已删除任务的回写拒止是竞态预期，禁记入写入异常 ERROR 族（R53 噪音闸）");
        assertEquals(1, warnsContaining("task deleted").size(),
                "必须有一条点名跳过原因的 WARN（失败通知无宿主如实入账）");
        verify(coreGateway, never()).createNotification(anyLong(), anyLong(),
                any(), any(), any(), any());
    }

    /**
     * Stopped-task race (core state-machine rejection on markFailed): the
     * row is already terminal, so the failure write-back rejection is the
     * expected race outcome after a product-door cancel. Demoted through
     * the shared write-back race vocabulary (common
     * TerminalStateRaceSignals, the one source, R40) to a WARN naming the
     * skip — it must not keep the ERROR persistence-incident signature.
     */
    @Test
    void generate_任务已停止_失败回写竞态降WARN不误报写入异常() {
        stubGenerationFailure();
        doThrow(new ServiceException("任务 #41 当前为「已停止」，无法回报失败：仅「生成中」的任务可置失败",
                BizErrorCode.STATE_MACHINE_REJECT))
                .when(coreGateway).markFailed(anyLong(), any(), any());

        assertThrows(ServiceException.class,
                () -> service.generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID,
                        1, "用户可以使用正确的账号密码登录系统", null, null)));

        assertEquals(List.of(), errorsContaining("AI failure notification write error"),
                "终态回写竞态是预期结果，禁记入写入异常 ERROR 族（R53 噪音闸）");
        assertEquals(1, warnsContaining("task already terminal").size(),
                "必须有一条点名跳过原因的 WARN（失败通知无落点如实入账）");
        verify(coreGateway, never()).createNotification(anyLong(), anyLong(),
                any(), any(), any(), any());
    }

    /**
     * Completion write-back raced by a stop: the generation itself
     * succeeded and the late markDone rejection (code + guard phrase)
     * reaches the failure handler. Nothing is left to record — skip the
     * failure write-back, the notice and the terminal event, log one WARN.
     */
    @Test
    void generate_完成回写竞态_不再落失败不弹通知只WARN() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(GenTestFixtures.endpoint());
        when(llmChain.chatHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class)))
                .thenThrow(new ServiceException("任务 #41 当前为「已停止」，无法回报完成：仅「生成中」的任务可完成",
                        BizErrorCode.STATE_MACHINE_REJECT));

        assertThrows(ServiceException.class,
                () -> service.generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID,
                        1, "用户可以使用正确的账号密码登录系统", null, null)));

        verify(coreGateway, never()).markFailed(anyLong(), any(), any());
        verify(coreGateway, never()).createNotification(anyLong(), anyLong(),
                any(), any(), any(), any());
        assertEquals(List.of(), errorsContaining("AI failure notification write error"),
                "预期竞态不落 ERROR 签名");
        assertEquals(1, warnsContaining("task already terminal").size(),
                "回写竞态必须有一条点名跳过原因的 WARN");
    }

    /**
     * Teeth case for the phrase guard: a state-machine rejection without
     * one of the write-back guard phrases (foreign domain conflict) must
     * NOT be folded into the race skip — the failure write-back is still
     * attempted and the retry notice still lands.
     */
    @Test
    void generate_非回写族状态机拒止_失败处理照常执行() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(GenTestFixtures.endpoint());
        when(llmChain.chatHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class)))
                .thenThrow(new ServiceException("目标状态机拒绝：该对象不在可操作状态",
                        BizErrorCode.STATE_MACHINE_REJECT));

        assertThrows(ServiceException.class,
                () -> service.generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID,
                        1, "用户可以使用正确的账号密码登录系统", null, null)));

        verify(coreGateway).markFailed(eq(TASK_ID), any(), any());
        verify(coreGateway).createNotification(eq(OPERATOR_ID), eq(PROJECT_ID),
                eq("AI_GEN_FAILED"), any(), any(), any());
    }

    /**
     * Teeth case: a genuine write-back outage (core unreachable) keeps its
     * ERROR signature — the demotion must never fold a real persistence
     * failure into the race noise.
     */
    @Test
    void generate_回写通道真实故障_ERROR签名保留() {
        stubGenerationFailure();
        doThrow(new RuntimeException("core 连接拒绝"))
                .when(coreGateway).markFailed(anyLong(), any(), any());

        assertThrows(ServiceException.class,
                () -> service.generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID,
                        1, "用户可以使用正确的账号密码登录系统", null, null)));

        assertEquals(1, errorsContaining("AI failure notification write error").size(),
                "非实体删除族的回写故障必须保持 ERROR（禁把真实持久化失败折叠成竞态噪音）");
    }
}
