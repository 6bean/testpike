package com.sixbean.testpike.core.judge.domain.service;

import com.sixbean.testpike.core.judge.api.dto.JudgeArtifactGrantView;
import com.sixbean.testpike.core.judge.api.dto.RecordInput;
import com.sixbean.testpike.core.judge.api.dto.RecordView;


public interface JudgeRecordService {

    
    RecordView saveScore(RecordInput input);

    
    JudgeArtifactGrantView artifactGrant(long runId, long sampleId);
}
