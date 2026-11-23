package com.sixbean.testpike.core.skill.domain.service;

import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.skill.api.dto.SkillInfo;
import com.sixbean.testpike.core.skill.api.dto.SkillMarketDownloadView;
import com.sixbean.testpike.core.skill.api.dto.SkillMarketItem;
import com.sixbean.testpike.core.skill.api.dto.SkillMarketQuery;


public interface SkillMarketService {

    
    PageResult<SkillMarketItem> marketList(SkillMarketQuery query);

    
    SkillMarketDownloadView download(Long skillId, String format);

    
    SkillInfo importSkill(Long operatorId, Long projectId, String filename, byte[] content);
}
