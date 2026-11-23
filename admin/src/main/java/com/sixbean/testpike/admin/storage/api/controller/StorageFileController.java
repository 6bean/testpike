package com.sixbean.testpike.admin.storage.api.controller;

import com.sixbean.testpike.admin.storage.infrastructure.remote.client.StorageFileClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/storage/files")
public class StorageFileController {

    private static final String PATH_PREFIX = "/storage/files/";

    private final StorageFileClient storageFileClient;

    public StorageFileController(StorageFileClient storageFileClient) {
        this.storageFileClient = storageFileClient;
    }

    @GetMapping("/**")
    public ResponseEntity<byte[]> download(HttpServletRequest request,
                                           @RequestParam("expires") long expires,
                                           @RequestParam("signature") String signature) {
        return storageFileClient.download(extractKey(request), expires, signature);
    }

    @PutMapping("/**")
    public ResponseEntity<Void> upload(HttpServletRequest request,
                                       @RequestParam("expires") long expires,
                                       @RequestParam("signature") String signature,
                                       @RequestBody(required = false) byte[] content) {
        return storageFileClient.upload(extractKey(request), expires, signature, content);
    }

    
    private static String extractKey(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String ctx = request.getContextPath();
        if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) {
            uri = uri.substring(ctx.length());
        }
        int idx = uri.indexOf(PATH_PREFIX);
        return idx < 0 ? "" : uri.substring(idx + PATH_PREFIX.length());
    }
}
