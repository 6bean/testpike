package com.sixbean.testpike.core.skill.domain.service;

import com.sixbean.testpike.core.skill.api.dto.SkillAssemblyItem;
import com.sixbean.testpike.core.skill.api.dto.SkillUsageInput;

import java.util.List;


public interface SkillConsumptionService {

    
    List<SkillAssemblyItem> getAssemblyItems(Long projectId);

    
    int recordUsages(List<SkillUsageInput> usages);
}
