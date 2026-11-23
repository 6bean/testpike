package com.sixbean.testpike.aiservice.aichat.domain.service;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.tool.domain.component.ReActRunner;
import com.sixbean.testpike.aiservice.tool.domain.component.ReActStreamSink;
import com.sixbean.testpike.common.mq.AiChatRunMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.sixbean.testpike.aiservice.aichat.domain.service.GoalPlanOrchestratorFixtures.RUN;
import static com.sixbean.testpike.aiservice.aichat.domain.service.GoalPlanOrchestratorFixtures.outcomeWithAnswer;
import static com.sixbean.testpike.aiservice.aichat.domain.service.GoalPlanOrchestratorFixtures.stateFramePayloadsOf;
import static com.sixbean.testpike.aiservice.aichat.domain.service.GoalPlanOrchestratorFixtures.stubDialogDoors;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Assembly-stage progress frames of the failure-feedback
 * report): while the orchestrator is still assembling the grounding context,
 * the bubble must already show WHICH stage is running, in stream order —
 * variable parsing (only when the question references {{DATA:...}} keys),
 * then retrieval, then the model call itself. Frames ride the existing
 * state-frame channel (the client's stage line shows the latest state
 * sentence verbatim); no second event channel and no new frame type.
 */
class ChatRunOrchestratorAssemblyProgressTest {

    private CoreGateway gateway;

    private List<String> runAndCaptureStates(AiChatRunMessage run) {
        gateway = mock(CoreGateway.class);
        ReActRunner runner = mock(ReActRunner.class);
        ProjectLlmRouter router = mock(ProjectLlmRouter.class);
        GroundingService grounding = mock(GroundingService.class);
        stubDialogDoors(gateway, runner, router, grounding);
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), any(), any(),
                any(ReActStreamSink.class))).thenReturn(outcomeWithAnswer("答"));
        GoalPlanOrchestratorFixtures.newOrchestrator(gateway, runner, router, grounding, null)
                .run(run);
        return stateFramePayloadsOf(gateway);
    }

    private static AiChatRunMessage question(String prompt) {
        return new AiChatRunMessage(9L, 42L, 3L, 7L, prompt, List.of(), null, List.of());
    }

    private static int indexOfStage(List<String> states, String prefix) {
        for (int i = 0; i < states.size(); i++) {
            if (states.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void dataKeyReferenceNamesTheVariablesBeforeRetrieval() {
        List<String> states = runAndCaptureStates(question(
                "基于当前这个用例，{{DATA:MAIN_BRAND_CONFIG_ADMIN}}我该如何配置？"));
        int parsing = indexOfStage(states, "变量解析中");
        int retrieval = indexOfStage(states, "检索完成");
        assertTrue(parsing >= 0, "a {{DATA:}} question must get a variable-parsing frame, got: " + states);
        assertTrue(states.get(parsing).contains("MAIN_BRAND_CONFIG_ADMIN"),
                "the frame must name the referenced key, got: " + states.get(parsing));
        assertTrue(retrieval > parsing,
                "the stage sentence must advance parsing -> retrieval in stream order");
    }

    @Test
    void modelCallFrameFollowsRetrieval() {
        List<String> states = runAndCaptureStates(RUN);
        int retrieval = indexOfStage(states, "检索完成");
        int model = indexOfStage(states, "模型调用中");
        assertTrue(retrieval >= 0, "the legacy retrieval frame must stay, got: " + states);
        assertTrue(model > retrieval,
                "the latest stage before the answer arrives must be the model call, got: " + states);
    }

    @Test
    void plainQuestionWithoutDataKeysEmitsNoVariableFrame() {
        List<String> states = runAndCaptureStates(RUN);
        assertEquals(-1, indexOfStage(states, "变量解析中"),
                "a question referencing no data key must not claim a parsing stage");
    }

    @Test
    void manyDataKeysNameTheFirstThreeAndCountTheRest() {
        List<String> states = runAndCaptureStates(question(
                "{{DATA:KEY_A}} {{DATA:KEY_B}} {{DATA:KEY_C}} {{DATA:KEY_D}} 怎么配"));
        int parsing = indexOfStage(states, "变量解析中");
        assertTrue(parsing >= 0, "multi-key question must get the parsing frame, got: " + states);
        String sentence = states.get(parsing);
        assertTrue(sentence.contains("KEY_A") && sentence.contains("KEY_B")
                        && sentence.contains("KEY_C"),
                "the first three keys are named, got: " + sentence);
        assertTrue(!sentence.contains("KEY_D") && sentence.contains("4"),
                "beyond the cap the frame counts instead of listing, got: " + sentence);
    }
}
