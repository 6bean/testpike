package com.sixbean.testpike.core.executor.domain.component;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.ObjectListing;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.storage.StorageGrant;
import com.sixbean.testpike.common.storage.StorageProvider;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;


@Component("aliyunOssStorageProvider")
public class AliyunOssStorageProvider implements StorageProvider {

 
    public static final String NAME = "aliyun-oss";

    private static final Logger log = LoggerFactory.getLogger(AliyunOssStorageProvider.class);

    
    private static final long MAX_TTL_MINUTES = 7 * 24 * 60L;

    
    private static final long DEFAULT_TTL_MINUTES = 15;

    private final ConfigService configService;

    
    private volatile OSS client;
    private volatile String fingerprint;

    public AliyunOssStorageProvider(ConfigService configService) {
        this.configService = configService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        StorageReadFailures.requireKey(key);
        try {
            var meta = new com.aliyun.oss.model.ObjectMetadata();
            meta.setContentLength(content.length);
            meta.setContentType(contentType == null || contentType.isBlank()
                    ? "application/octet-stream" : contentType);
            oss().putObject(bucket(), key, new ByteArrayInputStream(content), meta);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("文件上传失败: " + e.getMessage()
                    + "，请确认对象存储端点可达后重新发起上传（storage.oss.endpoint，"
                    + "示例 https://oss-cn-shanghai.aliyuncs.com）", BizErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    public byte[] get(String key) {
        StorageReadFailures.requireKey(key);
        try (OSSObject object = oss().getObject(bucket(), key);
             InputStream in = object.getObjectContent()) {
            return in.readAllBytes();
        } catch (Exception e) {
            boolean missing = e instanceof com.aliyun.oss.OSSException o
                    && ("NoSuchKey".equals(o.getErrorCode())
                        || "NoSuchBucket".equals(o.getErrorCode()));
            throw StorageReadFailures.fromReadException(e, key, missing);
        }
    }

    @Override
    public boolean exists(String key) {
        return StorageReadFailures.existsGuarded(key, () -> oss().doesObjectExist(bucket(), key),
                log, "OSS");
    }

    @Override
    public StorageGrant presignPut(String key, Duration ttl) {
        return presign(HttpMethod.PUT, key, ttl);
    }

    @Override
    public StorageGrant presignGet(String key, Duration ttl) {
        return presign(HttpMethod.GET, key, ttl);
    }

    @Override
    public void delete(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            oss().deleteObject(bucket(), key);
        } catch (Exception e) {
            log.warn("OSS 对象删除失败（幂等容忍）: key={} err={}", key, e.getMessage());
        }
    }

    @Override
    public List<String> list(String prefix, int limit) {
        List<String> keys = new ArrayList<>();
        try {
            ListObjectsRequest req = new ListObjectsRequest(bucket())
                    .withPrefix(prefix == null ? "" : prefix)
                    .withMaxKeys(Math.max(1, Math.min(limit, 1000)));
            ObjectListing listing = oss().listObjects(req);
            for (OSSObjectSummary s : listing.getObjectSummaries()) {
                if (keys.size() >= limit) {
                    break;
                }
                keys.add(s.getKey());
            }
        } catch (Exception e) {
            throw new ServiceException("OSS 列举失败: " + e.getMessage()
                    + "，请确认桶名与读取权限后重试（示例：bucket=testpike-assets）",
                    BizErrorCode.INTERNAL_ERROR);
        }
        return keys;
    }

    
    private StorageGrant presign(HttpMethod method, String key, Duration ttl) {
        StorageReadFailures.requireKey(key);
        long minutes = ttl == null ? DEFAULT_TTL_MINUTES
                : Math.max(1, Math.min(ttl.toMinutes(), MAX_TTL_MINUTES));
        try {
            GeneratePresignedUrlRequest req =
                    new GeneratePresignedUrlRequest(bucket(), key, method);
            req.setExpiration(new Date(System.currentTimeMillis() + minutes * 60_000L));
            String url = oss().generatePresignedUrl(req).toString();
            return new StorageGrant(key, url);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("预签名授权失败: " + e.getMessage()
                    + "，请前往「系统配置」核对阿里云 OSS 凭据后重试", BizErrorCode.INTERNAL_ERROR);
        }
    }

    
    private OSS oss() {
        String endpoint = config("storage.oss.endpoint", "");
        String ak = config("storage.oss.access-key-id", "");
        String sk = config("storage.oss.access-key-secret", "");
        if (ak.isBlank() || sk.isBlank()) {
            throw new ServiceException("OSS 凭据未配置（storage.oss.access-key-id/access-key-secret，K8 加密键），"
                    + "云存储模式不可用，请在「系统配置」页填写这两项并保存", BizErrorCode.INTERNAL_ERROR);
        }
        String fp = endpoint + '\0' + ak.hashCode() + '\0' + sk.hashCode();
        OSS current = client;
        if (current != null && fp.equals(fingerprint)) {
            return current;
        }
        synchronized (this) {
            if (client != null && fp.equals(fingerprint)) {
                return client;
            }
            OSS previous = client;
            ClientBuilderConfiguration cc = new ClientBuilderConfiguration();
            cc.setConnectionTimeout(3_000);
            cc.setSocketTimeout(10_000);
            cc.setMaxErrorRetry(1);
            client = new OSSClientBuilder().build(endpoint, ak, sk, cc);
            fingerprint = fp;
            if (previous != null) {
                previous.shutdown();
            }
            return client;
        }
    }

    private String bucket() {
        String bucket = config("storage.oss.bucket", "");
        if (bucket.isBlank()) {
            throw new ServiceException("storage.oss.bucket 未配置，云存储模式不可用，"
                            + "请在「系统配置」页补充存储桶名（示例：testpike-assets）并保存",
                    BizErrorCode.INTERNAL_ERROR);
        }
        return bucket;
    }

    
    public void shutdownClient() {
        OSS current = client;
        client = null;
        fingerprint = null;
        if (current != null) {
            current.shutdown();
        }
    }

    
    private String config(String key, String defaultValue) {
        return Objects.requireNonNullElse(configService.getValue(key, defaultValue), "");
    }

}
