package com.sixbean.testpike.admin.core.infrastructure.remote.client;

import com.sixbean.testpike.admin.core.api.dto.UserProjectInfo;

import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

public interface CoreUserProjectClient {

    
    List<UserProjectInfo> listUserProjects(long userId, boolean systemManager);

    
    @Component
    class RestUserProjectClient implements CoreUserProjectClient {

        private final RestClient rest;

        public RestUserProjectClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
            this.rest = RestClient.builder().baseUrl(coreBaseUrl).build();
        }

        @Override
        public List<UserProjectInfo> listUserProjects(long userId, boolean systemManager) {
            Response<List<UserProjectInfo>> resp = rest.method(HttpMethod.GET)
                    .uri("/internal/user-projects?userId={userId}&systemManager={systemManager}",
                            userId, systemManager)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (resp == null || !"0".equals(resp.getCode())) {
                throw new IllegalStateException("core 服务返回异常: " + (resp == null ? "空响应" : resp.getMsg()));
            }
            return resp.getData() == null ? List.of() : resp.getData();
        }
    }
}
