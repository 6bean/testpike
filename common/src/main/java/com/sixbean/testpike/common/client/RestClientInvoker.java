package com.sixbean.testpike.common.client;

import com.sixbean.testpike.common.exception.BizErrorCode;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.client.RestClient;

import java.net.URI;

/**
 * <ul>
 * </ul>
 *
 */
public final class RestClientInvoker {

    
    public static final String OPERATOR_HEADER = "X-Operator-Id";

    private RestClientInvoker() {
    }

    
    public static <T> T call(RestClient rest, HttpMethod method, String uri, Object body,
                             ParameterizedTypeReference<Response<T>> type, Long operatorId) {
        RestClient.RequestBodySpec spec = rest.method(method).uri(URI.create(uri));
        if (operatorId != null) {
            spec = spec.header(OPERATOR_HEADER, String.valueOf(operatorId));
        }
        if (body != null) {
            spec = spec.body(body);
        }
        return handleResponse(spec.retrieve().body(type));
    }

    
    public static <T> T callMultipart(RestClient rest, String uri, MultipartBodyBuilder body,
                                      ParameterizedTypeReference<Response<T>> type, Long operatorId) {
        RestClient.RequestBodySpec spec = rest.method(HttpMethod.POST).uri(URI.create(uri));
        if (operatorId != null) {
            spec = spec.header(OPERATOR_HEADER, String.valueOf(operatorId));
        }
        if (body != null) {
            spec = spec.body(body.build());
        }
        return handleResponse(spec.retrieve().body(type));
    }

    
    private static <T> T handleResponse(Response<T> resp) {
        if (resp == null || !"0".equals(resp.getCode())) {
            String message = resp == null ? "core 服务返回空响应"
                    : (resp.getMsg() == null ? "core 服务处理失败" : String.valueOf(resp.getMsg()));
            int code = resp == null ? BizErrorCode.INTERNAL_ERROR : parseCode(resp.getCode());
            throw new ServiceException(message, code);
        }
        return resp.getData();
    }

    
    private static final java.time.Duration INTERNAL_READ_TIMEOUT = java.time.Duration.ofSeconds(30);

    
    private static final int INTERNAL_CONNECT_TIMEOUT_MS = 3000;

    
    public static RestClient defaultRestClient(String baseUrl) {
        return defaultRestClient(baseUrl, INTERNAL_READ_TIMEOUT);
    }

    
    public static RestClient defaultRestClient(String baseUrl, java.time.Duration readTimeout) {
        org.springframework.http.client.JdkClientHttpRequestFactory factory =
                new org.springframework.http.client.JdkClientHttpRequestFactory(
                        java.net.http.HttpClient.newBuilder()
                                .connectTimeout(java.time.Duration.ofMillis(INTERNAL_CONNECT_TIMEOUT_MS))
                                .build());
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultStatusHandler(org.springframework.http.HttpStatusCode::isError,
                        (request, response) -> {
                        })
                .build();
    }

    private static int parseCode(String code) {
        try {
            return Integer.parseInt(code);
        } catch (NumberFormatException e) {
            return BizErrorCode.INTERNAL_ERROR;
        }
    }
}
