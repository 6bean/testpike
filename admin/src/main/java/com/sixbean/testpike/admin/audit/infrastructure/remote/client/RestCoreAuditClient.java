package com.sixbean.testpike.admin.audit.infrastructure.remote.client;

import com.sixbean.testpike.admin.audit.api.dto.AccessDeniedAuditRequest;
import com.sixbean.testpike.admin.audit.api.dto.AuditLogView;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.CursorView;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.ChainVerifyResult;
import com.sixbean.testpike.common.clientobject.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Optional;


@Component
public class RestCoreAuditClient implements CoreAuditClient {

    private static final Logger log = LoggerFactory.getLogger(RestCoreAuditClient.class);

    private final RestClient rest;

    public RestCoreAuditClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    
    @Override
    public void writeAccessDenied(AccessDeniedAuditRequest request) {
        try {
            postAuditLog(request);
        } catch (Exception e) {
            log.warn("越权审计写入失败（不阻断 403 判定）deniedProject={} err={}",
                    request.projectId(), e.getMessage());
        }
    }

    
    @Override
    public void writeScriptRegenOverwrite(Long operatorId, long projectId, long caseId,
                                          long scriptVersion, long taskId) {
        try {
            postAuditLog(new AccessDeniedAuditRequest(operatorId, projectId, "script",
                    String.valueOf(caseId), "script_regen_overwrite",
                    java.util.Map.of("scriptVersion", scriptVersion),
                    java.util.Map.of("taskId", taskId)));
        } catch (Exception e) {
            log.warn("重生成覆盖审计写入失败（不阻断任务创建）caseId={} err={}",
                    caseId, e.getMessage());
        }
    }

    
    private void postAuditLog(AccessDeniedAuditRequest request) {
        RestClientInvoker.call(rest, HttpMethod.POST, "/internal/audit-logs", request,
                new ParameterizedTypeReference<Response<Void>>() {
                }, request.operatorId());
    }

    @Override
    public PageView<AuditLogView> query(Long operatorId, Long projectId, String objectType,
                                        String objectId, String action, int page, int size,
                                        String sortBy, String sortDir) {
        String uri = UriComponentsBuilder.fromPath("/internal/audit-logs")
                .queryParamIfPresent("operatorId", Optional.ofNullable(operatorId))
                .queryParamIfPresent("projectId", Optional.ofNullable(projectId))
                .queryParamIfPresent("objectType", Optional.ofNullable(objectType))
                .queryParamIfPresent("objectId", Optional.ofNullable(objectId))
                .queryParamIfPresent("action", Optional.ofNullable(action))
                .queryParam("page", page)
                .queryParam("size", size)
                .queryParamIfPresent("sortBy", Optional.ofNullable(sortBy))
                .queryParamIfPresent("sortDir", Optional.ofNullable(sortDir))
                .build().encode().toUriString();
        return RestClientInvoker.call(rest, HttpMethod.GET, uri, null,
                new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public CursorView<AuditLogView> queryCursor(Long operatorId, Long projectId, String objectType,
                                                String objectId, String action, Long afterId, int size) {
        String uri = UriComponentsBuilder.fromPath("/internal/audit-logs/cursor")
                .queryParamIfPresent("operatorId", Optional.ofNullable(operatorId))
                .queryParamIfPresent("projectId", Optional.ofNullable(projectId))
                .queryParamIfPresent("objectType", Optional.ofNullable(objectType))
                .queryParamIfPresent("objectId", Optional.ofNullable(objectId))
                .queryParamIfPresent("action", Optional.ofNullable(action))
                .queryParamIfPresent("afterId", Optional.ofNullable(afterId))
                .queryParam("size", size)
                .build().encode().toUriString();
        return RestClientInvoker.call(rest, HttpMethod.GET, uri, null,
                new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public boolean verifyChain() {
        Boolean result = RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/audit-logs/chain-verify", null,
                new ParameterizedTypeReference<Response<Boolean>>() {
                }, null);
        return Boolean.TRUE.equals(result);
    }

    @Override
    public ChainVerifyResult verifyChainStructured() {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/audit-logs/chain-verify", null,
                new ParameterizedTypeReference<Response<ChainVerifyResult>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public ChainVerifyResult chainVerifyTask(long taskId) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/audit-logs/chain-verify/" + taskId, null,
                new ParameterizedTypeReference<Response<ChainVerifyResult>>() {
                }, null);
    }
}
