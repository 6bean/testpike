package com.sixbean.testpike.core.storage.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.storage.LocalStorageProvider;
import com.sixbean.testpike.core.config.api.dto.ConfigInfo;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.component.ConfigCipher;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.executor.domain.component.AliyunOssStorageProvider;
import com.sixbean.testpike.core.executor.domain.component.S3StorageProvider;
import com.sixbean.testpike.core.executor.domain.component.StorageRouter;
import com.sixbean.testpike.core.storage.api.dto.StorageConfigInput;
import com.sixbean.testpike.core.storage.api.dto.StorageConfigView;
import com.sixbean.testpike.core.storage.domain.service.StorageConfigService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;


@Service
public class StorageConfigServiceImpl implements StorageConfigService {

    static final String GROUP = "存储";
    static final String KEY_PROVIDER = "storage.provider";
    static final String KEY_PREFIX = "storage.key-prefix";
    static final String KEY_LOCAL_ROOT = "storage.local-root";
    static final String KEY_URL_TTL = "storage.url-ttl";
    static final String KEY_OSS_ENDPOINT = "storage.oss.endpoint";
    static final String KEY_OSS_BUCKET = "storage.oss.bucket";
    static final String KEY_OSS_AK = "storage.oss.access-key-id";
    static final String KEY_OSS_SK = "storage.oss.access-key-secret";

 
    private static final long TTL_MIN_MINUTES = 1;
    private static final long TTL_MAX_MINUTES = 7 * 24 * 60;

    private final ConfigService configService;

    public StorageConfigServiceImpl(ConfigService configService) {
        this.configService = configService;
    }

    @Override
    public StorageConfigView get() {
        Map<String, ConfigInfo> byKey = loadGroup();
        return new StorageConfigView(display(byKey, KEY_PROVIDER), display(byKey, KEY_PREFIX),
                display(byKey, KEY_URL_TTL), display(byKey, KEY_LOCAL_ROOT),
                display(byKey, KEY_OSS_ENDPOINT), display(byKey, KEY_OSS_BUCKET),
                display(byKey, KEY_OSS_AK), display(byKey, KEY_OSS_SK));
    }

    @Override
    @Transactional
    public StorageConfigView update(Long operatorId, StorageConfigInput input) {
        Map<String, ConfigInfo> byKey = loadGroup();
        String provider = normalizeProvider(input.provider());
        validateTtl(input.urlTtl());
        String effectiveProvider = provider != null ? provider : plain(KEY_PROVIDER);
        if (LocalStorageProvider.NAME.equals(effectiveProvider)) {
            String root = input.localRoot() != null ? input.localRoot().trim() : plain(KEY_LOCAL_ROOT);
            if (root.isEmpty()) {
                throw new ServiceException("存储提供方选 local 时必须配置 storage.local-root（本地落盘根目录），"
                        + "请在「系统设置 → 配置管理」填写该参数后重新保存",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        if (AliyunOssStorageProvider.NAME.equals(effectiveProvider)) {
            List<String> missing = new ArrayList<>();
            if (effectiveBlank(input.ossEndpoint(), KEY_OSS_ENDPOINT)) {
                missing.add(KEY_OSS_ENDPOINT);
            }
            if (effectiveBlank(input.ossBucket(), KEY_OSS_BUCKET)) {
                missing.add(KEY_OSS_BUCKET);
            }
            if (effectiveBlank(input.ossAccessKeyId(), KEY_OSS_AK)) {
                missing.add(KEY_OSS_AK);
            }
            if (effectiveBlank(input.ossAccessKeySecret(), KEY_OSS_SK)) {
                missing.add(KEY_OSS_SK);
            }
            if (!missing.isEmpty()) {
                throw new ServiceException("存储提供方选 aliyun-oss 时必须配置 "
                        + String.join(" / ", missing) + "（当前缺失），"
                        + "请在「系统设置 → 配置管理」补齐上述参数后重新保存",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        writeKey(operatorId, byKey, KEY_PROVIDER, provider);
        writeKey(operatorId, byKey, KEY_PREFIX, trimToNull(input.keyPrefix()));
        writeKey(operatorId, byKey, KEY_URL_TTL, input.urlTtl() == null ? null : input.urlTtl().trim());
        writeKey(operatorId, byKey, KEY_LOCAL_ROOT, trimToNull(input.localRoot()));
        writeKey(operatorId, byKey, KEY_OSS_ENDPOINT, trimToNull(input.ossEndpoint()));
        writeKey(operatorId, byKey, KEY_OSS_BUCKET, trimToNull(input.ossBucket()));
        writeKey(operatorId, byKey, KEY_OSS_AK, input.ossAccessKeyId());
        writeKey(operatorId, byKey, KEY_OSS_SK, input.ossAccessKeySecret());
        return get();
    }

    
    private static String normalizeProvider(String raw) {
        if (raw == null) {
            return null;
        }
        String p = raw.trim().toLowerCase();
        if (p.isEmpty() || !StorageRouter.PROVIDER_ENUM.contains(p)) {
            throw new ServiceException("存储提供方仅支持 "
                    + String.join("/", StorageRouter.PROVIDER_ENUM.stream().sorted().toList())
                    + "（当前值 \"" + raw + "\"），请在「系统设置 → 配置管理」改成上述取值之一",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return p;
    }

    private static void validateTtl(String raw) {
        if (raw == null) {
            return;
        }
        long minutes;
        try {
            minutes = Long.parseLong(raw.trim());
        } catch (RuntimeException e) {
            throw new ServiceException("预签名时效必须是整数分钟（当前值 \"" + raw + "\"），"
                    + "请在「系统设置 → 配置管理」改成纯数字后保存",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (minutes < TTL_MIN_MINUTES || minutes > TTL_MAX_MINUTES) {
            throw new ServiceException("预签名时效必须在 1~10080 分钟区间（上限 7 天=对象存储签名约束，当前 "
                    + minutes + "），请在「系统设置 → 配置管理」改成区间内的整数分钟",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    private boolean effectiveBlank(String submitted, String key) {
        if (submitted != null && !ConfigCipher.MASK.equals(submitted) && !submitted.isBlank()) {
            return false;
        }
        return plain(key).isBlank();
    }

    private String plain(String key) {
        String value = configService.getValue(key, "");
        return value == null ? "" : value.trim();
    }

    private void writeKey(Long operatorId, Map<String, ConfigInfo> byKey, String key, String value) {
        if (value == null || ConfigCipher.MASK.equals(value)) {
            return;
        }
        ConfigInfo current = byKey.get(key);
        if (current == null) {
            configService.create(operatorId, new ConfigInput(key, value, GROUP, "string", 0,
                    "K11 存储配置（T157）"));
            return;
        }
        if (current.encryptFlag() == 0 && Objects.equals(current.configValue(), value)) {
            return;
        }
        configService.update(operatorId, current.id(), new ConfigInput(key, value,
                current.groupName(), current.valueType(), current.encryptFlag(), current.description()));
    }

    private Map<String, ConfigInfo> loadGroup() {
        Map<String, ConfigInfo> byKey = new HashMap<>();
        for (ConfigInfo info : configService.list(GROUP)) {
            byKey.put(info.configKey(), info);
        }
        return byKey;
    }

    private static String display(Map<String, ConfigInfo> byKey, String key) {
        ConfigInfo info = byKey.get(key);
        return info == null ? "" : Objects.requireNonNullElse(info.configValue(), "");
    }

    private static String trimToNull(String value) {
        return value == null ? null : value.trim();
    }

}
