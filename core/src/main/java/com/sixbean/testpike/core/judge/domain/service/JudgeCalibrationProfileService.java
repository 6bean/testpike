package com.sixbean.testpike.core.judge.domain.service;

import com.sixbean.testpike.core.judge.api.dto.CalibrationGroupView;

import java.util.List;


public interface JudgeCalibrationProfileService {

    
    List<CalibrationGroupView> profile(String rubricName, String rubricDomain, Long llmConfigId);
}
