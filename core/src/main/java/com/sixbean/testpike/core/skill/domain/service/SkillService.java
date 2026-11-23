package com.sixbean.testpike.core.skill.domain.service;

import com.sixbean.testpike.core.skill.api.dto.SkillRecycleInfo;

import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.skill.api.dto.SkillCreateInput;
import com.sixbean.testpike.core.skill.api.dto.SkillInfo;
import com.sixbean.testpike.core.skill.api.dto.SkillQuery;
import com.sixbean.testpike.core.skill.api.dto.SkillUpdateInput;
import com.sixbean.testpike.core.skill.api.dto.SkillVersionDetail;
import com.sixbean.testpike.core.skill.api.dto.SkillVersionInfo;

import java.util.List;


public interface SkillService {

 
    SkillInfo create(Long operatorId, SkillCreateInput input);

    
    SkillInfo get(Long id);

    
    PageResult<SkillInfo> list(SkillQuery query);

    
    SkillInfo update(Long operatorId, Long id, SkillUpdateInput input);

 
    List<SkillVersionInfo> listVersions(Long id);

 
    SkillVersionDetail getVersion(Long id, int version);

    
    void delete(Long operatorId, Long id, int expectedVersion);

    
    com.sixbean.testpike.core.common.clientobject.PageResult<SkillRecycleInfo> recycleBin(
            Long projectId, String keyword, int page, int size);

    
    SkillRecycleInfo recycleEntry(Long id);

    
    SkillInfo restore(Long operatorId, Long id, int expectedVersion);

    
    SkillInfo submit(Long operatorId, Long id, int expectedVersion);

    
    SkillInfo approve(Long operatorId, Long id, int expectedVersion);

    
    SkillInfo reject(Long operatorId, Long id, int expectedVersion, String reason);

    
    SkillInfo retire(Long operatorId, Long id, int expectedVersion);
}
