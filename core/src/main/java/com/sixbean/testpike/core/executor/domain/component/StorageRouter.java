package com.sixbean.testpike.core.executor.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.storage.LocalStorageProvider;
import com.sixbean.testpike.common.storage.StorageProvider;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

/**
 * <ul>
 * </ul>
 *
 */
@Component
public class StorageRouter {

    private static final Logger log = LoggerFactory.getLogger(StorageRouter.class);

    
    public static final int MODE_SYSTEM_DEFAULT = 1;
    public static final int MODE_LOCAL = 2;
    public static final int MODE_CLOUD = 3;

    
    public static final Set<String> PROVIDER_ENUM =
            Set.of(LocalStorageProvider.NAME, S3StorageProvider.NAME, AliyunOssStorageProvider.NAME);

    
    private static final long DEFAULT_TTL_MINUTES = 15;
    private static final long MAX_TTL_MINUTES = 7 * 24 * 60L;

    private final ConfigService configService;
    private final StorageProvider s3Provider;
    private final StorageProvider ossProvider;
    private final String localPublicBaseUrl;
    private final String localSignSecret;

    
    private volatile LocalStorageProvider localProvider;
    private volatile String localFingerprint;

    public StorageRouter(ConfigService configService,
                         @Qualifier("s3StorageProvider") StorageProvider s3Provider,
                         @Qualifier("aliyunOssStorageProvider") StorageProvider ossProvider,
                         @Value("${storage.local.public-base-url:http://localhost:8081/storage/files}")
                         String localPublicBaseUrl,
                         @Value("${storage.local.sign-secret:}") String localSignSecret) {
        this.configService = configService;
        this.s3Provider = s3Provider;
        this.ossProvider = ossProvider;
        this.localPublicBaseUrl = localPublicBaseUrl;
        this.localSignSecret = localSignSecret;
    }

    
    public StorageRoute route(Integer storageMode, String projectPath) {
        String prefix = normalizePrefix(projectPath);
        int mode = storageMode == null ? MODE_SYSTEM_DEFAULT : storageMode;
        return switch (mode) {
            case MODE_SYSTEM_DEFAULT -> new StorageRoute(systemProvider(), prefix);
            case MODE_LOCAL -> new StorageRoute(localProvider(), prefix);
            case MODE_CLOUD -> new StorageRoute(cloudProvider(), prefix);
            default -> throw new ServiceException(
                    "未知存储模式 storage_mode=" + mode + "（可选 1=系统默认 2=本地 3=云），"
                            + "请在项目存储设置中改为上述模式之一后保存（示例：storage_mode=2）",
                    BizErrorCode.VALIDATION_ERROR);
        };
    }

    
    public StorageProvider systemProvider() {
        String configured = configService.getValue("storage.provider", "local").trim();
        return switch (configured) {
            case S3StorageProvider.NAME -> s3Provider;
            case AliyunOssStorageProvider.NAME -> ossProvider;
            case LocalStorageProvider.NAME -> localProvider();
            default -> throw new ServiceException(
                    "storage.provider 配置值非法: " + configured
                            + "（可选 " + String.join("/", PROVIDER_ENUM.stream().sorted().toList()) + "），"
                            + "请前往「系统配置」把 storage.provider 改成上述可选值之一",
                    BizErrorCode.VALIDATION_ERROR);
        };
    }

    
    public Duration urlTtl() {
        long minutes;
        try {
            minutes = Long.parseLong(configService.getValue("storage.url-ttl", "15").trim());
        } catch (NumberFormatException e) {
            minutes = DEFAULT_TTL_MINUTES;
        }
        return Duration.ofMinutes(Math.max(1, Math.min(minutes, MAX_TTL_MINUTES)));
    }

 
    public String keyPrefix() {
        return configService.getValue("storage.key-prefix", "").trim();
    }

    
    private StorageProvider cloudProvider() {
        String configured = configService.getValue("storage.provider", "local").trim();
        return switch (configured) {
            case S3StorageProvider.NAME -> s3Provider;
            case AliyunOssStorageProvider.NAME -> ossProvider;
            case LocalStorageProvider.NAME -> {
                log.warn("项目存储模式=云(3)，但系统 storage.provider=local 无云后端可落，"
                        + "已回退系统默认（本地）；如需云存储请在系统配置页将 storage.provider 改为 "
                        + "s3 或 aliyun-oss");
                yield localProvider();
            }
            default -> throw new ServiceException(
                    "storage.provider 配置值非法: " + configured
                            + "（可选 " + String.join("/", PROVIDER_ENUM.stream().sorted().toList()) + "），"
                            + "请前往「系统配置」把 storage.provider 改成上述可选值之一",
                    BizErrorCode.VALIDATION_ERROR);
        };
    }

    
    public LocalStorageProvider localProvider() {
        String root = configService.getValue("storage.local-root", "").trim();
        if (root.isEmpty()) {
            throw new ServiceException("本地存储未配置：请先在系统配置页设置 storage.local-root 根目录"
                    + "（示例：/data/testpike-files）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String fingerprint = root + "|" + localSignSecret + "|" + localPublicBaseUrl;
        LocalStorageProvider current = localProvider;
        if (current == null || !fingerprint.equals(localFingerprint)) {
            synchronized (this) {
                if (localProvider == null || !fingerprint.equals(localFingerprint)) {
                    localProvider = new LocalStorageProvider(root, localPublicBaseUrl, localSignSecret);
                    localFingerprint = fingerprint;
                }
                current = localProvider;
            }
        }
        return current;
    }

    
    private String normalizePrefix(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            return "";
        }
        String trimmed = projectPath.replaceAll("^/+|/+$", "").trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        for (String segment : trimmed.split("/")) {
            if (segment.equals("..") || segment.isBlank()) {
                throw new ServiceException("项目存储路径非法（含越界段）: " + projectPath
                        + "（应为桶内前缀或相对子目录，如 rec-2），请改成桶内前缀写法后保存"
                        + "（示例：rec-2）", BizErrorCode.VALIDATION_ERROR);
            }
        }
        return trimmed;
    }
}
