package com.sixbean.testpike.core.executor.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.storage.StorageGrant;
import com.sixbean.testpike.common.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * S3-compatible object storage backend (industry-standard AWS Signature V4 wire protocol).
 *
 * <p>Speaks the plain S3 API through the official AWS SDK v2, so the same implementation
 * targets a self-hosted SeaweedFS S3 gateway (default deployment, docker compose
 * {@code testpike-seaweedfs}) or any cloud-hosted S3-compatible endpoint (AWS S3, Aliyun
 * OSS S3 endpoint, S3 replacement, ...) purely by configuration: endpoint, region and
 * bucket live in {@code .env} / environment variables (R48 install-time customization,
 * never inlined). Presigned PUT/GET uploads/downloads follow the 03 protocol 2.5 pattern:
 * the application never proxies artifact bytes.
 *
 * <p>Bucket is created lazily and idempotently (per-chain slot buckets such as
 * {@code tp-slot1} come from {@code S3_BUCKET}, so parallel dev chains stay isolated on a
 * shared gateway). Credentials missing fail closed at construction, naming the exact env
 * keys. Presign TTL is clamped to the SigV4 maximum of 7 days by {@link StorageRouter}.
 *
 * <p>Implementation notes proven against SeaweedFS 4.53 (docs/change-eval design record,
 * 2026-11-16):
 * <ul>
 *   <li>Only SigV4 presigned URLs are used. SigV2 presigns are broken against S3 gateways
 *       that include Content-Type in the canonical string, so clients must never fall back
 *       to V2 (AWS SDK v2 does not).</li>
 *   <li>Path-style access is the default (required by SeaweedFS and most self-hosted
 *       gateways); disable via {@code S3_PATH_STYLE=false} for virtual-host-style clouds.</li>
 * </ul>
 */
@Component("s3StorageProvider")
public class S3StorageProvider implements StorageProvider {

    /** Provider id (storage.provider enum value; replaces the retired "s3" value). */
    public static final String NAME = "s3";

    private static final Logger log = LoggerFactory.getLogger(S3StorageProvider.class);

    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final String bucket;

