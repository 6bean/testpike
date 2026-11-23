package com.sixbean.testpike.core.storage.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.storage.LocalStorageProvider;
import com.sixbean.testpike.core.executor.domain.component.StorageRouter;
import com.sixbean.testpike.core.storage.domain.service.LocalStorageFileService;
import org.springframework.stereotype.Service;


@Service
public class LocalStorageFileServiceImpl implements LocalStorageFileService {

    private static final String VERB_GET = "GET";
    private static final String VERB_PUT = "PUT";

    private final StorageRouter storageRouter;

    public LocalStorageFileServiceImpl(StorageRouter storageRouter) {
        this.storageRouter = storageRouter;
    }

    @Override
    public byte[] download(String key, long expiresEpochSec, String signature) {
        requireStructuralKey(key);
        requireSigned(VERB_GET, key, expiresEpochSec, signature);
        try {
            return provider().get(key);
        } catch (IllegalArgumentException e) {
            throw new ServiceException(e.getMessage(), BizErrorCode.VALIDATION_ERROR);
        }
    }

    @Override
    public void upload(String key, long expiresEpochSec, String signature, byte[] content) {
        requireStructuralKey(key);
        requireSigned(VERB_PUT, key, expiresEpochSec, signature);
        try {
            provider().put(key, content == null ? new byte[0] : content, null);
        } catch (IllegalArgumentException e) {
            throw new ServiceException(e.getMessage(), BizErrorCode.VALIDATION_ERROR);
        }
    }

    @Override
    public LocalStorageProvider provider() {
        return storageRouter.localProvider();
    }

    
    private static void requireStructuralKey(String key) {
        if (key == null || key.isBlank() || key.startsWith("/")) {
            throw new ServiceException("对象 key 非法: " + key, BizErrorCode.VALIDATION_ERROR);
        }
        for (String segment : key.split("/")) {
            if (segment.equals("..") || segment.isBlank()) {
                throw new ServiceException("对象 key 越界（含 .. 或空段）: " + key,
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
    }

    
    private void requireSigned(String verb, String key, long expiresEpochSec, String signature) {
        if (!provider().verifySignedUrl(verb, key, expiresEpochSec, signature)) {
            throw new ServiceException("下载/上传链接无效或已过期，请重新获取",
                    BizErrorCode.FORBIDDEN);
        }
    }
}
