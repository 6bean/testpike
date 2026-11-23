package com.sixbean.testpike.aiservice.judge.domain.service.impl;

import com.sixbean.testpike.aiservice.judge.domain.component.EvalTargetAdapter;
import com.sixbean.testpike.aiservice.judge.infrastructure.remote.JudgeRunBrief;
import com.sixbean.testpike.aiservice.judge.infrastructure.remote.JudgeSampleBrief;


final class StubEvalTargetAdapter implements EvalTargetAdapter {

    private final String answer;
    private final RuntimeException failure;

    int calls;
    JudgeRunBrief lastRun;
    JudgeSampleBrief lastSample;

    StubEvalTargetAdapter(String answer, RuntimeException failure) {
        this.answer = answer;
        this.failure = failure;
    }

    @Override
    public String type() {
        return "http";
    }

    @Override
    public String fetchEvaluatedText(JudgeRunBrief run, JudgeSampleBrief sample) {
        calls++;
        lastRun = run;
        lastSample = sample;
        if (failure != null) {
            throw failure;
        }
        return answer;
    }
}
