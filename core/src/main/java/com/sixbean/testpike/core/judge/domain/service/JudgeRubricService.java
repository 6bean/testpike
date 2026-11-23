package com.sixbean.testpike.core.judge.domain.service;

import com.sixbean.testpike.core.judge.api.dto.RubricFamilyView;
import com.sixbean.testpike.core.judge.api.dto.RubricSaveInput;
import com.sixbean.testpike.core.judge.api.dto.RubricView;

import java.util.List;


public interface JudgeRubricService {

    
    RubricView save(RubricSaveInput input);

 
    RubricView cloneVersion(Long sourceId, Long operatorId);

    
    RubricView detail(Long id);

    
    List<RubricView> listVersions(String name, String domain);

    /**
 * Family-head enumeration for the run-creation picker : one
 * latest-version row per (name, domain), ordered by name then domain.
 * Read-only projection over the same version-family table — the
 * versioning/grayscale judgement never moves out of save/cloneVersion.
 */
    List<RubricFamilyView> listLatest();
}
