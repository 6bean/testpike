package com.sixbean.testpike.aiservice.aichat.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.aichat.domain.component.TimeTravelContext;
import com.sixbean.testpike.aiservice.aichat.infrastructure.remote.ExecStepEvidenceRemote;
import com.sixbean.testpike.aiservice.explore.infrastructure.remote.ExecTaskResultRemote;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Time-travel context block for the replay capsule :
 * the dialog turn pinned to one execution task carries the step timeline
 * (stepNo / action / status / wall-clock duration) plus the six evidence-slot
 * presence marks, so the model can answer "why was step N slow or failing"
 * and cite the step number the client locates the frame with.
 *
 * <p>L2 posture: the core doors are stubbed here only to isolate
 * infrastructure; the real admin/core read path is exercised by the L3/L4
 * legs of the batch closure package.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TimeTravelContextTest {

    @Mock
    private CoreGateway coreGateway;

    private TimeTravelContextResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new TimeTravelContextResolver(coreGateway, new ObjectMapper());
    }

    @Test
    void noPinnedTaskYieldsNoneAndZeroOutboundCalls() {
        TimeTravelContext context = resolver.resolve(null);

        assertFalse(context.present());
        assertEquals("", context.block());
        verifyNoInteractions(coreGateway);
    }

    @Test
    void timelineRowsRenderStepNoActionStatusAndDuration() {
        // Real APP executor shape (executor/app_worker.py): durationMs is the
        // per-step authority, tsStart/tsEnd are epoch-SECONDS (float), never
        // milliseconds — the block must read the contract the producer sends.
        when(coreGateway.getExecTaskResult(77L)).thenReturn(task(
                "[{\"stepNo\":1,\"action\":\"app.open\",\"result\":\"pass\",\"durationMs\":500,"
                        + "\"tsStart\":1761800000.123,\"tsEnd\":1761800000.623},"
                        + "{\"stepNo\":2,\"action\":\"app.tap\",\"result\":\"fail\",\"durationMs\":8000,"
                        + "\"tsStart\":1761800001.0,\"tsEnd\":1761800009.0},"
                        + "{\"stepNo\":3,\"action\":\"app.input\",\"result\":\"pass\"}]"));
        when(coreGateway.taskStepEvidence(77L)).thenReturn(List.of());

        TimeTravelContext context = resolver.resolve(77L);

        assertTrue(context.present());
        String block = context.block();
        assertTrue(block.contains("第1步 app.open 成功"), block);
        assertTrue(block.contains("第2步 app.tap 失败"), block);
        assertTrue(block.contains("耗时 8000ms"), block);
        // honesty: a step without any duration source says so, no fake 0ms
        assertTrue(block.contains("第3步 app.input 成功"), block);
        assertTrue(block.contains("耗时未知"), block);
    }

    @Test
    void webDriverIsoTimestampsYieldRealDurations() {
        // Real WEB_DRIVER shape (executor/driver/webdriver.py StepResult):
        // startAt/endAt are local ISO strings whose offset carries NO colon
        // (%z -> "+0800"); durations derive from the pair.
        when(coreGateway.getExecTaskResult(81L)).thenReturn(task(
                "[{\"stepNo\":1,\"action\":\"click #login\",\"status\":\"ok\","
                        + "\"startAt\":\"2026-10-30T09:00:01+0800\",\"endAt\":\"2026-10-30T09:00:03+0800\"},"
                        + "{\"stepNo\":2,\"action\":\"click #submit\",\"status\":\"fail\","
                        + "\"startAt\":\"2026-10-30T09:00:04+0800\",\"endAt\":\"2026-10-30T09:00:12+0800\"}]"));
        when(coreGateway.taskStepEvidence(81L)).thenReturn(List.of());

        String block = resolver.resolve(81L).block();

        assertTrue(block.contains("第2步 click #submit 失败 耗时 8000ms"), block);
        assertTrue(block.contains("第1步 click #login 成功 耗时 2000ms"), block);
    }

    @Test
    void epochSecondTimestampsDeriveDurationsWithoutDurationMs() {
        // APP rows can carry the double-clock pair without durationMs when the
        // adapter short-circuits; seconds must not be read as milliseconds.
        when(coreGateway.getExecTaskResult(82L)).thenReturn(task(
                "[{\"stepNo\":1,\"action\":\"app.tap\",\"status\":\"ok\","
                        + "\"tsStart\":1761800000.5,\"tsEnd\":1761800009.0}]"));
        when(coreGateway.taskStepEvidence(82L)).thenReturn(List.of());

        String block = resolver.resolve(82L).block();

        assertTrue(block.contains("耗时 8500ms"), block);
    }

    @Test
    void sixEvidenceSlotsShowPresenceAndAbsentIsNotNoEvidenceRow() {
        when(coreGateway.getExecTaskResult(78L)).thenReturn(task(
                "[{\"stepNo\":1,\"action\":\"app.tap\",\"status\":\"ok\"}]"));
        when(coreGateway.taskStepEvidence(78L)).thenReturn(List.of(
                new ExecStepEvidenceRemote(78L, 1, "app.tap", "shot/1.png", "http://p/1",
                        "vt/1.json", "http://p/2", null, null, "rec/1.mp4", "http://p/3",
                        null, null, null, null)));

        TimeTravelContext context = resolver.resolve(78L);

        String block = context.block();
        assertTrue(block.contains("证据[截图=有 视图树=有 日志=缺 录屏=有 弱网=缺 性能=缺]"), block);
    }

    @Test
    void noEvidenceRowsStillRenderPresenceMarks() {
        when(coreGateway.getExecTaskResult(79L)).thenReturn(task(
                "[{\"stepNo\":1,\"action\":\"app.open\",\"status\":\"ok\"}]"));
        when(coreGateway.taskStepEvidence(79L)).thenReturn(List.of());

        String block = resolver.resolve(79L).block();

        // executor never uploaded != the step has no evidence conceptually:
        // all six slots honestly read as missing, the step line stays.
        assertTrue(block.contains("证据[截图=缺 视图树=缺 日志=缺 录屏=缺 弱网=缺 性能=缺]"), block);
    }

    @Test
    void longTimelineIsBoundedWithOmissionNote() {
        StringBuilder timeline = new StringBuilder("[");
        for (int i = 1; i <= 200; i++) {
            timeline.append(i > 1 ? "," : "")
                    .append("{\"stepNo\":").append(i)
                    .append(",\"action\":\"app.step\",\"status\":\"ok\"}");
        }
        timeline.append("]");
        when(coreGateway.getExecTaskResult(80L)).thenReturn(task(timeline.toString()));
        when(coreGateway.taskStepEvidence(80L)).thenReturn(List.of());

        String block = resolver.resolve(80L).block();

        long rendered = block.lines().filter(l -> l.contains("app.step")).count();
        assertEquals(40, rendered, "prompt-weight cap: at most 40 step lines");
        assertTrue(block.contains("共200步，中间省略"), block);
        assertFalse(block.contains("第41步"), block);
    }

    @Test
    void unreadableTaskDegradesToHonestDisclaimerBlock() {
        when(coreGateway.getExecTaskResult(anyLong()))
                .thenThrow(new ServiceException("任务不存在"));

        TimeTravelContext context = resolver.resolve(81L);

        assertTrue(context.present(), "the degrade note itself rides the prompt so the model disclaims");
        assertTrue(context.block().contains("#81"), context.block());
    }

    @Test
    void malformedTimelineDegradesToHonestDisclaimerBlock() {
        when(coreGateway.getExecTaskResult(82L)).thenReturn(task("{not-a-timeline"));
        when(coreGateway.taskStepEvidence(82L)).thenReturn(List.of());

        TimeTravelContext context = resolver.resolve(82L);

        assertTrue(context.present());
        assertTrue(context.block().contains("#82"), context.block());
        assertTrue(context.block().contains("时间线"), context.block());
    }

    @Test
    void blankTimelineDegradesToHonestDisclaimerBlock() {
        when(coreGateway.getExecTaskResult(83L)).thenReturn(task(null));
        when(coreGateway.taskStepEvidence(83L)).thenReturn(List.of());

        TimeTravelContext context = resolver.resolve(83L);

        assertTrue(context.present());
        assertTrue(context.block().contains("#83"), context.block());
    }

    private static ExecTaskResultRemote task(String stepsTimeline) {
        return new ExecTaskResultRemote(77L, 3L, 5L, 1L, 4, null, null, null, null,
                null, null, null, stepsTimeline, null, null, null, null, null, null, null);
    }
}
