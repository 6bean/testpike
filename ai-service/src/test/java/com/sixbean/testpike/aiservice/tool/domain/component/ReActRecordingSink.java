package com.sixbean.testpike.aiservice.tool.domain.component;

import java.util.ArrayList;
import java.util.List;

/**
 * Recording sink shared by the streaming ReAct tests (single source): keeps
 * the arrival order of everything the loop pushes, prefixed with the frame
 * kind the SSE mapping uses, so trajectory assertions read like the wire.
 * Standalone class — no inner types.
 */
final class ReActRecordingSink implements ReActStreamSink {

    final List<String> log = new ArrayList<>();

    @Override
    public void onDelta(String text) {
        log.add("delta:" + text);
    }

    @Override
    public void onReason(String text) {
        log.add("reason:" + text);
    }

    @Override
    public void onToolStart(String toolName) {
        log.add("tool_start:" + toolName);
    }

    @Override
    public void onToolDone(String toolName, boolean success, String output) {
        log.add("tool_done:" + toolName + ":" + success
                + (output == null ? "" : ":" + output));
    }

    @Override
    public void onUserInject(String text, int stepNo) {
        log.add("user_inject:" + stepNo + ":" + text);
    }
}
