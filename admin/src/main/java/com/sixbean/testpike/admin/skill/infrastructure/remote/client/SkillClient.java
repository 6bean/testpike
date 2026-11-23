package com.sixbean.testpike.admin.skill.infrastructure.remote.client;

import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.skill.api.dto.ProjectSkillBindingRequest;
import com.sixbean.testpike.admin.skill.api.dto.ProjectSkillView;
import com.sixbean.testpike.admin.skill.api.dto.SkillCreateRequest;
import com.sixbean.testpike.admin.skill.api.dto.SkillMarketDownloadView;
import com.sixbean.testpike.admin.skill.api.dto.SkillMarketItemView;
import com.sixbean.testpike.admin.skill.api.dto.SkillRecycleView;
import com.sixbean.testpike.admin.skill.api.dto.SkillRejectRequest;
import com.sixbean.testpike.admin.skill.api.dto.SkillUpdateRequest;
import com.sixbean.testpike.admin.skill.api.dto.SkillVersionDetailView;
import com.sixbean.testpike.admin.skill.api.dto.SkillVersionView;
import com.sixbean.testpike.admin.skill.api.dto.SkillView;

import java.util.List;


public interface SkillClient {

    PageView<SkillView> list(String keyword, Integer type, Integer status, Long projectId,
                             Long tagId, String sortBy, String sortDir, int page, int size);

    SkillView create(SkillCreateRequest request);

    SkillView get(long id);

    SkillView update(long id, SkillUpdateRequest request);

    void delete(long id, int expectedVersion);


    PageView<SkillRecycleView> recycleBin(Long projectId, String keyword, int page, int size);

    SkillRecycleView recycleEntry(long id);

    SkillView restore(long id, int expectedVersion);

    SkillView submit(long id, int expectedVersion);

    SkillView approve(long id, int expectedVersion);

    SkillView reject(long id, SkillRejectRequest request);

    SkillView retire(long id, int expectedVersion);

    List<SkillVersionView> versions(long id);

    SkillVersionDetailView version(long id, int version);


    PageView<SkillMarketItemView> marketList(int page, int size, String keyword, Integer type,
                                            Long tagId, String sortBy, String sortDir);

    SkillMarketDownloadView marketDownload(long id, String format);

    SkillView marketImport(Long projectId, String filename, byte[] content);


    ProjectSkillView projectSkills(long projectId);

    ProjectSkillView saveProjectSkills(long projectId, ProjectSkillBindingRequest request);
}
