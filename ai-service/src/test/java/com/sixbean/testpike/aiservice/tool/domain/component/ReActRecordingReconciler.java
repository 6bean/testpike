package com.sixbean.testpike.aiservice.tool.domain.component;

import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Recording finish door for the clarification-suspension specs: captures
 * every suspend-lane transcript and counts finish consults, so a spec can
 * prove WHICH lane a run exited through. Standalone class — no inner types.
 */
final class ReActRecordingReconciler implements ReActFinishReconciler {

    final List<List<ChatMessage>> suspendCalls = new ArrayList<>();
    int finishCalls;

    @Override
    public String takeFinishNudge(List<ChatMessage> transcript) {
        finishCalls++;
        return null;
    }

    @Override
    public void onRunSuspend(List<ChatMessage> transcript) {
        suspendCalls.add(transcript);
    }
}
