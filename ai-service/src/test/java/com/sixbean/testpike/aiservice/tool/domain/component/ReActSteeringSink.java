package com.sixbean.testpike.aiservice.tool.domain.component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Sink that answers the mid-turn steering poll from a scripted queue and
 * journals only the inject markers (single-source counterpart of the
 * per-test recording sink for the steering specs). Standalone class — no
 * inner types.
 */
final class ReActSteeringSink implements ReActStreamSink {

    final Deque<List<String>> pollScript = new ArrayDeque<>();
    final List<String> steerLog = new ArrayList<>();
    /** Scripted finish door ; null = legacy plain finish. */
    ReActFinishReconciler reconciler;
    /** 1-based checkpoint-poll count at which the user abort becomes visible . */
    int abortFromPoll = Integer.MAX_VALUE;
    private int abortPolls;

    @Override
    public boolean aborted() {
        return ++abortPolls >= abortFromPoll;
    }

    @Override
    public ReActFinishReconciler finishReconciler() {
        return reconciler;
    }

    @Override
    public void onDelta(String text) {
    }

    @Override
    public void onReason(String text) {
    }

    @Override
    public void onToolStart(String toolName) {
    }

    @Override
    public void onToolDone(String toolName, boolean success, String output) {
    }

    @Override
    public List<String> takeSteeringTexts() {
        return pollScript.isEmpty() ? List.of() : pollScript.poll();
    }

    @Override
    public void onUserInject(String text, int stepNo) {
        steerLog.add("inject#" + stepNo + ":" + text);
    }
}
