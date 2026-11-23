package com.sixbean.testpike.core.project.domain.service;

import com.sixbean.testpike.core.project.api.dto.ProjectTemplateContent;
import com.sixbean.testpike.core.project.api.dto.ProjectTemplateCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectTemplateInfo;

import java.util.List;


public interface ProjectTemplateService {

    
    long create(Long operatorId, ProjectTemplateCreateInput input);

    
    ProjectTemplateInfo update(long templateId, ProjectTemplateCreateInput input);

    
    ProjectTemplateInfo get(long templateId);

    
    List<ProjectTemplateInfo> list();

    
    void delete(long templateId);

    
    void applyToProject(long templateId, Long operatorId, long projectId);

    
    void validateContent(ProjectTemplateContent content);
}
