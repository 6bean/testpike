package com.sixbean.testpike.admin.tool.infrastructure.remote.client;

import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.tool.api.dto.ProjectToolBindingRequest;
import com.sixbean.testpike.admin.tool.api.dto.ProjectToolView;
import com.sixbean.testpike.admin.tool.api.dto.ToolConfirmTicketView;
import com.sixbean.testpike.admin.tool.api.dto.ToolCreateRequest;
import com.sixbean.testpike.admin.tool.api.dto.ToolDetailView;
import com.sixbean.testpike.admin.tool.api.dto.ToolInvocationView;
import com.sixbean.testpike.admin.tool.api.dto.ToolInvokeRequest;
import com.sixbean.testpike.admin.tool.api.dto.ToolMarketDownloadView;
import com.sixbean.testpike.admin.tool.api.dto.ToolMarketItemView;
import com.sixbean.testpike.admin.tool.api.dto.ToolRejectRequest;
import com.sixbean.testpike.admin.tool.api.dto.ToolRollbackRequest;
import com.sixbean.testpike.admin.tool.api.dto.ToolTestRunInput;
import com.sixbean.testpike.admin.tool.api.dto.ToolTestRunResult;
import com.sixbean.testpike.admin.tool.api.dto.ToolUpdateRequest;
import com.sixbean.testpike.admin.tool.api.dto.ToolVersionDetailView;
import com.sixbean.testpike.admin.tool.api.dto.ToolVersionInfoView;
import com.sixbean.testpike.admin.tool.api.dto.ToolView;
import org.springframework.http.ResponseEntity;


public interface ToolClient {

    PageView<ToolView> list(String keyword, Integer type, Integer status, Long projectId,
                            String category, Long tagId, String sortBy, String sortDir,
                            int page, int size);

    ToolView create(ToolCreateRequest request);

    ToolView get(long id);

    ToolView update(long id, ToolUpdateRequest request);

    void delete(long id, int expectedVersion);

    ToolView submit(long id, int expectedVersion);

    ToolView approve(long id, int expectedVersion);

    ToolView reject(long id, ToolRejectRequest request);

    ToolView retire(long id, int expectedVersion);


    PageView<ToolMarketItemView> marketList(int page, int size, String keyword, Integer type,
                                            String category, String sortBy, String sortDir);

    ToolMarketDownloadView marketDownload(long id, String format);

    ToolView marketImport(Long projectId, String filename, byte[] content);


    ProjectToolView projectTools(long projectId);

    ProjectToolView saveProjectTools(long projectId, ProjectToolBindingRequest request);

    PageView<ToolInvocationView> invocations(long toolId, String start, String end,
                                             int page, int size);


    ToolTestRunResult testRun(long toolId, ToolTestRunInput input);


    ToolConfirmTicketView confirmTicket(long toolId);

    ToolTestRunResult invoke(long toolId, ToolInvokeRequest request);


    ToolDetailView detail(long id);

    PageView<ToolVersionInfoView> versions(long id, int page, int size);

    ToolVersionDetailView versionDetail(long id, int version);

    ResponseEntity<byte[]> download(long id, Integer version);

    ToolView rollback(long id, ToolRollbackRequest request);
}
