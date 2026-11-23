package com.sixbean.testpike.core.skill.domain.service;

import com.sixbean.testpike.core.skill.api.dto.ProjectSkillBindingInput;
import com.sixbean.testpike.core.skill.api.dto.ProjectSkillView;


public interface ProjectSkillService {

    
    ProjectSkillView saveBindings(Long operatorId, Long projectId, ProjectSkillBindingInput input);

    
    ProjectSkillView getBindings(Long projectId);
}
