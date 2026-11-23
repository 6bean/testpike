package com.sixbean.testpike.admin.webelement.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.webelement.api.dto.CensusPostingBridgeResult;
import com.sixbean.testpike.admin.webelement.api.dto.CreateWebElementRequest;
import com.sixbean.testpike.admin.webelement.api.dto.HealEvidencePage;
import com.sixbean.testpike.admin.webelement.api.dto.ReferencingCasePage;
import com.sixbean.testpike.admin.webelement.api.dto.SurveyBridgeResult;
import com.sixbean.testpike.admin.webelement.api.dto.SurveyEventsPage;
import com.sixbean.testpike.admin.webelement.api.dto.RevisionStormRow;
import com.sixbean.testpike.admin.webelement.api.dto.WebElementAnchor;
import com.sixbean.testpike.admin.webelement.api.dto.WebElementHealthRow;
import com.sixbean.testpike.admin.webelement.api.dto.WebElementLedgerView;
import com.sixbean.testpike.admin.webelement.api.dto.WebElementPageGroupView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Optional;

/**
 * RestClient implementation of the web-element ledger client. Operator id
 * rides on X-Operator-Id (audit closure) and core error codes pass through
 * unchanged, so the page sees the same 40901 stale-write posture as the tag
 * family does (shared rule, no second protocol invented here).
 */
@Component
public class RestCoreWebElementClient implements CoreWebElementClient {

    private final RestClient rest;

    public RestCoreWebElementClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public WebElementLedgerView create(CreateWebElementRequest request) {
        return call(HttpMethod.POST, "/internal/web-elements", request,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public WebElementLedgerView get(long id) {
        return call(HttpMethod.GET, "/internal/web-elements/" + id, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public List<WebElementLedgerView> list(long projectId, String pageKey) {
        String uri = UriComponentsBuilder.fromPath("/internal/web-elements")
                .queryParam("projectId", projectId)
                .queryParamIfPresent("pageKey", Optional.ofNullable(pageKey).filter(s -> !s.isBlank()))
                .build().encode().toUriString();
        return call(HttpMethod.GET, uri, null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public List<WebElementPageGroupView> pages(long projectId) {
        return call(HttpMethod.GET, "/internal/web-elements/pages?projectId=" + projectId, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public WebElementLedgerView updateAnchors(long id, List<WebElementAnchor> anchors, int expectedRevision) {
        return call(HttpMethod.PUT, "/internal/web-elements/" + id + "/anchors?expectedRevision=" + expectedRevision,
                anchors == null ? List.of() : anchors, new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public WebElementLedgerView changeState(long id, int targetState, int expectedRevision) {
        return call(HttpMethod.PUT,
                "/internal/web-elements/" + id + "/state?targetState=" + targetState
                        + "&expectedRevision=" + expectedRevision,
                null, new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public WebElementLedgerView rename(long id, String newName, int expectedRevision) {
        return call(HttpMethod.PUT,
                "/internal/web-elements/" + id + "/name?expectedRevision=" + expectedRevision,
                java.util.Map.of("name", newName == null ? "" : newName),
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public SurveyBridgeResult survey(long projectId, String startUrl) {
        return call(HttpMethod.POST, "/internal/web-elements/survey",
                java.util.Map.of("projectId", projectId, "startUrl", startUrl),
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public CensusPostingBridgeResult postCensusLedgerDrafts(long projectId) {
        return censusCall("/internal/web-elements/census/ledger-drafts", projectId);
    }

    @Override
    public CensusPostingBridgeResult postCensusScriptRefDrafts(long projectId) {
        return censusCall("/internal/web-elements/census/script-ref-drafts", projectId);
    }

    /** One body shape for both census doors (the anchor is the whole payload). */
    private CensusPostingBridgeResult censusCall(String uri, long projectId) {
        return call(HttpMethod.POST, uri, java.util.Map.of("projectId", projectId),
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public HealEvidencePage healEvidenceByTask(long taskId) {
        return call(HttpMethod.GET,
                "/internal/web-elements/heal-evidence?taskId=" + taskId,
                null, new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public HealEvidencePage healEvidenceByElement(long id, int page, int pageSize) {
        String uri = UriComponentsBuilder.fromPath("/internal/web-elements/" + id + "/heal-evidence")
                .queryParam("page", page)
                .queryParam("pageSize", pageSize)
                .build().encode().toUriString();
        return call(HttpMethod.GET, uri, null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public ReferencingCasePage referencingCases(long id, int page, int pageSize) {
        String uri = UriComponentsBuilder.fromPath("/internal/web-elements/" + id + "/referencing-cases")
                .queryParam("page", page)
                .queryParam("pageSize", pageSize)
                .build().encode().toUriString();
        return call(HttpMethod.GET, uri, null, new ParameterizedTypeReference<>() {
        });
    }

    /**
 * Survey tail window read door (R62 segment c). A null limit is not sent —
 * the core window reader then defers to the hot key ai.stream.chunk-size
 * (same two-door posture as the steps tail, no default claim here).
 */
    @Override
    public SurveyEventsPage surveyProcessEvents(long sessionId, long cursor, Integer limit) {
        UriComponentsBuilder uri = UriComponentsBuilder
                .fromPath("/internal/web-elements/survey/" + sessionId + "/process-events")
                .queryParam("cursor", cursor);
        if (limit != null) {
            uri.queryParam("limit", limit);
        }
        return call(HttpMethod.GET, uri.build().encode().toUriString(), null,
                new ParameterizedTypeReference<>() {
                });
    }

    /**
 * Health read door (web-element observation surface). A blank page key is
 * not sent — core then reads the whole project segment, same two-door
 * posture as the list door.
 */
    @Override
    public List<WebElementHealthRow> health(long projectId, String pageKey) {
        String uri = UriComponentsBuilder.fromPath("/internal/web-elements/health")
                .queryParam("projectId", projectId)
                .queryParamIfPresent("pageKey", Optional.ofNullable(pageKey).filter(s -> !s.isBlank()))
                .build().encode().toUriString();
        return call(HttpMethod.GET, uri, null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public List<RevisionStormRow> storms(long projectId) {
        return call(HttpMethod.GET, "/internal/web-elements/storms?projectId=" + projectId, null,
                new ParameterizedTypeReference<>() {
                });
    }

    /** One call shape for all doors (public logic lives in RestClientInvoker, no duplication). */
    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }
}
