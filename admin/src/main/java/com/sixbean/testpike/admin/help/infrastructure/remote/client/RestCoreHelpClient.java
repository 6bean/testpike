package com.sixbean.testpike.admin.help.infrastructure.remote.client;

import com.sixbean.testpike.admin.help.api.dto.HelpDocView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;


@Component
public class RestCoreHelpClient implements CoreHelpClient {

    private final RestClient rest;

    public RestCoreHelpClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public List<HelpDocView> list() {
        return RestClientInvoker.call(rest, HttpMethod.GET, "/internal/help/docs", null,
                new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public List<HelpDocView> detail(String domainCode) {
        String uri = UriComponentsBuilder.fromPath("/internal/help/docs")
                .pathSegment(domainCode == null ? "" : domainCode)
                .build().encode().toUriString();
        return RestClientInvoker.call(rest, HttpMethod.GET, uri, null,
                new ParameterizedTypeReference<>() {
                }, null);
    }
}
