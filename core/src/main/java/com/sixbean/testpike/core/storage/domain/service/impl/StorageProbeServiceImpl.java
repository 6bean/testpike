package com.sixbean.testpike.core.storage.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.storage.LocalStorageProvider;
import com.sixbean.testpike.common.storage.StorageKeys;
import com.sixbean.testpike.common.storage.StorageProvider;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.executor.domain.component.AliyunOssStorageProvider;
import com.sixbean.testpike.core.executor.domain.component.S3StorageProvider;
import com.sixbean.testpike.core.executor.domain.component.StorageRouter;
import com.sixbean.testpike.core.storage.api.dto.StorageProbeRequest;
import com.sixbean.testpike.core.storage.api.dto.StorageProbeResult;
import com.sixbean.testpike.core.storage.domain.component.ProbeConfigOverride;
import com.sixbean.testpike.core.storage.domain.service.StorageProbeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * <ul>
 * </ul>
 *
 */
@Service
public class StorageProbeServiceImpl implements StorageProbeService {

    private static final Logger log = LoggerFactory.getLogger(StorageProbeServiceImpl.class);

    
    private static final byte[] PROBE_PAYLOAD =
            ("testpike-storage-probe-" + UUID.randomUUID()).getBytes();
    private static final String PROBE_CONTENT_TYPE = "text/plain";

    private final ConfigService configService;
    private final S3StorageProvider s3Provider;
    private final StorageRouter storageRouter;
    private final String localPublicBaseUrl;
    private final String localSignSecret;

    public StorageProbeServiceImpl(ConfigService configService,
                                   S3StorageProvider s3Provider,
                                   StorageRouter storageRouter,
                                   @Value("${storage.local.public-base-url:http://localhost:8081/storage/files}")
                                   String localPublicBaseUrl,
                                   @Value("${storage.local.sign-secret:}") String localSignSecret) {
        this.configService = configService;
        this.s3Provider = s3Provider;
        this.storageRouter = storageRouter;
        this.localPublicBaseUrl = localPublicBaseUrl;
        this.localSignSecret = localSignSecret;
    }

