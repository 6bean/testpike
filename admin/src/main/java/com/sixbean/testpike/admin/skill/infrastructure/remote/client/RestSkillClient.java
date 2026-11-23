package com.sixbean.testpike.admin.skill.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
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
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;


@Component
public class RestSkillClient implements SkillClient {

    private final RestClient rest;

    public RestSkillClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public PageView<SkillView> list(String keyword, Integer type, Integer status, Long projectId,
                                    Long tagId, String sortBy, String sortDir, int page, int size) {
        StringBuilder uri = new StringBuilder("/internal/skills?page=").append(page)
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
    public SkillView create(SkillCreateRequest request) {
        return call(HttpMethod.POST, "/internal/skills", request, skillType());
    }

    @Override
    public SkillView get(long id) {
        return call(HttpMethod.GET, "/internal/skills/" + id, null, skillType());
    }

    @Override
    public SkillView update(long id, SkillUpdateRequest request) {
        return call(HttpMethod.PUT, "/internal/skills/" + id, request, skillType());
    }

    @Override
    public void delete(long id, int expectedVersion) {
        call(HttpMethod.DELETE, "/internal/skills/" + id + "?expectedVersion=" + expectedVersion,
                null, new ParameterizedTypeReference<Response<Void>>() {
                });
    }


    @Override
    public PageView<SkillRecycleView> recycleBin(Long projectId, String keyword, int page, int size) {
        StringBuilder uri = new StringBuilder("/internal/skills/recycle-bin?page=").append(page)
                .append("&size=").append(size);
        if (projectId != null) {
            uri.append("&projectId=").append(projectId);
        }
        if (keyword != null && !keyword.isBlank()) {
            uri.append("&keyword=").append(URLEncoder.encode(keyword, StandardCharsets.UTF_8));
        }
        return call(HttpMethod.GET, uri.toString(), null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public SkillRecycleView recycleEntry(long id) {
        return call(HttpMethod.GET, "/internal/skills/" + id + "/recycle-entry", null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public SkillView restore(long id, int expectedVersion) {
        return call(HttpMethod.PUT, postAction(id, "restore", expectedVersion), null, skillType());
    }

    @Override
    public SkillView submit(long id, int expectedVersion) {
        return call(HttpMethod.POST, postAction(id, "submit", expectedVersion), null, skillType());
    }

    @Override
    public SkillView approve(long id, int expectedVersion) {
        return call(HttpMethod.POST, postAction(id, "approve", expectedVersion), null, skillType());
    }

    @Override
    public SkillView reject(long id, SkillRejectRequest request) {
        return call(HttpMethod.POST, "/internal/skills/" + id + "/reject",
                request, skillType());
    }

    @Override
    public SkillView retire(long id, int expectedVersion) {
        return call(HttpMethod.POST, postAction(id, "retire", expectedVersion), null, skillType());
    }

    @Override
    public List<SkillVersionView> versions(long id) {
        return call(HttpMethod.GET, "/internal/skills/" + id + "/versions", null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public SkillVersionDetailView version(long id, int version) {
        return call(HttpMethod.GET, "/internal/skills/" + id + "/versions/" + version, null,
                new ParameterizedTypeReference<>() {
                });
    }

    
    private String postAction(long id, String action, int expectedVersion) {
        return "/internal/skills/" + id + "/" + action + "?expectedVersion=" + expectedVersion;
    }


    @Override
    public PageView<SkillMarketItemView> marketList(int page, int size, String keyword, Integer type,
                                                    Long tagId, String sortBy, String sortDir) {
        StringBuilder uri = new StringBuilder("/internal/skills/market?page=").append(page)
                .append("&size=").append(size);
        if (keyword != null && !keyword.isBlank()) {
            uri.append("&keyword=").append(URLEncoder.encode(keyword, StandardCharsets.UTF_8));
        }
        if (type != null) {
            uri.append("&type=").append(type);
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
    public SkillMarketDownloadView marketDownload(long id, String format) {
        return call(HttpMethod.GET, "/internal/skills/market/" + id + "/download?format="
                + URLEncoder.encode(format == null ? "" : format, StandardCharsets.UTF_8),
                null, new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public SkillView marketImport(Long projectId, String filename, byte[] content) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", content, MediaType.APPLICATION_OCTET_STREAM).filename(filename);
        if (projectId != null) {
            body.part("projectId", projectId);
        }
        return RestClientInvoker.callMultipart(rest, "/internal/skills/market/import", body,
                skillType(), UserContext.currentUserId());
    }


    @Override
    public ProjectSkillView projectSkills(long projectId) {
        return call(HttpMethod.GET, "/internal/skills/project/" + projectId, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public ProjectSkillView saveProjectSkills(long projectId, ProjectSkillBindingRequest request) {
        return call(HttpMethod.PUT, "/internal/skills/project/" + projectId, request,
                new ParameterizedTypeReference<>() {
                });
    }

    private ParameterizedTypeReference<Response<SkillView>> skillType() {
        return new ParameterizedTypeReference<>() {
        };
    }

    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }
}
