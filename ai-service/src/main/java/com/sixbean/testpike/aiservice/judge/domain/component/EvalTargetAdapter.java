package com.sixbean.testpike.aiservice.judge.domain.component;

import com.sixbean.testpike.aiservice.judge.infrastructure.remote.JudgeRunBrief;
import com.sixbean.testpike.aiservice.judge.infrastructure.remote.JudgeSampleBrief;


public interface EvalTargetAdapter {

    
    String type();

    
    String fetchEvaluatedText(JudgeRunBrief run, JudgeSampleBrief sample);
}
