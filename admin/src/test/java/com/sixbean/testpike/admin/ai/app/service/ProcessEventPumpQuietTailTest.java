package com.sixbean.testpike.admin.ai.app.service;

import com.sixbean.testpike.admin.aichat.app.component.StreamFrameRecorder;
import com.sixbean.testpike.admin.aichat.infrastructure.remote.client.CoreAiChatClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongFunction;

import static com.sixbean.testpike.admin.aichat.app.component.StreamPumpConfigDoors.quietGuardConfigDoor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2 spectra of the shared tail pump's quiet guards: silence while the job
 * is still running is a legitimate phase (one long model call idles for
 * minutes), not a failure. The pump must therefore land a step-level
 * "still running" milestone and keep the viewer tailing; only a real door
 * outage — proven by the sibling spectra in {@code StepsGenEventStreamBridgeTest} —
 * ends the stream with an error frame. The give-up that does exist is the
 * hot-configurable single-window cap, and even that seals honestly: the
 * frame says the run may still be live and reconnecting continues from the
 * same tail row.
 */
class ProcessEventPumpQuietTailTest {

    private static final LongFunction<List<ProcessEventRow>> NO_NEW_ROWS = cursor -> List.of();

    @Test
    void quietWhileRunningKeepsStreamOpenWithMilestoneInsteadOfError() {
        CoreAiChatClient door = quietGuardConfigDoor(5, 3600);
        StreamFrameRecorder writer = new StreamFrameRecorder();
        // The probe flips settled as soon as the first quiet milestone lands,
        // so the spectra seals within one idle interval instead of parking the suite.
        BooleanSupplier settledAfterMilestone = () -> !writer.frameTypes.isEmpty();

        new ProcessEventPump(door).tail("提取", NO_NEW_ROWS, settledAfterMilestone, 0, writer);

        assertTrue(writer.completed, "a settled run must seal the stream");
        assertFalse(writer.frameTypes.contains("error"),
                "a quiet-but-still-running job must not surface a false disconnect");
        assertEquals(List.of("step"), writer.frameTypes,
                "quiet must land exactly one step-level milestone, no error frame");
        assertTrue(writer.payloads.get(0).contains("仍在进行"),
                "the milestone must tell the viewer the run is still in progress");
        assertTrue(writer.payloads.get(0).contains("继续跟随"),
                "the milestone must say the stream keeps following");
    }

    @Test
    void singleWindowCapSealsHonestlyAndPromisesReconnectContinuation() {
        CoreAiChatClient door = quietGuardConfigDoor(5, 10);
        StreamFrameRecorder writer = new StreamFrameRecorder();

        new ProcessEventPump(door).tail("步骤生成", NO_NEW_ROWS, () -> false, 0, writer);

        assertTrue(writer.completed, "the single-window cap must end the stream");
        assertFalse(writer.frameTypes.contains("error"),
                "reaching the window cap is a designed close, not an error face");
        assertTrue(writer.payloads.stream().anyMatch(p -> p.contains("仍在进行")),
                "the quiet milestone must have landed before the cap sealed the window");
        String last = writer.payloads.get(writer.payloads.size() - 1);
        assertTrue(last.contains("重连后可继续跟随"),
                "the seal frame must name the reconnect continuation with the W2 wording");
    }

    /**
     * The drawer prints a level label before every row body, so a body that
     * opens with the task subject ("step generation process ...") repeats the
     * label word on screen — the recap shots proved the doubled rendering
     * "step" + "step generation process exceeded ...". The pump's own
     * step-level frames (quiet milestone, window-cap seal) must therefore read
     * cleanly right after the label. Door-outage error frames keep their
     * subject: they open with an explicit "cannot read" verb, so no word is
     * duplicated against the "error" label.
     */
    @Test
    void pumpOwnStepFramesCarryNoSubjectPrefixThatWouldDoubleTheRowLabel() {
        CoreAiChatClient door = quietGuardConfigDoor(5, 10);
        StreamFrameRecorder writer = new StreamFrameRecorder();

        new ProcessEventPump(door).tail("步骤生成", NO_NEW_ROWS, () -> false, 0, writer);

        List<String> stepBodies = new java.util.ArrayList<>();
        for (int i = 0; i < writer.frameTypes.size(); i++) {
            if ("step".equals(writer.frameTypes.get(i))) {
                stepBodies.add(writer.payloads.get(i));
            }
        }
        assertFalse(stepBodies.isEmpty(), "the spectra must land step-level frames");
        for (String body : stepBodies) {
            assertFalse(body.startsWith("步骤生成"),
                    "a step body must not open with the task subject it repeats on screen: " + body);
            assertFalse(body.startsWith("步骤"),
                    "a step body must not open with the first word of the level label: " + body);
        }
    }

    /**
     * R62 value-side guard on the pump's own frames (backend msg surface):
     * a manual-refresh instruction pushes the viewer onto a dead-end path
     * while the tail window already keeps the state fresh and reloads the
     * affected lists silently at the terminal event. Neither the quiet
     * milestone nor the single-window-cap seal may carry the wording family;
     * the negative self-sample keeps the pattern from rotting silently.
     */
    @Test
    void pumpStepFramesCarryNoManualRefreshInstructionWording() {
        java.util.regex.Pattern zhForbidden =
                java.util.regex.Pattern.compile("(请刷新|刷新后重试|刷新页面|刷新后的列表|以刷新)");
        CoreAiChatClient door = quietGuardConfigDoor(5, 10);
        StreamFrameRecorder writer = new StreamFrameRecorder();

        new ProcessEventPump(door).tail("提取", NO_NEW_ROWS, () -> false, 0, writer);

        assertFalse(writer.payloads.isEmpty(), "the spectra must land step-level frames");
        for (String body : writer.payloads) {
            assertFalse(zhForbidden.matcher(body).find(),
                    "a pump frame must not instruct a manual refresh: " + body);
        }
        assertTrue(zhForbidden.matcher("任务状态请以刷新后的列表为准").find(),
                "the guard pattern must reject a planted refresh instruction");
        assertTrue(zhForbidden.matcher("也可刷新页面查看最新状态").find(),
                "the guard pattern must reject a planted trailing refresh clause");
    }
}
