package com.sixbean.testpike.admin.tool.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.core.infrastructure.remote.client.HttpForwardHeaders;
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
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;


@Component
public class RestToolClient implements ToolClient {

    private final RestClient rest;

    public RestToolClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public PageView<ToolView> list(String keyword, Integer type, Integer status, Long projectId,
                                   String category, Long tagId, String sortBy, String sortDir,
                                   int page, int size) {
        StringBuilder uri = new StringBuilder("/internal/tools?page=").append(page)
                .append("&size=").append(size);
        if (keyword != null && !keyword.isBlank()) {
            uri.append("&keyword=").append(URLEncoder.encode(keyword, StandardCharsets.UTF_8));
        }
        if (type != null) {
            uri.append("&type=").append(type);
        }
        if (status != null) {
            uri.append("&status=").append(status);
        }
        if (projectId != null) {
            uri.append("&projectId=").append(projectId);
        }
        if (category != null && !category.isBlank()) {
            uri.append("&category=").append(URLEncoder.encode(category, StandardCharsets.UTF_8));
        }
        if (tagId != null) {
            uri.append("&tagId=").append(tagId);
        }
        if (sortBy != null && !sortBy.isBlank()) {
            uri.append("&sortBy=").append(URLEncoder.encode(sortBy, StandardCharsets.UTF_8));
        }
        if (sortDir != null && !sortDir.isBlank()) {
            uri.append("&sortDir=").append(sortDir);
        }
        return call(HttpMethod.GET, uri.toString(), null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public ToolView create(ToolCreateRequest request) {
        return call(HttpMethod.POST, "/internal/tools", request, toolType());
    }

    @Override
    public ToolView get(long id) {
        return call(HttpMethod.GET, "/internal/tools/" + id, null, toolType());
    }

    @Override
    public ToolView update(long id, ToolUpdateRequest request) {
        return call(HttpMethod.PUT, "/internal/tools/" + id, request, toolType());
    }

    @Override
    public void delete(long id, int expectedVersion) {
        call(HttpMethod.DELETE, "/internal/tools/" + id + "?expectedVersion=" + expectedVersion,
                null, new ParameterizedTypeReference<Response<Void>>() {
                });
    }

    @Override
    public ToolView submit(long id, int expectedVersion) {
        return call(HttpMethod.POST, postAction(id, "submit", expectedVersion), null, toolType());
    }

    @Override
    public ToolView approve(long id, int expectedVersion) {
        return call(HttpMethod.POST, postAction(id, "approve", expectedVersion), null, toolType());
    }

    @Override
    public ToolView reject(long id, ToolRejectRequest request) {
        return call(HttpMethod.POST, "/internal/tools/" + id + "/reject", request, toolType());
    }

    @Override
    public ToolView retire(long id, int expectedVersion) {
        return call(HttpMethod.POST, postAction(id, "retire", expectedVersion), null, toolType());
    }


    @Override
    public PageView<ToolMarketItemView> marketList(int page, int size, String keyword, Integer type,
                                                   String category, String sortBy, String sortDir) {
        StringBuilder uri = new StringBuilder("/internal/tools/market?page=").append(page)
                .append("&size=").append(size);
        if (keyword != null && !keyword.isBlank()) {
            uri.append("&keyword=").append(URLEncoder.encode(keyword, StandardCharsets.UTF_8));
        }
        if (type != null) {
            uri.append("&type=").append(type);
        }
        if (category != null && !category.isBlank()) {
            uri.append("&category=").append(URLEncoder.encode(category, StandardCharsets.UTF_8));
        }
        if (sortBy != null && !sortBy.isBlank()) {
            uri.append("&sortBy=").append(URLEncoder.encode(sortBy, StandardCharsets.UTF_8));
        }
        if (sortDir != null && !sortDir.isBlank()) {
            uri.append("&sortDir=").append(sortDir);
        }
        return call(HttpMethod.GET, uri.toString(), null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public ToolMarketDownloadView marketDownload(long id, String format) {
        return call(HttpMethod.GET, "/internal/tools/market/" + id + "/download?format="
                + URLEncoder.encode(format == null ? "" : format, StandardCharsets.UTF_8),
                null, new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public ToolView marketImport(Long projectId, String filename, byte[] content) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", content, MediaType.APPLICATION_OCTET_STREAM).filename(filename);
        if (projectId != null) {
            body.part("projectId", projectId);
        }
        return RestClientInvoker.callMultipart(rest, "/internal/tools/market/import", body,
                toolType(), UserContext.currentUserId());
    }

    
    private String postAction(long id, String action, int expectedVersion) {
        return "/internal/tools/" + id + "/" + action + "?expectedVersion=" + expectedVersion;
    }


    @Override
    public ProjectToolView projectTools(long projectId) {
        return call(HttpMethod.GET, "/internal/tools/project/" + projectId, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public ProjectToolView saveProjectTools(long projectId, ProjectToolBindingRequest request) {
        return call(HttpMethod.PUT, "/internal/tools/project/" + projectId, request,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public PageView<ToolInvocationView> invocations(long toolId, String start, String end,
                                                    int page, int size) {
        StringBuilder uri = new StringBuilder("/internal/tools/").append(toolId)
                .append("/invocations?page=").append(page).append("&size=").append(size);
        if (start != null && !start.isBlank()) {
            uri.append("&start=").append(URLEncoder.encode(start.trim(), StandardCharsets.UTF_8));
        }
        if (end != null && !end.isBlank()) {
            uri.append("&end=").append(URLEncoder.encode(end.trim(), StandardCharsets.UTF_8));
        }
        return call(HttpMethod.GET, uri.toString(), null, new ParameterizedTypeReference<>() {
        });
    }


    @Override
    public ToolTestRunResult testRun(long toolId, ToolTestRunInput input) {
        return call(HttpMethod.POST, "/internal/tools/" + toolId + "/test-run", input,
                new ParameterizedTypeReference<>() {
                });
    }


    @Override
    public ToolConfirmTicketView confirmTicket(long toolId) {
        return call(HttpMethod.POST, "/internal/tools/" + toolId + "/confirm-token", null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public ToolTestRunResult invoke(long toolId, ToolInvokeRequest request) {
        return call(HttpMethod.POST, "/internal/tools/" + toolId + "/invoke", request,
                new ParameterizedTypeReference<>() {
                });
    }


    @Override
    public ToolDetailView detail(long id) {
        return call(HttpMethod.GET, "/internal/tools/" + id + "/detail", null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public PageView<ToolVersionInfoView> versions(long id, int page, int size) {
        return call(HttpMethod.GET, "/internal/tools/" + id + "/versions?page=" + page
                + "&size=" + size, null, new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public ToolVersionDetailView versionDetail(long id, int version) {
        return call(HttpMethod.GET, "/internal/tools/" + id + "/versions/" + version, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public ResponseEntity<byte[]> download(long id, Integer version) {
        String uri = "/internal/tools/" + id + "/download"
                + (version == null ? "" : "?version=" + version);
        ResponseEntity<byte[]> resp = rest.get().uri(URI.create(uri))
                .header(RestClientInvoker.OPERATOR_HEADER, String.valueOf(UserContext.currentUserId()))
                .retrieve()
                .toEntity(new ParameterizedTypeReference<>() {
                });
        if (resp == null) {
            return ResponseEntity.internalServerError().build();
        }
        return ResponseEntity.status(resp.getStatusCode())
                .headers(HttpForwardHeaders.strip(resp.getHeaders()))
                .body(resp.getBody());
    }

    @Override
    public ToolView rollback(long id, ToolRollbackRequest request) {
        return call(HttpMethod.POST, "/internal/tools/" + id + "/rollback", request, toolType());
    }

    private ParameterizedTypeReference<Response<ToolView>> toolType() {
        return new ParameterizedTypeReference<>() {
        };
    }

    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }
}