    public S3StorageProvider(@Value("${s3.endpoint:http://localhost:8333}") String endpoint,
                             @Value("${s3.access-key:}") String accessKey,
                             @Value("${s3.secret-key:}") String secretKey,
                             @Value("${s3.bucket:testpike-exec}") String bucket,
                             @Value("${s3.region:us-east-1}") String region,
                             @Value("${s3.path-style:true}") boolean pathStyle) {
        if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            throw new ServiceException("S3 对象存储凭据缺失（.env S3_ACCESS_KEY/S3_SECRET_KEY）",
                    BizErrorCode.INTERNAL_ERROR);
        }
        URI endpointUri = URI.create(endpoint.endsWith("/")
                ? endpoint.substring(0, endpoint.length() - 1) : endpoint);
        StaticCredentialsProvider credentials =
                StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        S3ClientBuilderParams params = new S3ClientBuilderParams(endpointUri, credentials,
                Region.of(region == null || region.isBlank() ? "us-east-1" : region), pathStyle);
        this.s3Client = params.buildClient();
        this.presigner = params.buildPresigner();
        this.bucket = bucket;
    }

    /** Keeps client and presigner wired from one set of values (no drift between the two). */
    private record S3ClientBuilderParams(URI endpoint, StaticCredentialsProvider credentials,
                                         Region region, boolean pathStyle) {
        S3Configuration serviceOptions() {
            return S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build();
        }

        S3Client buildClient() {
            return S3Client.builder()
                    .endpointOverride(endpoint)
                    .credentialsProvider(credentials)
                    .region(region)
                    .serviceConfiguration(serviceOptions())
                    .build();
        }

        S3Presigner buildPresigner() {
            return S3Presigner.builder()
                    .endpointOverride(endpoint)
                    .credentialsProvider(credentials)
                    .region(region)
                    .serviceConfiguration(serviceOptions())
                    .build();
        }
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        StorageReadFailures.requireKey(key);
        ensureBucket();
        try {
            s3Client.putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(contentType == null || contentType.isBlank()
                                    ? "application/octet-stream" : contentType)
                            .build(),
                    RequestBody.fromBytes(content));
        } catch (Exception e) {
            throw new ServiceException("文件上传失败: " + e.getMessage(), BizErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    public byte[] get(String key) {
        StorageReadFailures.requireKey(key);
        try {
            return s3Client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucket).key(key).build()).asByteArray();
        } catch (Exception e) {
            boolean missing = e instanceof NoSuchKeyException || e instanceof NoSuchBucketException
                    || (e instanceof S3Exception s3 && (s3.statusCode() == 404
                            || "NoSuchKey".equals(s3.awsErrorDetails().errorCode())
                            || "NoSuchBucket".equals(s3.awsErrorDetails().errorCode())));
            throw StorageReadFailures.fromReadException(e, key, missing);
        }
    }

    @Override
    public boolean exists(String key) {
        return StorageReadFailures.existsGuarded(key, () -> {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        }, log, "S3");
    }

    @Override
    public StorageGrant presignPut(String key, Duration ttl) {
        StorageReadFailures.requireKey(key);
        ensureBucket();
        try {
            String url = presigner.presignPutObject(PutObjectPresignRequest.builder()
                            .signatureDuration(ttl)
                            .putObjectRequest(PutObjectRequest.builder()
                                    .bucket(bucket).key(key).build())
                            .build())
                    .url().toString();
            return new StorageGrant(key, url);
        } catch (Exception e) {
            throw new ServiceException("预签名上传授权失败: " + e.getMessage(),
                    BizErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    public StorageGrant presignGet(String key, Duration ttl) {
        StorageReadFailures.requireKey(key);
        try {
            String url = presigner.presignGetObject(GetObjectPresignRequest.builder()
                            .signatureDuration(ttl)
                            .getObjectRequest(GetObjectRequest.builder()
                                    .bucket(bucket).key(key).build())
                            .build())
                    .url().toString();
            return new StorageGrant(key, url);
        } catch (Exception e) {
            throw new ServiceException("预签名下载授权失败: " + e.getMessage(),
                    BizErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    public void delete(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (Exception e) {
            // Idempotent sweep semantics (aligned with Q6 retention cleanup): a missing
            // object or transient failure must not break the cleanup loop.
            log.warn("S3 object delete failed (tolerated idempotently): key={} err={}",
                    key, e.getMessage());
        }
    }

    @Override
    public List<String> list(String prefix, int limit) {
        List<String> keys = new ArrayList<>();
        try {
            var response = s3Client.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(prefix == null ? "" : prefix)
                    .maxKeys(Math.max(1, limit))
                    .build());
            for (S3Object object : response.contents()) {
                keys.add(object.key());
                if (keys.size() >= limit) {
                    break;
                }
            }
            return keys;
        } catch (NoSuchBucketException e) {
            return List.of();
        } catch (Exception e) {
            throw new ServiceException("对象列表读取失败: " + e.getMessage(),
                    BizErrorCode.INTERNAL_ERROR);
        }
    }

    /** Idempotent bucket bootstrap (per-chain buckets are just names on a shared gateway). */
    private synchronized void ensureBucket() {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException e) {
            try {
                s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                log.info("S3 bucket created: {}", bucket);
            } catch (Exception createError) {
                // Concurrent bootstrap from another node is fine; re-check existence.
                if (!bucketPresent()) {
                    throw new ServiceException("对象存储桶初始化失败: " + bucket
                            + "（" + createError.getMessage() + "）", BizErrorCode.INTERNAL_ERROR);
                }
            }
        } catch (Exception e) {
            // Some gateways answer head-bucket with 403 for valid buckets; only a definite
            // NoSuchBucket may proceed to create, anything else keeps the old bucket as-is.
            log.debug("S3 head-bucket inconclusive: bucket={} err={}", bucket, e.getMessage());
        }
    }

    private boolean bucketPresent() {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    /** Test/ops hook: size of an object (content-length), -1 when absent. */
    public long contentLength(String key) {
        try {
            HeadObjectResponse head = s3Client.headObject(
                    HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return head.contentLength();
        } catch (Exception e) {
            return -1L;
        }
    }
}
