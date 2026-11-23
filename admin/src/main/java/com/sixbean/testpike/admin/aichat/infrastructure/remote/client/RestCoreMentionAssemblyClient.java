package com.sixbean.testpike.admin.aichat.infrastructure.remote.client;

import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * RestClient implementation of the mention-assembly doors (AO batch
 * stage 1): reuses {@link RestClientInvoker} — same transport, same
 * X-Operator-Id posture as every other core bridge, no second call helper.
 * <p>The tool read passes the actor id so core answers the five-way-gated
 * set; the skill read stays header-less, mirroring the S5 consumption seam
 * (its membership guard is the admin-side project check in front of it).
 */
@Component
public class RestCoreMentionAssemblyClient implements CoreMentionAssemblyClient {

    private final RestClient rest;

    public RestCoreMentionAssemblyClient(
            @Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public List<MentionSkillRow> listSkillAssembly(long projectId) {
        List<MentionSkillRow> rows = RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/skills/project/" + projectId + "/assembly", null,
                new ParameterizedTypeReference<Response<List<MentionSkillRow>>>() {
                }, null);
        return rows == null ? List.of() : rows;
    }

    @Override
    public List<MentionToolRow> listToolAssembly(long projectId, long actorId) {
        List<MentionToolRow> rows = RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/tools/project/" + projectId + "/assembly", null,
                new ParameterizedTypeReference<Response<List<MentionToolRow>>>() {
                }, actorId);
        return rows == null ? List.of() : rows;
    }
}