    @Override
    public StorageProbeResult probe(StorageProbeRequest request) {
        String provider = requireProvider(request.provider());
        StorageProvider target = switch (provider) {
            case LocalStorageProvider.NAME -> localProvider(request);
            case S3StorageProvider.NAME -> s3Provider;
            case AliyunOssStorageProvider.NAME -> ossProvider(request);
            default -> throw new ServiceException("暂不支持的连通测试提供方: " + provider + "；请在「系统配置」的存储连通测试中选择支持的提供方",
                    BizErrorCode.VALIDATION_ERROR);
        };
        String key = StorageKeys.build(storageRouter.keyPrefix(), 0L,
                "exec-artifact", "probe.txt");
        try {
            target.put(key, PROBE_PAYLOAD, PROBE_CONTENT_TYPE);
            byte[] back = target.get(key);
            if (!java.util.Arrays.equals(PROBE_PAYLOAD, back)) {
                return StorageProbeResult.fail(provider, StorageProbeResult.CATEGORY_UNKNOWN,
                        "连通失败：探针对象读回内容与写入不一致");
            }
            return StorageProbeResult.ok(provider, key);
        } catch (Exception e) {
            String detail = chainMessage(e);
            String category = classify(detail);
            log.info("存储连通测试失败 provider={} category={} err={}", provider, category, detail);
            return StorageProbeResult.fail(provider, category, humanFail(provider, category, detail));
        } finally {
            try {
                target.delete(key);
            } catch (Exception ignore) {
            }
            if (target instanceof AliyunOssStorageProvider tmpOss) {
                tmpOss.shutdownClient();
            }
        }
    }

    
    private static String requireProvider(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ServiceException("必须指定存储提供方 provider（可选 "
                    + String.join("/", StorageRouter.PROVIDER_ENUM.stream().sorted().toList()) + "）；请在「系统配置」的存储连通测试表单选择其一后重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String p = raw.trim().toLowerCase(Locale.ROOT);
        if (!StorageRouter.PROVIDER_ENUM.contains(p)) {
            throw new ServiceException("存储提供方仅支持 "
                    + String.join("/", StorageRouter.PROVIDER_ENUM.stream().sorted().toList())
                    + "（当前值 \"" + raw + "\"）；请改成上述清单内的取值后重试（提供方在「系统配置」的存储连通测试表单选择）", BizErrorCode.VALIDATION_ERROR);
        }
        return p;
    }

    
    private StorageProvider localProvider(StorageProbeRequest request) {
        String root = effective(request.localRoot(), "storage.local-root");
        if (root.isBlank()) {
            throw new ServiceException("本地存储未配置：请填写 localRoot 或在系统配置设置 "
                    + "storage.local-root 根目录（入口：「系统配置」页检索参数 storage.local-root）", BizErrorCode.VALIDATION_ERROR);
        }
        return new LocalStorageProvider(root, localPublicBaseUrl, localSignSecret);
    }

    
    private StorageProvider ossProvider(StorageProbeRequest request) {
        Map<String, String> overrides = new HashMap<>();
        putOverride(overrides, "storage.oss.endpoint", request.ossEndpoint());
        putOverride(overrides, "storage.oss.bucket", request.ossBucket());
        putOverride(overrides, "storage.oss.access-key-id", request.ossAccessKeyId());
        putOverride(overrides, "storage.oss.access-key-secret", request.ossAccessKeySecret());
        return new AliyunOssStorageProvider(new ProbeConfigOverride(configService, overrides));
    }

    private static void putOverride(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    
    private String effective(String submitted, String configKey) {
        if (submitted != null && !submitted.isBlank()) {
            return submitted.trim();
        }
        String saved = configService.getValue(configKey, "");
        return saved == null ? "" : saved.trim();
    }

    
    public static String classify(String message) {
        String m = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (containsAny(m, "invalidaccesskey", "accessdenied", "signature", "forbidden",
                "403", "invalid credential", "access key", "accesskey", "securitytoken",
                "nosuchuser", "invalidaccesskeyid", "unauthorized", "凭据未配置", "凭据缺失")) {
            return StorageProbeResult.CATEGORY_AUTH;
        }
        if (containsAny(m, "nosuchbucket", "bucketnotfound", "no such bucket",
                "specified bucket", "bucket does not exist")) {
            return StorageProbeResult.CATEGORY_BUCKET;
        }
        if (containsAny(m, "unknownhost", "connection refused", "connect to", "timeout",
                "timed out", "socket", "unreachable", "no route to host", "ssl",
                "name or service not known", "failed to resolve", "network")) {
            return StorageProbeResult.CATEGORY_NETWORK;
        }
        return StorageProbeResult.CATEGORY_UNKNOWN;
    }

    private static boolean containsAny(String text, String... keys) {
        for (String k : keys) {
            if (text.contains(k)) {
                return true;
            }
        }
        return false;
    }

    
    private static String humanFail(String provider, String category, String detail) {
        String label = switch (category) {
            case StorageProbeResult.CATEGORY_NETWORK -> "网络不可达";
            case StorageProbeResult.CATEGORY_AUTH -> "鉴权失败（AK/SK 或签名不正确）";
            case StorageProbeResult.CATEGORY_BUCKET -> "桶不存在或不可用";
            default -> "未识别错误";
        };
        return "连通测试失败[" + label + "] provider=" + provider + "：" + detail;
    }

    
    private static String chainMessage(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable t = e;
        while (t != null) {
            if (sb.length() > 0) {
                sb.append(" | caused by: ");
            }
            sb.append(t.getClass().getSimpleName())
                    .append(t.getMessage() == null ? "" : ": " + t.getMessage());
            t = t.getCause() == t ? null : t.getCause();
        }
        return sb.toString();
    }
}
