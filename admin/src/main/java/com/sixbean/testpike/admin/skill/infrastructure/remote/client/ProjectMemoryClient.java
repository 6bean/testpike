package com.sixbean.testpike.admin.skill.infrastructure.remote.client;

import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.skill.api.dto.ProjectMemoryCreateRequest;
import com.sixbean.testpike.admin.skill.api.dto.ProjectMemoryView;

/**
 * core project-memory ledger client (02 contract /api/v1/project-memory →
 * core /internal/project-memory doors incl. the memory reject exit, .
 */
public interface ProjectMemoryClient {

    PageView<ProjectMemoryView> list(Long projectId, String keyword, Integer injectStatus,
                                     int page, int size);

    ProjectMemoryView create(ProjectMemoryCreateRequest request);

    ProjectMemoryView setInjectStatus(long id, Long projectId, int injectStatus);

    ProjectMemoryView reject(long id, Long projectId);

    void delete(long id, Long projectId);

    PageView<ProjectMemoryView> recycleBin(Long projectId, int page, int size);

    ProjectMemoryView restore(long id, Long projectId);
}
