package com.sixbean.testpike.admin.storage.infrastructure.remote.client;

import org.springframework.http.ResponseEntity;


public interface StorageFileClient {

    
    ResponseEntity<byte[]> download(String key, long expires, String signature);

    
    ResponseEntity<Void> upload(String key, long expires, String signature, byte[] content);
}
