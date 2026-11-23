package com.sixbean.testpike.core.tool.domain.service;

import com.sixbean.testpike.core.tool.api.dto.ProjectToolBindingInput;
import com.sixbean.testpike.core.tool.api.dto.ProjectToolView;


public interface ProjectToolService {

    
    ProjectToolView saveBindings(Long operatorId, Long projectId, ProjectToolBindingInput input);

    
    ProjectToolView getBindings(Long projectId);
}
