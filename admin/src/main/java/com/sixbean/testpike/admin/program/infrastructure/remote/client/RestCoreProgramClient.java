package com.sixbean.testpike.admin.program.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.program.api.dto.CreateProgramRequest;
import com.sixbean.testpike.admin.program.api.dto.ProgramMemberAddRequest;
import com.sixbean.testpike.admin.program.api.dto.ProgramMemberRoleRequest;
import com.sixbean.testpike.admin.program.api.dto.ProgramMemberView;
import com.sixbean.testpike.admin.program.api.dto.ProgramView;
import com.sixbean.testpike.admin.program.api.dto.UpdateProgramRequest;
import com.sixbean.testpike.admin.program.api.dto.UserProgramView;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.client.RestClientInvoker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;

/**
 * RestClient implementation of {@link CoreProgramClient} (admin → core HTTP bridge).
 * <p>X-Operator-Id carries the current user so core keeps its audit chain closed;
 * error codes (400/403/404/409 semantics) pass through unchanged. Shared call logic
 * lives in {@link RestClientInvoker} — this class only maps endpoints.
 */
@Component
public class RestCoreProgramClient implements CoreProgramClient {

    private final RestClient rest;

    public RestCoreProgramClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public long createProgram(CreateProgramRequest request) {
        Long id = call(HttpMethod.POST, "/internal/programs", request,
                new ParameterizedTypeReference<Response<Long>>() { });
        return id == null ? 0L : id;
    }

    @Override
    public void updateProgram(long id, UpdateProgramRequest request) {
        call(HttpMethod.PUT, "/internal/programs/" + id, request,
                new ParameterizedTypeReference<Response<Void>>() { });
    }

    @Override
    public void setProgramState(long id, int state) {
        String uri = UriComponentsBuilder.fromPath("/internal/programs/" + id + "/state")
                .queryParam("state", state)
                .build().encode().toUriString();
        call(HttpMethod.PUT, uri, null, new ParameterizedTypeReference<Response<Void>>() { });
    }

    @Override
    public void deleteProgram(long id) {
        call(HttpMethod.DELETE, "/internal/programs/" + id, null,
                new ParameterizedTypeReference<Response<Void>>() { });
    }

    @Override
    public ProgramView getProgram(long id) {
        return call(HttpMethod.GET, "/internal/programs/" + id, null,
                new ParameterizedTypeReference<Response<ProgramView>>() { });
    }

    @Override
    public List<ProgramView> listPrograms() {
        return call(HttpMethod.GET, "/internal/programs", null,
                new ParameterizedTypeReference<Response<List<ProgramView>>>() { });
    }

    @Override
    public List<UserProgramView> listProgramsForUser(long userId, boolean systemManager) {
        String uri = UriComponentsBuilder.fromPath("/internal/programs/for-user")
                .queryParam("userId", userId)
                .queryParam("systemManager", systemManager)
                .build().encode().toUriString();
        return call(HttpMethod.GET, uri, null,
                new ParameterizedTypeReference<Response<List<UserProgramView>>>() { });
    }

    @Override
    public void attachProject(long id, long projectId) {
        call(HttpMethod.POST, "/internal/programs/" + id + "/projects/" + projectId, null,
                new ParameterizedTypeReference<Response<Void>>() { });
    }

    @Override
    public void detachProject(long id, long projectId) {
        call(HttpMethod.DELETE, "/internal/programs/" + id + "/projects/" + projectId, null,
                new ParameterizedTypeReference<Response<Void>>() { });
    }

    @Override
    public List<Long> listProgramProjectIds(long id) {
        return call(HttpMethod.GET, "/internal/programs/" + id + "/projects", null,
                new ParameterizedTypeReference<Response<List<Long>>>() { });
    }

    @Override
    public void addMember(long id, ProgramMemberAddRequest request) {
        call(HttpMethod.POST, "/internal/programs/" + id + "/members", request,
                new ParameterizedTypeReference<Response<Void>>() { });
    }

    @Override
    public void updateMemberRole(long id, long userId, ProgramMemberRoleRequest request) {
        call(HttpMethod.PUT, "/internal/programs/" + id + "/members/" + userId, request,
                new ParameterizedTypeReference<Response<Void>>() { });
    }

    @Override
    public void removeMember(long id, long userId) {
        call(HttpMethod.DELETE, "/internal/programs/" + id + "/members/" + userId, null,
                new ParameterizedTypeReference<Response<Void>>() { });
    }

    @Override
    public List<ProgramMemberView> listMembers(long id) {
        return call(HttpMethod.GET, "/internal/programs/" + id + "/members", null,
                new ParameterizedTypeReference<Response<List<ProgramMemberView>>>() { });
    }

    /** Uniform invocation (operator id forwarded for X-Operator-Id; shared logic in RestClientInvoker). */
    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }
}
