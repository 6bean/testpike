package com.sixbean.testpike.admin.storage.infrastructure.remote.client;

import com.sixbean.testpike.admin.core.infrastructure.remote.client.HttpForwardHeaders;
import com.sixbean.testpike.common.client.RestClientInvoker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;


@Component
public class RestStorageFileClient implements StorageFileClient {

    private final RestClient rest;

    public RestStorageFileClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public ResponseEntity<byte[]> download(String key, long expires, String signature) {
        return forward(HttpMethod.GET, key, expires, signature, null);
    }

    @Override
    public ResponseEntity<Void> upload(String key, long expires, String signature, byte[] content) {
        ResponseEntity<byte[]> resp = forward(HttpMethod.PUT, key, expires, signature, content);
        return ResponseEntity.status(resp.getStatusCode()).build();
    }

    private ResponseEntity<byte[]> forward(HttpMethod method, String key, long expires,
                                           String signature, byte[] body) {
        URI uri = URI.create("/internal/storage/local/" + encodeKeySegments(key)
                + "?expires=" + expires + "&signature=" + enc(signature));
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri);
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_OCTET_STREAM).body(body);
        }
        ResponseEntity<byte[]> resp = spec.retrieve().toEntity(new ParameterizedTypeReference<>() {
        });
        if (resp == null) {
            return ResponseEntity.internalServerError().build();
        }
        return ResponseEntity.status(resp.getStatusCode())
                .headers(HttpForwardHeaders.strip(resp.getHeaders()))
                .body(resp.getBody());
    }

    
    static String encodeKeySegments(String key) {
        String[] segments = key.split("/", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(URLEncoder.encode(segments[i], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
